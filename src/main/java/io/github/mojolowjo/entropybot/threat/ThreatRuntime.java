package io.github.mojolowjo.entropybot.threat;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * B2 threat test, the game side (docs/BRAIN_PLAN.md 5.2), event-driven since 0.23.2: the block array around the bot is
 * kept ({@link ReachCache}); block changes patch single cells (the mod's ClientLevel hook through
 * {@code FlightRecorder.watchListener}, see EntropyBot), a box shift copies only the new slice, and a full copy runs
 * every 5 s and on a chunk load/unload inside the box. The search runs on the game thread, coalesced: at most once a
 * tick, only when the array is dirty or the bot changed cell, and only while hostiles are near. A mob that appears gets
 * its reach the same tick (a read when the array is clean). Decisions ({@link ThreatRules#decide}) are re-made each
 * second (the closing measure), on every new search and for every new mob.
 * Loader notes: vanilla getters only (Level.getBlockState, BlockState.getCollisionShape, Mob.isAggressive,
 * Entity.getYHeadRot, LivingEntity.hasLineOfSight, Level.getMaxLocalRawBrightness); chunk events NeoForge ChunkEvent
 * (Fabric: ClientChunkEvents); no new mixin, no Baritone.
 * Errors: counted, the first 5 logged in full then one a minute; while broken the fight code uses the old test.
 */
public final class ThreatRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    public static final ThreatRuntime INSTANCE = new ThreatRuntime();

    public static final int R = 16, DOWN = 8, UP = 8;
    static final int PERIOD = 20;
    static final int FULL_TICKS = 100;
    static final long FRESH_MS = 12_000;
    static final double OVERRUN_MS = 5;
    static final int RING = 32;
    static final int WINDOW = 100;          // counters' rate window, ticks

    private final ReachCache cache = new ReachCache(2 * R + 1, DOWN + UP + 1, 2 * R + 1);
    private Level lastLevel;
    private boolean needFull = true;
    private long lastFullTick = -100000, lastPeriod = -100000;
    private volatile long lastFullMs;
    private volatile Map<Integer, ThreatRules.Decision> latest = Map.of();
    private final Map<Integer, Double> prevReach = new HashMap<>();
    private final Map<Integer, Long> firstSeen = new HashMap<>();
    private final ArrayDeque<String> ring = new ArrayDeque<>();
    private volatile int[] litSpot;
    private long nowTick;
    // counters
    private long newMobReads, newMobSearches, chunkFulls, errors, counted, noted;
    private int mobsNear;
    private long winStart = -1, wPatches, wSearches, wFulls, wSlices;
    private volatile double patchRate, searchRate, fullRate, sliceRate;
    private long lastErrorLog;
    private String lastError;
    private volatile String verdictLine;
    private long verdictAt;
    private boolean broken;

    private ThreatRuntime() {}

    private static final class Src implements ReachCache.Source {
        final Level level;
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        Src(Level level) { this.level = level; }

        @Override
        public byte code(int x, int y, int z) {
            m.set(x, y, z);
            return ThreatRuntime.code(level, m, level.getBlockState(m));
        }

        @Override
        public byte light(int x, int y, int z) {
            m.set(x, y, z);
            return (byte) level.getMaxLocalRawBrightness(m);
        }
    }

    /** Every client tick from the reflexes (game thread). hurt: the reflexes' "just hit" window. Never throws. */
    public void tick(Minecraft mc, LocalPlayer p, long tick, boolean hurt) {
        try {
            nowTick = tick;
            Level level = mc.level;
            if (level != lastLevel) {
                lastLevel = level;
                needFull = true;
                latest = Map.of();
                prevReach.clear();
                firstSeen.clear();
            }
            Src src = new Src(level);
            int ox = p.getBlockX() - R, oy = p.getBlockY() - DOWN, oz = p.getBlockZ() - R;
            if (needFull || tick - lastFullTick >= FULL_TICKS) {
                cache.full(src, ox, oy, oz);
                needFull = false;
                lastFullTick = tick;
                lastFullMs = System.currentTimeMillis();
            } else cache.moveTo(src, ox, oy, oz);
            rates(tick);
            // the hostiles near, and which are new
            Hostility h = Hostility.get();
            List<Entity> near = new ArrayList<>();
            List<Entity> fresh = new ArrayList<>();
            boolean anySpider = false;
            Map<Integer, ThreatRules.Decision> old = latest;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == p || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
                double d = p.distanceTo(e);
                if (d > ThreatRules.SAMPLE_RADIUS || !h.counts(e, hurt)) continue;
                near.add(e);
                if (!old.containsKey(e.getId())) fresh.add(e);
                firstSeen.putIfAbsent(e.getId(), tick);
                anySpider |= ThreatRules.moveOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()) == ThreatRules.Move.SPIDER;
            }
            mobsNear = near.size();
            boolean period = tick - lastPeriod >= PERIOD;
            if (near.isEmpty()) {
                if (period) {
                    lastPeriod = tick;
                    if (!old.isEmpty()) latest = Map.of();
                    prevReach.clear();
                    firstSeen.clear();
                }
                return;
            }
            boolean searched = cache.ensure(p.getBlockX(), p.getBlockY(), p.getBlockZ(), anySpider);
            if (!fresh.isEmpty()) {
                if (searched) newMobSearches++; else newMobReads += fresh.size();
            }
            if (!period && !searched && fresh.isEmpty()) return;
            // decide: all of them on a period or a new search, else only the new ones
            List<Entity> todo = period || searched ? near : fresh;
            Map<Integer, ThreatRules.Decision> out = new HashMap<>(period || searched ? Map.of() : old);
            if (!period && !searched) out.putAll(old);
            for (Entity e : todo) {
                ThreatRules.MobSample m = sampleOf(p, e, p.distanceTo(e), h.hitMe(e));
                boolean sp = ThreatRules.moveOf(m.kind()) == ThreatRules.Move.SPIDER;
                int pd = cache.dist(e.getBlockX(), e.getBlockY(), e.getBlockZ(), sp);
                Double pr = prevReach.get(e.getId());
                ThreatRules.MobSample s = new ThreatRules.MobSample(m.id(), m.kind(), m.straight(), m.aggressive(), m.yawOff(), m.seen(), m.hitMe(),
                        pd, pr == null ? Double.NaN : pr);
                ThreatRules.Decision d = ThreatRules.decide(s);
                out.put(e.getId(), d);
                if (d.counts()) counted++; else noted++;
                ThreatRules.Decision was = old.get(d.id());
                if (was == null || was.counts() != d.counts() || !was.rule().equals(d.rule())) remember(d);
            }
            if (period) {
                lastPeriod = tick;
                prevReach.clear();
                for (ThreatRules.Decision d : out.values()) if (!Double.isNaN(d.reach())) prevReach.put(d.id(), d.reach());
                firstSeen.keySet().retainAll(out.keySet());
                ReachGrid g = cache.grid();
                int[] w = cache.walk();
                litSpot = g == null || w == null ? null : g.nearestLit(w, FightOrFlee.LIT, FightOrFlee.LIT_MAX_DIST);
            }
            latest = out;
            broken = false;
        } catch (Throwable t) {
            broken = true;
            error("tick", t);
        }
    }

    /** Hostility through one small seam, so this class needs only these two calls. */
    private static final class Hostility {
        static final Hostility H = new Hostility();

        static Hostility get() { return H; }

        boolean counts(Entity e, boolean hurt) { return io.github.mojolowjo.entropybot.engine.Hostility.INSTANCE.kind(e, hurt).counts(); }

        boolean hitMe(Entity e) { return io.github.mojolowjo.entropybot.engine.Hostility.INSTANCE.hitMe(e); }
    }

    private void rates(long tick) {
        if (winStart < 0) {
            winStart = tick;
            wPatches = cache.patches;
            wSearches = cache.searches;
            wFulls = cache.fulls;
            wSlices = cache.slices;
            return;
        }
        if (tick - winStart < WINDOW) return;
        double s = (tick - winStart) / 20.0;
        patchRate = (cache.patches - wPatches) / s;
        searchRate = (cache.searches - wSearches) / s;
        fullRate = (cache.fulls - wFulls) / s;
        sliceRate = (cache.slices - wSlices) / s;
        winStart = tick;
        wPatches = cache.patches;
        wSearches = cache.searches;
        wFulls = cache.fulls;
        wSlices = cache.slices;
    }

    /** A block changed in the client level (game thread, the ClientLevel hook): patch its cell. Never throws. */
    public void blockChanged(Level level, BlockPos pos, BlockState to) {
        try {
            if (level != lastLevel || !cache.inBox(pos.getX(), pos.getY(), pos.getZ())) return;
            cache.patch(pos.getX(), pos.getY(), pos.getZ(), code(level, pos, to), new Src(level));
        } catch (Throwable t) {
            error("patch", t);
        }
    }

    /** A chunk loaded or unloaded: inside the box, the next tick reads it all again. */
    public void chunkChanged(int cx, int cz) {
        try {
            if (cache.overlapsChunk(cx, cz)) {
                needFull = true;
                chunkFulls++;
            }
        } catch (Throwable t) {
            error("chunk", t);
        }
    }

    private synchronized void remember(ThreatRules.Decision d) {
        ring.addLast(java.time.LocalTime.now().withNano(0) + " #" + d.id() + " " + d.line());
        while (ring.size() > RING) ring.removeFirst();
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

    /** The grid is fresh (a full copy within 12 s) and the last tick ran clean. */
    public boolean gridOk() {
        return !broken && cache.grid() != null && System.currentTimeMillis() - lastFullMs <= FRESH_MS;
    }

    public ThreatRules.Decision decision(int entityId) { return latest.get(entityId); }

    /** The filter in front of the fight code (see {@link ThreatRules#filter}). */
    public boolean counts(Entity e, double straight, boolean hitMe) {
        return ThreatRules.filter(latest.get(e.getId()), gridOk(), straight, hitMe, e instanceof Creeper);
    }

    /** The nearest lit standing spot found by the last search {x, y, z, moves}, or null. */
    public int[] litSpot() { return gridOk() ? litSpot : null; }

    /** The tick a mob was first seen near (for the reflex timing log), or -1. */
    public long firstSeen(int id) {
        Long t = firstSeen.get(id);
        return t == null ? -1 : t;
    }

    /** 0.23.6: the grid snapshot when fresh, else null (the fire reflex's water search, the run guard). */
    public ReachGrid grid() { return gridOk() ? cache.grid() : null; }

    /**
     * 0.23.6 run guard for a raw run along (dx, dz): go, turn or stop ({@link RunGuard}). Fire comes from the level (no
     * collision box, so not in the grid). Without a fresh grid it says go (the old behaviour). Never throws.
     */
    public RunGuard.Verdict runGuard(LocalPlayer p, double dx, double dz) {
        try {
            Level level = p.level();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            RunGuard.Fire fire = (x, y, z) -> level.getBlockState(m.set(x, y, z)).is(net.minecraft.tags.BlockTags.FIRE);
            return RunGuard.decide(grid(), p.getX(), p.getBlockY(), p.getZ(), dx, dz, fire);
        } catch (RuntimeException e) {
            error("run guard", e);
            return new RunGuard.Verdict("go", dx, dz, "error");
        }
    }

    /** 0.23.2: the escape heading from a creeper, from the grid ({dirX, dirZ, run}; run -1 = no grid, straight away). */
    public double[] escape(LocalPlayer p, Entity from) {
        double ax = p.getX() - from.getX(), az = p.getZ() - from.getZ();
        try {
            return EscapeDir.choose(gridOk() ? cache.grid() : null, p.getBlockX(), p.getBlockY(), p.getBlockZ(), ax, az);
        } catch (RuntimeException e) {
            error("escape", e);
            return EscapeDir.choose(null, 0, 0, 0, ax, az);
        }
    }

    /** 0.24.4 dead-end rule: the check for the bot against this mob on the fresh grid, else null (no rule). Never throws. */
    public DeadEnd.Check deadEnd(LocalPlayer p, Entity mob) {
        try {
            ReachGrid g = grid();
            if (g == null || mob == null) return null;
            return DeadEnd.check(g, p.getBlockX(), p.getBlockY(), p.getBlockZ(), mob.getBlockX(), mob.getBlockY(), mob.getBlockZ());
        } catch (RuntimeException e) {
            error("dead end", e);
            return null;
        }
    }

    /** 0.25.1 cover rule: a cell out of this mob's sight on the fresh grid, else null. Never throws. */
    public Cover.Spot cover(LocalPlayer p, Entity mob) {
        try {
            ReachGrid g = grid();
            if (g == null || mob == null) return null;
            return Cover.find(g, p.getBlockX(), p.getBlockY(), p.getBlockZ(), mob.getX(), mob.getEyeY(), mob.getZ());
        } catch (RuntimeException e) {
            error("cover", e);
            return null;
        }
    }

    /** 0.25.1 high-ground rule: a cell above these mobs with one way up on the fresh grid, else null. Never throws. */
    public HighGround.Spot highGround(LocalPlayer p, List<int[]> mobs) {
        try {
            ReachGrid g = grid();
            if (g == null) return null;
            return HighGround.find(g, p.getBlockX(), p.getBlockY(), p.getBlockZ(), mobs);
        } catch (RuntimeException e) {
            error("high ground", e);
            return null;
        }
    }

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

    /** "debug threats x y z": the current array and search at one block (a mob standing there). */
    public String probe(int x, int y, int z) {
        ReachGrid g = cache.grid();
        int[] w = cache.walk();
        if (g == null || w == null) return "no search yet";
        int d = g.distAt(w, x, y, z);
        int lx = x - g.ox, ly = y - g.oy, lz = z - g.oz;
        if (!g.in(lx, ly, lz)) return x + " " + y + " " + z + ": outside the grid (origin " + g.ox + " " + g.oy + " " + g.oz + ")";
        StringBuilder b = new StringBuilder(x + " " + y + " " + z + ": walk " + (d < 0 ? "no path" : d + " moves")
                + (cache.dirty() ? " (array changed since the search)" : "") + " | column");
        for (int yy = Math.max(0, ly - 2); yy <= Math.min(g.sy - 1, ly + 2); yy++)
            b.append(" ").append(y + yy - ly).append("=").append("ASTLWl".charAt(g.codes[g.idx(lx, yy, lz)])).append(g.walkable(lx, yy, lz) ? "*" : "");
        return b.append(" (A air, S solid, T fence/wall, L lava, W water, l low; * = a mob stands there)").toString();
    }

    /** "debug threats": the counters. */
    public String debug() {
        return "threat test: " + (gridOk() ? "grid ok" : "fallback") + ", grid " + (2 * R + 1) + "x" + (DOWN + UP + 1) + "x" + (2 * R + 1)
                + " | per second (last 5 s): patches " + r1(patchRate) + ", searches " + r1(searchRate) + ", slice copies " + r1(sliceRate)
                + ", full refreshes " + r1(fullRate)
                + " | last search " + r1(cache.lastSearchMs) + " ms | totals: patches " + cache.patches + ", searches " + cache.searches
                + ", full refreshes " + cache.fulls + " (" + chunkFulls + " for chunk loads), slices " + cache.slices + " (" + cache.sliceCells + " cells)"
                + " | new mobs: " + newMobReads + " answered by a read, " + newMobSearches + " bursts needed a search"
                + " | mobs near " + mobsNear + " | counted " + counted + ", noted " + noted + " | errors " + errors
                + (lastError != null ? " (last: " + lastError + ")" : "")
                + " | last full copy " + (lastFullMs == 0 ? "never" : (System.currentTimeMillis() - lastFullMs) / 1000 + " s ago");
    }

    static double r1(double d) { return Math.round(d * 100) / 100.0; }

    /** For "check": {key, line, fix} findings. */
    public List<String[]> findings() {
        List<String[]> f = new ArrayList<>();
        if (cache.lastSearchMs > OVERRUN_MS) f.add(new String[]{"threatslow", "the threat search took " + r1(cache.lastSearchMs) + " ms (over " + (int) OVERRUN_MS + ")", "debug threats"});
        if (mobsNear > 0 && cache.fulls > 0 && System.currentTimeMillis() - lastFullMs > 15_000)
            f.add(new String[]{"threatworker", "the threat grid hasn't refreshed for 15 s with mobs near (the fight code uses straight distance)", "debug threats; the game log has [entropybot] threat lines"});
        return f;
    }

    /** A small status object for state.json's defence block. */
    public com.google.gson.JsonObject status() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("grid", gridOk() ? "ok" : "fallback");
        o.addProperty("ms", r1(cache.lastSearchMs));
        o.addProperty("searchesPerS", r1(searchRate));
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
