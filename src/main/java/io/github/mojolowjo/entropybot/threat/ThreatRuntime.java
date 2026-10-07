package io.github.mojolowjo.entropybot.threat;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * B2 threat test, the game side (docs/BRAIN_PLAN.md 5.2): once a second (from the reflex tick) copies the block grid
 * around the bot and samples the hostile mobs on the game thread, then one worker thread runs the two reach searches
 * (walkers, spiders) and {@link ThreatRules#decide} per mob. The reflexes read the latest decisions through
 * {@link #counts}. Loader notes: vanilla getters only (Level.getBlockState, BlockState.getCollisionShape,
 * Mob.isAggressive, Entity.getYHeadRot, LivingEntity.hasLineOfSight, Level.getMaxLocalRawBrightness); no mixin, no Baritone.
 * Errors: counted, the first 5 logged in full then one a minute; a failed or late search falls back to the old test.
 */
public final class ThreatRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    public static final ThreatRuntime INSTANCE = new ThreatRuntime();

    public static final int R = 16, DOWN = 8, UP = 8;
    static final int PERIOD = 20;
    static final long FRESH_MS = 3000;
    static final double SLOW_COPY_MS = 4;
    static final double OVERRUN_MS = 5;
    static final int RING = 32;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entropybot-threat");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private record Job(ReachGrid grid, int bx, int by, int bz, List<ThreatRules.MobSample> mobs, Map<Integer, int[]> cells) {}

    private record Out(Map<Integer, ThreatRules.Decision> decisions, int[] lit, double bfsMs, long at, ReachGrid grid, int[] walk) {}

    private Future<Out> pending;
    private long lastTick = -1000;
    // results (game thread only, except the volatile snapshot for the state writer)
    private volatile Map<Integer, ThreatRules.Decision> latest = Map.of();
    private final Map<Integer, Double> prevReach = new HashMap<>();
    private final ArrayDeque<String> ring = new ArrayDeque<>();
    private volatile int[] litSpot;
    private volatile Out lastOut;
    private volatile long lastAnswerMs;
    // counters
    private long copies, searches, fallbacks, errors, counted, noted, slowSkips;
    private double copyMsSum, copyMsMax, bfsMsSum, bfsMsMax, lastCopyMs, lastBfsMs;
    private final ArrayDeque<double[]> recent = new ArrayDeque<>();     // {copyMs, bfsMs} of the last 10
    private int slowStreak, skipCycles, mobsNear;
    private long lastErrorLog;
    private String lastError;
    private volatile String verdictLine;
    private long verdictAt;

    private ThreatRuntime() {}

    /** Every client tick from the reflexes (game thread). hurt: the reflexes' "just hit" window. Never throws. */
    public void tick(Minecraft mc, LocalPlayer p, long tick, boolean hurt) {
        try {
            if (tick - lastTick < PERIOD) return;
            lastTick = tick;
            collect();
            if (pending != null) {                  // the last search still runs: skip this second
                fallbacks++;
                return;
            }
            if (skipCycles > 0) {
                skipCycles--;
                fallbacks++;
                return;
            }
            long t0 = System.nanoTime();
            ReachGrid g = snapshot(mc.level, p);
            Map<Integer, int[]> cells = new HashMap<>();
            List<ThreatRules.MobSample> mobs = sample(mc, p, hurt, cells);
            double copyMs = (System.nanoTime() - t0) / 1e6;
            copies++;
            lastCopyMs = copyMs;
            copyMsSum += copyMs;
            copyMsMax = Math.max(copyMsMax, copyMs);
            if (copyMs > SLOW_COPY_MS) {
                if (++slowStreak >= 2) {
                    skipCycles = 10;
                    slowSkips++;
                    slowStreak = 0;
                    LOG.warn("[entropybot] threat: grid copy slow ({} ms twice), straight distance only for 10 s", Math.round(copyMs * 10) / 10.0);
                }
            } else slowStreak = 0;
            mobsNear = mobs.size();
            int bx = p.getBlockX() - g.ox, by = p.getBlockY() - g.oy, bz = p.getBlockZ() - g.oz;
            Map<Integer, Double> prev = new HashMap<>(prevReach);
            Job job = new Job(g, bx, by, bz, mobs, cells);
            pending = worker.submit(() -> run(job, prev));
        } catch (Throwable t) {
            error("tick", t);
        }
    }

    /** The worker: two searches and the decisions. */
    private static Out run(Job j, Map<Integer, Double> prev) {
        long t0 = System.nanoTime();
        ReachGrid g = j.grid();
        int[] walk = g.search(j.bx(), j.by(), j.bz(), false);
        boolean anySpider = false;
        for (ThreatRules.MobSample m : j.mobs()) anySpider |= ThreatRules.moveOf(m.kind()) == ThreatRules.Move.SPIDER;
        int[] spider = anySpider ? g.search(j.bx(), j.by(), j.bz(), true) : null;
        Map<Integer, ThreatRules.Decision> out = new HashMap<>();
        for (ThreatRules.MobSample m : j.mobs()) {
            int[] c = j.cells().get(m.id());
            int pd = -2;
            if (c != null) pd = g.distAt(ThreatRules.moveOf(m.kind()) == ThreatRules.Move.SPIDER && spider != null ? spider : walk, c[0], c[1], c[2]);
            Double pr = prev.get(m.id());
            ThreatRules.MobSample s = new ThreatRules.MobSample(m.id(), m.kind(), m.straight(), m.aggressive(), m.yawOff(), m.seen(), m.hitMe(),
                    pd, pr == null ? Double.NaN : pr);
            out.put(m.id(), ThreatRules.decide(s));
        }
        int[] lit = g.nearestLit(walk, FightOrFlee.LIT, FightOrFlee.LIT_MAX_DIST);
        return new Out(out, lit, (System.nanoTime() - t0) / 1e6, System.currentTimeMillis(), g, walk);
    }


    /** Takes a finished search's results (game thread). */
    private void collect() {
        Future<Out> f = pending;
        if (f == null || !f.isDone()) return;
        pending = null;
        try {
            Out o = f.get();
            searches++;
            lastBfsMs = o.bfsMs();
            bfsMsSum += o.bfsMs();
            bfsMsMax = Math.max(bfsMsMax, o.bfsMs());
            recent.addLast(new double[]{lastCopyMs, o.bfsMs()});
            while (recent.size() > 10) recent.removeFirst();
            Map<Integer, ThreatRules.Decision> old = latest;
            prevReach.clear();
            for (ThreatRules.Decision d : o.decisions().values()) {
                if (!Double.isNaN(d.reach())) prevReach.put(d.id(), d.reach());
                if (d.counts()) counted++; else noted++;
                ThreatRules.Decision was = old.get(d.id());
                if (was == null || was.counts() != d.counts() || !was.rule().equals(d.rule())) remember(d);
            }
            latest = o.decisions();
            litSpot = o.lit();
            lastOut = o;
            lastAnswerMs = o.at();
        } catch (Throwable t) {
            fallbacks++;
            error("search", t.getCause() != null ? t.getCause() : t);
        }
    }

    private synchronized void remember(ThreatRules.Decision d) {
        ring.addLast(java.time.LocalTime.now().withNano(0) + " #" + d.id() + " " + d.line());
        while (ring.size() > RING) ring.removeFirst();
    }

    private ReachGrid snapshot(Level level, LocalPlayer p) {
        int ox = p.getBlockX() - R, oy = p.getBlockY() - DOWN, oz = p.getBlockZ() - R;
        ReachGrid g = new ReachGrid(2 * R + 1, DOWN + UP + 1, 2 * R + 1, ox, oy, oz);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = 0; y < g.sy; y++) for (int z = 0; z < g.sz; z++) for (int x = 0; x < g.sx; x++) {
            m.set(ox + x, oy + y, oz + z);
            g.codes[g.idx(x, y, z)] = code(level, m, level.getBlockState(m));
        }
        // light only where a mob could stand (feet free, floor below)
        for (int y = 1; y < g.sy - 1; y++) for (int z = 0; z < g.sz; z++) for (int x = 0; x < g.sx; x++) {
            int i = g.idx(x, y, z);
            byte c = g.codes[i];
            if ((c != ReachGrid.AIR && c != ReachGrid.LOW) || g.codes[g.idx(x, y + 1, z)] != ReachGrid.AIR) continue;
            byte below = g.codes[g.idx(x, y - 1, z)];
            if (below != ReachGrid.SOLID && c != ReachGrid.LOW) continue;
            m.set(ox + x, oy + y, oz + z);
            g.light[i] = (byte) level.getMaxLocalRawBrightness(m);
        }
        return g;
    }

    static byte code(Level level, BlockPos pos, BlockState s) {
        if (s.isAir()) return ReachGrid.AIR;
        if (s.getFluidState().is(FluidTags.LAVA)) return ReachGrid.LAVA;
        if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.OPEN)) return ReachGrid.AIR;
        VoxelShape sh = s.getCollisionShape(level, pos);
        if (sh.isEmpty()) return s.getFluidState().is(FluidTags.WATER) ? ReachGrid.WATER : ReachGrid.AIR;
        double top = sh.max(Direction.Axis.Y);
        if (top > 1.0) return ReachGrid.TALL;
        if (top <= 0.5) return ReachGrid.LOW;
        return ReachGrid.SOLID;
    }

    private List<ThreatRules.MobSample> sample(Minecraft mc, LocalPlayer p, boolean hurt, Map<Integer, int[]> cs) {
        io.github.mojolowjo.entropybot.engine.Hostility h = io.github.mojolowjo.entropybot.engine.Hostility.INSTANCE;
        List<ThreatRules.MobSample> out = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
            double d = p.distanceTo(e);
            if (d > ThreatRules.SAMPLE_RADIUS || !h.kind(e, hurt).counts()) continue;
            out.add(sampleOf(p, e, d, h.hitMe(e)));
            cs.put(e.getId(), new int[]{e.getBlockX(), e.getBlockY(), e.getBlockZ()});
        }
        return out;
    }


    static ThreatRules.MobSample sampleOf(LocalPlayer p, Entity e, double d, boolean hitMe) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        boolean aggressive = e instanceof Mob m && m.isAggressive();
        double dx = p.getX() - e.getX(), dz = p.getZ() - e.getZ();
        float toBot = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        double yawOff = Mth.wrapDegrees(toBot - e.getYHeadRot());
        boolean seen = d <= 2.5 || (e instanceof LivingEntity le && le.hasLineOfSight(p));
        return new ThreatRules.MobSample(e.getId(), id, d, aggressive, yawOff, seen, hitMe, -2, Double.NaN);
    }

    // ---- what the rest of the mod reads ----

    /** A fresh search answered within the last 3 s. */
    public boolean gridOk() {
        return System.currentTimeMillis() - lastAnswerMs <= FRESH_MS && skipCycles == 0;
    }

    public ThreatRules.Decision decision(int entityId) { return latest.get(entityId); }

    /** The filter in front of the fight code (see {@link ThreatRules#filter}). */
    public boolean counts(Entity e, double straight, boolean hitMe) {
        return ThreatRules.filter(latest.get(e.getId()), gridOk(), straight, hitMe, e instanceof Creeper);
    }

    /** The nearest lit standing spot found by the last search {x, y, z, moves}, or null. */
    public int[] litSpot() { return gridOk() ? litSpot : null; }

    public void verdict(String line) {
        verdictLine = line;
        verdictAt = System.currentTimeMillis();
    }

    // ---- reporting ----

    /** "why threats": the last fight-or-flee verdict, then the last decisions (newest last). */
    public synchronized String why() {
        StringBuilder b = new StringBuilder();
        b.append("threat test: ").append(gridOk() ? "grid ok" : "fallback (straight distance)");
        if (verdictLine != null) b.append("\nlast verdict (").append((System.currentTimeMillis() - verdictAt) / 1000).append(" s ago): ").append(verdictLine);
        Map<Integer, ThreatRules.Decision> now = latest;
        if (!now.isEmpty() && gridOk()) {
            b.append("\nnow:");
            for (ThreatRules.Decision d : now.values()) b.append("\n  #").append(d.id()).append(' ').append(d.line());
        }
        if (ring.isEmpty()) b.append("\nno threat decisions yet");
        else {
            b.append("\nlast changes:");
            for (String s : ring) b.append("\n  ").append(s);
        }
        return b.toString();
    }

    /** "debug threats x y z": the last search at one block (a mob standing there). */
    public String probe(int x, int y, int z) {
        Out o = lastOut;
        if (o == null) return "no search yet";
        ReachGrid g = o.grid();
        int d = g.distAt(o.walk(), x, y, z);
        int lx = x - g.ox, ly = y - g.oy, lz = z - g.oz;
        if (!g.in(lx, ly, lz)) return x + " " + y + " " + z + ": outside the last grid (origin " + g.ox + " " + g.oy + " " + g.oz + ")";
        StringBuilder b = new StringBuilder(x + " " + y + " " + z + ": walk " + (d < 0 ? "no path" : d + " moves") + " | column");
        for (int yy = Math.max(0, ly - 2); yy <= Math.min(g.sy - 1, ly + 2); yy++)
            b.append(" ").append(y + yy - ly).append("=").append("ASTLWl".charAt(g.codes[g.idx(lx, yy, lz)])).append(g.walkable(lx, yy, lz) ? "*" : "");
        return b.append(" (A air, S solid, T fence/wall, L lava, W water, l low; * = a mob stands there)").toString();
    }

    /** "debug threats": the counters. */
    public String debug() {
        double avg = recentAvg();
        return "threat test: " + (gridOk() ? "grid ok" : "fallback") + ", grid " + (2 * R + 1) + "x" + (DOWN + UP + 1) + "x" + (2 * R + 1)
                + " | copies " + copies + " (last " + r1(lastCopyMs) + " ms, avg " + r1(copies == 0 ? 0 : copyMsSum / copies) + ", max " + r1(copyMsMax) + ")"
                + " | searches " + searches + " (last " + r1(lastBfsMs) + " ms, avg " + r1(searches == 0 ? 0 : bfsMsSum / searches) + ", max " + r1(bfsMsMax) + ")"
                + " | per second now " + r1(avg) + " ms | mobs sampled " + mobsNear + " | counted " + counted + ", noted " + noted
                + " | fallbacks " + fallbacks + ", slow skips " + slowSkips + ", errors " + errors
                + (lastError != null ? " (last: " + lastError + ")" : "")
                + " | last answer " + (lastAnswerMs == 0 ? "never" : (System.currentTimeMillis() - lastAnswerMs) / 1000 + " s ago");
    }

    private double recentAvg() {
        if (recent.isEmpty()) return 0;
        double s = 0;
        for (double[] r : recent) s += r[0] + r[1];
        return s / recent.size();
    }

    static double r1(double d) { return Math.round(d * 100) / 100.0; }

    /** For "check": {key, line, fix} findings. */
    public List<String[]> findings() {
        List<String[]> f = new ArrayList<>();
        double avg = recentAvg();
        if (avg > OVERRUN_MS) f.add(new String[]{"threatslow", "the threat test takes " + r1(avg) + " ms a second (over " + (int) OVERRUN_MS + ")", "debug threats"});
        if (mobsNear > 0 && copies > 0 && System.currentTimeMillis() - lastAnswerMs > 10_000)
            f.add(new String[]{"threatworker", "the threat search hasn't answered for 10 s with mobs near (the fight code uses straight distance)", "debug threats; the game log has [entropybot] threat lines"});
        return f;
    }

    /** A small status object for state.json's defence block. */
    public com.google.gson.JsonObject status() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("grid", gridOk() ? "ok" : "fallback");
        o.addProperty("ms", r1(recentAvg()));
        o.addProperty("errors", errors);
        return o;
    }

    private void error(String where, Throwable t) {
        errors++;
        lastError = where + ": " + t;
        long now = System.currentTimeMillis();
        if (errors <= 5 || now - lastErrorLog >= 60_000) {
            lastErrorLog = now;
            LOG.error("[entropybot] threat: {} error #{}: {}", where, errors, t.toString(), errors <= 5 ? t : null);
        }
    }
}
