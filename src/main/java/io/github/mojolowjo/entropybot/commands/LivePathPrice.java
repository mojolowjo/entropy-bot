package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPathCost;
import baritone.api.pathing.goals.GoalGetToBlock;
import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.TickPrice;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * path-cost: the chain's walk price over the fork's {@code IPathCost}. 0.39.z41 (owner 2026-10-23: "math the actual path
 * it will take ... Use Baritone's numbers"): the FULL A* runs in the background ({@link IPathCost#estimateInBackground}:
 * built on the game thread, searched on Baritone's executor with pathCost.fullMaxMs / pathCost.maxNodes / pathCost.budgetS);
 * its answer is posted back to the game thread ({@link Minecraft#execute}) and cached per target. An ask (game thread):
 * a cached answer -> it (a complete path is the price; noPath is Baritone's reason); the target's own chunk not loaded ->
 * "not loaded" (rule 15, no search); one search in flight for the target, or pathCost.maxPending in flight -> "computing";
 * else the search starts -> "computing". The partial "about" answer is never a price (said "budget"). A cached answer is
 * dropped when the bot stands more than pathCost.reuseBlocks from where it was asked, the bag changed (its contents'
 * hash: Baritone's throwaway blocks and tools change its costs) or the level, or it is older than pathCost.keepS (z57: that
 * cache is the z41 route's only, pricedPath.on 0; the priced path asks the fork's path memory every time: {@link #pricedAsk}). Rule 2: Baritone
 * prices the walk, nothing here re-computes a path. Loader notes: Minecraft#isSameThread / #execute (the client thread);
 * Level#isLoaded; the fork's IPathCost.estimateInBackground (baritone-unoptimized-neoforge-1.11.3-entropy, z41).
 */
public final class LivePathPrice implements PathPrice {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");
    public static final LivePathPrice INSTANCE = new LivePathPrice();
    private final Set<String> said = ConcurrentHashMap.newKeySet();

    /** One landed answer and what it was asked from. Game thread only (the maps too). */
    private record Kept(Answer answer, int[] from, int bag, long atMs) {}

    private final Map<String, Kept> kept = new HashMap<>();
    private final Map<String, int[]> inFlight = new HashMap<>();
    private volatile long landed;
    private volatile String lastLanded = "";

    private LivePathPrice() {}

    @Override public boolean pending() { return !inFlight.isEmpty() || !pending.isEmpty(); }

    @Override public long landed() { return landed; }

    /** 0.39.z56 item 3 (load probe): searches started since the game started. */
    private volatile long started;

    public long started() { return started; }

    /** 0.39.z56 item 3 (load probe): searches in flight now (game thread). */
    public int inFlight() { return inFlight.size() + pending.size(); }

    /** The last landed answer's words ("walk to 3 12 -40: 812 ticks, complete"), for the re-price line. */
    public String lastLanded() { return lastLanded; }

    @Override
    public Answer price(int x, int y, int z, double dist) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) return none("the chain was priced off the game thread: the path search is started on it only");
        if (mc.player == null || mc.level == null) return Answer.none("no player or level");
        IBaritone b = Jobs.baritone();
        if (b == null) return none("Baritone is not loaded");
        BlockPos pos = new BlockPos(x, y, z);
        // 0.39.z46 (S57): with the priced path, "chunks not loaded" is Baritone's own world view's answer (the fork's)
        if (!PathPrice.pricedPathOn() && !mc.level.isLoaded(pos)) return Answer.chunks("not loaded: the target's chunk is not loaded");
        String key = x + "," + y + "," + z;
        int[] me = Jobs.here(mc.player);
        if (PathPrice.pricedPathOn()) return pricedAsk(mc, b, pos, key, me);
        int bag = 31 * bagHash(mc) + System.identityHashCode(mc.level);     // another level (dimension, world): not the same answer
        long now = System.currentTimeMillis();
        Kept k = kept.get(key);
        if (k != null && fresh(k, me, bag, now)) return k.answer();
        if (k != null) kept.remove(key);
        if (inFlight.containsKey(key)) return Answer.computing(null);
        if (inFlight.size() >= PathPrice.maxPending())
            return Answer.computing("queued: pathCost.maxPending " + PathPrice.maxPending() + " searches in flight");
        java.util.concurrent.CompletableFuture<IPathCost.Answer> f;
        try {
            // pricedPath.on 0: z41's route (one background A* with our budget, the walk searches fresh); on 1: pricedAsk
            f = b.getPathCost().estimateInBackground(goal(pos),
                    (int) Math.ceil(PathPrice.budgetS() * io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS), PathPrice.fullMaxMs(), PathPrice.maxNodes());
        } catch (LinkageError e) {
            return none("this Baritone has no IPricedPath / IPathCost.estimateInBackground (the stock jar or an older fork? install the entropy fork z46): " + e);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] path cost to {}: the search could not start", key, e);
            return Answer.none("the search could not start: " + e);
        }
        inFlight.put(key, me);
        started++;                                       // 0.39.z56 item 3: the load probe's count (game thread)
        f.whenComplete((a, err) -> mc.execute(() -> land(mc, key, me, bag, a, err)));
        return Answer.computing(null);
    }

    /** 0.39.z57: one search in flight per goal key: its future (the fork's; a joined ask gets the same one) and where it was asked. */
    private record Pending(java.util.concurrent.CompletableFuture<IPathCost.Answer> future, int[] from) {}

    private final Map<String, Pending> pending = new HashMap<>();
    /** 0.39.z57: per goal key, the last landed path's cells and lava part (a memory answer is that path's tail). */
    private final Map<String, PricedAnswers.Lava> lavaKept = new HashMap<>();

    /**
     * 0.39.z57 (path memory; owner 2026-10-25: "while doing stuff it should not forget the path that was already planned";
     * run P 23:46:34-38: one walk priced 10 times from one cell - each finished craft changed the bag, the bag hash dropped
     * the kept answer and the chain re-priced): the fork's path memory answers (IPricedPath.price with Limits): a done future
     * is a memory answer (exact, the suffix of a kept path from the feet, 0 at the goal, a kept no-path answer from this
     * cell) or "queued" / "chunks not loaded" - answered now, no landing, no re-price; else the search's future (a second
     * ask of the same goal from the same cell joins it), which lands once. The mod keeps no answers of its own here: what
     * is kept and when it is dropped (feet off the path, a block on it, what the price used, the world, pathCost.maxKeepS)
     * is the fork's one rule. The "probe reuse:" lines say every drop (debug.walkProbe).
     */
    private Answer pricedAsk(Minecraft mc, IBaritone b, BlockPos pos, String key, int[] me) {
        java.util.concurrent.CompletableFuture<IPathCost.Answer> f;
        try {
            f = b.getPricedPath().price(goal(pos), limits());
        } catch (LinkageError e) {
            return none("this Baritone has no IPricedPath.price(Goal, Limits) (an older fork? install the entropy fork z57): " + e);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] path cost to {}: the search could not start", key, e);
            return Answer.none("the search could not start: " + e);
        } finally {
            drops(b);
        }
        if (f.isDone()) {
            IPathCost.Answer a;
            try {
                a = f.join();
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] path cost to {}: the fork's answer failed", key, e);
                return Answer.none("the fork's answer failed: " + e);
            }
            Answer wait = a == null ? null : PricedAnswers.notYet(a.reason, baritone.api.pathing.calc.IPricedPath.QUEUED,
                    baritone.api.pathing.calc.IPricedPath.LEFT_START, PathPrice.maxPending());
            if (wait != null) return wait;
            return answer(mc, a, key);
        }
        Pending p = pending.get(key);
        if (p != null && p.future() == f) return Answer.computing(null);    // joined: the same search, landing once
        pending.put(key, new Pending(f, me));
        started++;                                       // 0.39.z56 item 3: the load probe's count (game thread)
        f.whenComplete((a, err) -> mc.execute(() -> landPriced(mc, b, key, f, me, a, err)));
        return Answer.computing(null);
    }

    /** 0.39.z57: the fork's limits from Tuning: pathCost.maxKeepS, pricedPath.keepTicks (no-path), maxSegments, pathCost.maxPending. */
    private static baritone.api.pathing.calc.IPricedPath.Limits limits() {
        return new baritone.api.pathing.calc.IPricedPath.Limits(
                (int) Math.ceil(PathPrice.maxKeepS() * io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS),
                PathPrice.pricedKeepTicks(), PathPrice.pricedMaxSegments(), PathPrice.maxPending());
    }

    /** 0.39.z57 (rule 7): the fork's drop lines, one "probe reuse:" line each while debug.walkProbe is on (read anyway: bounded). */
    private static void drops(IBaritone b) {
        java.util.List<String> lines;
        try {
            lines = b.getPricedPath().drainDrops();
        } catch (LinkageError e) {
            return;                                      // a fork without the memory: none to say (said once by pricedAsk)
        }
        if (lines.isEmpty() || !WalkProbe.on()) return;
        for (String l : lines) LOG.info("[entropybot] probe reuse: {}", ProbeLines.reuse(l));
    }

    /** 0.39.z57: one search landed (game thread): the chain is told (landed++); a search cancelled for a newer ask says nothing. */
    private void landPriced(Minecraft mc, IBaritone b, String key, java.util.concurrent.CompletableFuture<IPathCost.Answer> f, int[] from,
                            IPathCost.Answer a, Throwable err) {
        Pending p = pending.get(key);
        if (p != null && p.future() == f) pending.remove(key);
        drops(b);
        if (err == null && a != null && baritone.api.pathing.calc.IPricedPath.LEFT_START.equals(a.reason)) return;
        Answer out;
        if (err != null) {
            LOG.warn("[entropybot] path cost to {}: the background search threw", key, err);
            out = Answer.none("the search threw: " + err);
        } else out = answer(mc, a, key);
        if (WalkProbe.on() && a != null) {
            String[] q = key.split(",");
            BlockPos goal = new BlockPos(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]));
            if (!a.noPath) WalkProbe.price(goal, from, a);
            else if (IPathCost.Answer.UNREACHABLE.equals(a.reason) || baritone.api.pathing.calc.IPricedPath.NO_PROGRESS.equals(a.reason)) WalkProbe.unreachable(goal, from, a.reason);
        }
        lastLanded = "walk to " + key.replace(',', ' ') + ": " + (out.walk() != null ? Math.round(out.walk().seconds()) + " s, complete" : out.kind() + " (" + out.reason() + ")");
        LOG.debug("[entropybot] path cost: {}", lastLanded);
        landed++;
    }

    /** 0.39.z57: Baritone's answer as the chain's, the lava part read once per landed path and summed for its tails. */
    private Answer answer(Minecraft mc, IPathCost.Answer a, String key) {
        if (a == null || a.noPath || !a.complete) return answer(mc, a);
        java.util.List<int[]> cells = new java.util.ArrayList<>(a.path.size());
        for (BlockPos q : a.path) cells.add(new int[]{q.getX(), q.getY(), q.getZ()});
        long[] ask = PricedAnswers.cells(cells);
        PricedAnswers.Lava kept = lavaKept.get(key);
        double lava = kept == null ? Double.NaN : kept.suffix(ask);
        if (Double.isNaN(lava)) {
            PricedAnswers.Lava fresh = new PricedAnswers.Lava(ask, lavaCells(mc, cells));
            if (lavaKept.size() > LAVA_KEPT) lavaKept.clear();
            lavaKept.put(key, fresh);
            lava = fresh.total();
        }
        return Answer.priced(new Walk(TickPrice.walkS(a.ticks), TickPrice.walkS(lava)));
    }

    /** structural: goal keys whose lava parts are kept (a chain asks a few dozen; past this they are read again) */
    private static final int LAVA_KEPT = 256;

    /** The goal a walk is priced with, and walked with when the price's path is reused (the fork matches goals by equals). */
    public static GoalGetToBlock goal(BlockPos pos) { return new GoalGetToBlock(pos); }

    /**
     * 0.39.z46: walk to stand by the block at pos: the fork's priced path walks the path its price kept (one search) when
     * the bot still stands at its start; else Baritone searches fresh. pricedPath.on 0: Baritone's CustomGoalProcess as z41.
     * 0.39.z57: from any cell of the kept path the fork walks its suffix (and keeps the path known along the way).
     * True: the kept path is walked.
     */
    public static boolean walk(IBaritone b, BlockPos pos) {
        if (!PathPrice.pricedPathOn()) {
            b.getCustomGoalProcess().setGoalAndPath(goal(pos));
            WalkProbe.walked(false);                         // 0.39.z54 B
            return false;
        }
        boolean kept = b.getPricedPath().walk(goal(pos));
        drops(b);                            // 0.39.z57: a kept path the walk found stale is said (probe reuse)
        WalkProbe.walked(kept);                              // 0.39.z54 B: the walk's kept / fresh answer for the probe line
        // 0.39.z55 item 2 (run N 21:25:33: "no kept path from here" with no reason; the bot had put a crafting table on the
        // kept path's first cell): the fork's reason; its fresh search now uses the price's search goal (DigDownGoal)
        String why;
        try {
            why = b.getPricedPath().walkWhy();
        } catch (LinkageError e) {
            why = "this fork has no IPricedPath.walkWhy (an older fork: install the z55 jar): " + e;
        }
        LOG.info("[entropybot] path: walk to {} {} {}: {}", pos.getX(), pos.getY(), pos.getZ(),
                kept ? "the priced path (one search)" : "no kept path from here (" + why + ") - Baritone searches fresh, with the price's search goal"
                        + " (fork z56: every priced walk, its first and next-segment searches; z55 swapped only with a kept price, run O 22:15:04)");
        return kept;
    }

    /**
     * 0.39.z47 (item 4: ordinary walks as the strip): a chain walk or a station visit to stand by the block at pos through
     * {@link #walk} (the kept path when the bot still stands at its start, else Baritone searches fresh); the goal walked.
     */
    public static baritone.api.pathing.goals.Goal walkGoal(IBaritone b, BlockPos pos) {
        io.github.mojolowjo.entropybot.engine.RunThrowaway.walkPenalty();     // the walk's placement price, as Jobs.walkGoal
        walk(b, pos);
        return goal(pos);
    }

    /** Game thread: one search's answer is kept and the chain is told (landed++). */
    private void land(Minecraft mc, String key, int[] from, int bag, IPathCost.Answer a, Throwable err) {
        inFlight.remove(key);
        Answer out;
        if (err != null) {
            LOG.warn("[entropybot] path cost to {}: the background search threw", key, err);
            out = Answer.none("the search threw: " + err);
        } else out = answer(mc, a);
        // 0.39.z54 B (run M: the walk vs the price; 38 "no progress" answers): the probe's lines, nothing built when off
        if (WalkProbe.on() && a != null) {
            String[] p = key.split(",");
            BlockPos goal = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            if (!a.noPath) WalkProbe.price(goal, from, a);
            else if (IPathCost.Answer.UNREACHABLE.equals(a.reason) || baritone.api.pathing.calc.IPricedPath.NO_PROGRESS.equals(a.reason)) WalkProbe.unreachable(goal, from, a.reason);
        }
        long now = System.currentTimeMillis();
        kept.values().removeIf(old -> now - old.atMs() > PathPrice.keepS() * 1000);   // structural: milliseconds per second
        kept.put(key, new Kept(out, from, bag, now));
        lastLanded = "walk to " + key.replace(',', ' ') + ": " + (out.walk() != null ? Math.round(out.walk().seconds()) + " s, complete" : out.kind() + " (" + out.reason() + ")");
        LOG.debug("[entropybot] path cost: {}", lastLanded);
        landed++;
    }

    /** Baritone's answer as the chain's: complete -> the price; noPath -> its reason, by kind; "about" -> not a price. */
    private static Answer answer(Minecraft mc, IPathCost.Answer a) {
        if (a == null) return Answer.none("the search gave no answer");
        if (!a.noPath && a.complete) return Answer.priced(new Walk(TickPrice.walkS(a.ticks), TickPrice.walkS(lavaTicks(mc, a))));
        if (!a.noPath) return Answer.none("budget: the search ran out before the goal (" + a + ")");
        if (IPathCost.Answer.UNREACHABLE.equals(a.reason)) return Answer.unreachable("no path: the complete search found none");
        if (baritone.api.pathing.calc.IPricedPath.NO_PROGRESS.equals(a.reason))       // 0.39.z46: a segment search went nowhere
            return Answer.unreachable("no path: Baritone's search made no progress (" + a.reason + ")");
        if (IPathCost.Answer.CHUNKS.equals(a.reason)) return Answer.chunks(IPathCost.Answer.CHUNKS);
        return Answer.none(a.reason);
    }

    private static boolean fresh(Kept k, int[] me, int bag, long now) {
        if (k.bag() != bag || now - k.atMs() > PathPrice.keepS() * 1000) return false;  // structural: milliseconds per second
        double dx = me[0] - k.from()[0], dy = me[1] - k.from()[1], dz = me[2] - k.from()[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= PathPrice.reuseBlocks();
    }

    /** The bag's contents as one number (items and counts): a change drops the kept answers. */
    private static int bagHash(Minecraft mc) {
        var inv = mc.player.getInventory();
        int h = 1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (!s.isEmpty()) h = 31 * h + s.getItem().hashCode() * 7 + s.getCount();
        }
        return h;
    }

    /**
     * Lava distance (0.39.z40): the lava price inside the walk (the hook's LavaNear rule over the path's cells, at the Tuning
     * values; rough, rule 14: the level now, when the answer lands). 0: none, or no cells in the answer.
     */
    private static double lavaTicks(Minecraft mc, IPathCost.Answer a) {
        // 0.39.z42 (rule 7): the one null here is the level: the answer lands later than the ask, and the world may have
        // been left meanwhile. The fork's Answer.path is never null (List.of() when it gives no cells: no lava to price).
        if (mc.level == null) return 0;
        if (a.path.isEmpty()) return 0;
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        for (BlockPos p : a.path) cells.add(new int[]{p.getX(), p.getY(), p.getZ()});
        var lv = mc.level;
        io.github.mojolowjo.entropybot.guard.LavaNear.Lava lava = (x, y, z) -> {
            BlockPos q = new BlockPos(x, y, z);
            return lv.isLoaded(q) && lv.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA);
        };
        return io.github.mojolowjo.entropybot.guard.LavaNear.pathTicks(lava, cells, io.github.mojolowjo.entropybot.guard.LavaNear.reach(),
                io.github.mojolowjo.entropybot.guard.LavaNear.penalty());
    }

    /** 0.39.z57: each cell's lava ticks (LavaNear at the Tuning values, the level now); zeros when the level is gone (rule 7: the level is the one null). */
    private static double[] lavaCells(Minecraft mc, java.util.List<int[]> cells) {
        double[] out = new double[cells.size()];
        if (mc.level == null) return out;
        var lv = mc.level;
        io.github.mojolowjo.entropybot.guard.LavaNear.Lava lava = (x, y, z) -> {
            BlockPos q = new BlockPos(x, y, z);
            return lv.isLoaded(q) && lv.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA);
        };
        int reach = io.github.mojolowjo.entropybot.guard.LavaNear.reach();
        double pen = io.github.mojolowjo.entropybot.guard.LavaNear.penalty();
        for (int i = 1; i < out.length; i++) {
            int[] c = cells.get(i);
            out[i] = io.github.mojolowjo.entropybot.guard.LavaNear.ticks(lava, c[0], c[1], c[2], reach, pen);
        }
        return out;
    }

    private Answer none(String why) {
        if (said.add(why)) LOG.warn("[entropybot] path cost: {}; walks have no price (path not known)", why);
        return Answer.none(why);
    }
}
