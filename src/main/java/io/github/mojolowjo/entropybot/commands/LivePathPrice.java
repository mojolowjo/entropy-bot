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

    /**
     * Answers landed since the start; 0.39.z58: plus the fork's queue landings (searches its queue started for asks it had
     * answered "queued": the chain re-prices on them as on its own).
     */
    @Override public long landed() { return landed + queueLandings(); }

    /** 0.39.z56 item 3 (load probe): searches started since the game started. */
    private volatile long started;

    public long started() { return started; }

    /** 0.39.z56 item 3 (load probe): searches in flight now (game thread). */
    public int inFlight() { return inFlight.size() + pending.size(); }

    /** The last landed answer's words ("walk to 3 12 -40: 812 ticks, complete"), for the re-price line. */
    public String lastLanded() { return lastLanded; }

    @Override
    public Answer price(int x, int y, int z, double dist) { return ask(x, y, z, false); }

    /** 0.39.z76 send-back 2 (run AN: the band walk ended one above a one-high band): priced with the goal of that exact cell. */
    @Override
    public Answer priceInto(int x, int y, int z, double dist) { return ask(x, y, z, true); }

    /**
     * 0.39.z76 send-back 3 (rule 18 amended, rule 22): every ask, then its first-answer clock - a walk still COMPUTING after
     * pathCost.firstAnswerWaitS of asks from the same start (within failures.moved) is answered by Baritone's estimate
     * ({@link PricedAnswers#afterWait}); any other answer, or a move off that start, starts the clock again.
     */
    private Answer ask(int x, int y, int z, boolean into) {
        Answer a = ask0(x, y, z, into);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return a;
        // 0.39.z77 round fix 1: CHUNKS keeps the clock too (run BH); 0.39.z78 ea (run EA 20:19:51-20:20:48): the clock is the
        // walk's, wherever the bot stands - a move no longer starts it again (FirstAnswerClock)
        return firstAsked.answer(key(x, y, z, into), a, mc.level.getGameTime(), io.github.mojolowjo.entropybot.aa.NeedChain.firstAnswerWaitS(),
                () -> estimate(x, y, z));
    }

    /** 0.39.z76 send-back 3 / 0.39.z78 ea: the walks' first-answer clocks. */
    private final FirstAnswerClock firstAsked = new FirstAnswerClock(ASKED_AT_MAX);

    /** structural: a memory bound on the clocks kept (a chain asks a few dozen) */
    private static final int ASKED_AT_MAX = 1024;

    private Answer ask0(int x, int y, int z, boolean into) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) return none("the chain was priced off the game thread: the path search is started on it only");
        if (mc.player == null || mc.level == null) return Answer.none("no player or level");
        IBaritone b = Jobs.baritone();
        if (b == null) return none("Baritone is not loaded");
        BlockPos pos = new BlockPos(x, y, z);
        // 0.39.z46 (S57): with the priced path, "chunks not loaded" is Baritone's own world view's answer (the fork's)
        if (!PathPrice.pricedPathOn() && !mc.level.isLoaded(pos)) return Answer.chunks("not loaded: the target's chunk is not loaded");
        String key = key(x, y, z, into);
        int[] me = Jobs.here(mc.player);
        window.asked(key);                               // 0.39.z76 J3-mod item 2: asked in this sweep window (kept, not withdrawn)
        if (PathPrice.pricedPathOn()) return pricedAsk(mc, b, posOf(key), key, me);
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
            f = b.getPathCost().estimateInBackground(goalOf(key),
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

    // ---- 0.39.z76 J3-mod item 2: rank rough (the estimate), withdraw what nobody wants (IPricedPath.cancel) ----

    /** Goal keys asked (this window and the one before) / ranked out since the last {@link #sweep} (game thread). */
    private final AskSweep.Window window = new AskSweep.Window();
    /** Asks withdrawn since the game started (the stall probe's count). */
    private volatile long withdrawn;

    public long withdrawn() { return withdrawn; }

    /**
     * Baritone's estimate of the walk from the feet to stand by x y z: the fork's {@code IBaritone.getPathEstimate()} (its goal
     * heuristic, no search) in seconds; NaN with no player or Baritone, or an older fork (said once). Game thread.
     */
    @Override
    public double estimate(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return Double.NaN;
        IBaritone b = Jobs.baritone();
        if (b == null) return Double.NaN;
        try {
            double t = b.getPathEstimate().estimate(goal(new BlockPos(x, y, z)), mc.player.blockPosition());
            return Double.isNaN(t) || Double.isInfinite(t) ? Double.NaN : TickPrice.walkS(t);
        } catch (LinkageError e) {
            none("this Baritone has no IBaritone.getPathEstimate (an older fork? install the entropy fork J3): ways are ranked by the straight metres; " + e);
            return Double.NaN;
        }
    }

    @Override public void rankedOut(int x, int y, int z) { window.rankedOut(x + "," + y + "," + z); }

    /**
     * Once per AA tick (AaRuntime): the pending asks nobody asked in the last two sweep windows (review: two planners on
     * staggered ticks keep their goals, AskSweep.Window) that the chain ranked out, or whose
     * start the bot left by more than failures.moved ({@link AskSweep}), are withdrawn - the fork cancels their search (its
     * slot frees at once) and answers WITHDRAWN, which reads "path not known" (PricedAnswers.notYet). Never the running
     * step's walk. Game thread. Loader notes: the fork's IPricedPath.cancel (J3).
     */
    public void sweep() {
        boolean swept = false;
        try {
            if (!PathPrice.pricedPathOn() || pending.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            IBaritone b = Jobs.baritone();
            if (mc == null || mc.player == null || b == null) return;
            java.util.List<AskSweep.Ask> open = new java.util.ArrayList<>();
            for (var e : pending.entrySet()) if (!e.getValue().future().isDone()) open.add(new AskSweep.Ask(e.getKey(), e.getValue().from()));
            String run = runningTold;                    // z76 review: the running walk by its own key ("in:x,y,z" for a band walk)
            swept = true;                                // review: the window turns in its own sweep (asks of the last two windows kept)
            for (String key : window.sweep(open, Jobs.here(mc.player), io.github.mojolowjo.entropybot.aa.brain.Failures.movedV(), run)) {
                boolean out;
                try {
                    out = b.getPricedPath().cancel(goalOf(key));
                } catch (LinkageError e) {
                    none("this Baritone has no IPricedPath.cancel (an older fork? install the entropy fork J3): asks nobody wants run to their end; " + e);
                    return;
                }
                if (out) {
                    withdrawn++;
                    LOG.debug("[entropybot] path cost: the ask for {} withdrawn (ranked out of the priced ways or its start left; nobody asked it for two sweeps)", key);
                }
            }
        } finally {
            if (!swept) window.next();
        }
    }

    /** 0.39.z57: one search in flight per goal key: its future (the fork's; a joined ask gets the same one) and where it was asked. */
    private record Pending(java.util.concurrent.CompletableFuture<IPathCost.Answer> future, int[] from) {}

    private final Map<String, Pending> pending = new HashMap<>();
    /** 0.39.z57 review 9: per goal key, the lava part read for the memory's landing. */
    private final PricedAnswers.LavaKept lavaKept = new PricedAnswers.LavaKept(LAVA_KEPT);

    /**
     * 0.39.z57 (path memory; owner 2026-10-25: "while doing stuff it should not forget the path that was already planned";
     * run P 23:46:34-38: one walk priced 10 times from one cell - each finished craft changed the bag, the bag hash dropped
     * the kept answer and the chain re-priced): the fork's path memory answers (IPricedPath.price with Limits): a done future
     * is a memory answer (exact, the suffix of a kept path from the feet, 0 at the goal, a kept no-path answer from this
     * cell) or "queued" / "chunks not loaded" - answered now, no landing, no re-price; else the search's future, which lands
     * once (z57 review 2: while it runs, asks of the goal wait on it here without asking the fork; it is never cancelled
     * for a move and its answer is the fork's re-ask from the feet at the landing, rule 18). The mod keeps no answers of its own here: what
     * is kept and when it is dropped (feet off the path, a block on it, what the price used, the world, pathCost.maxKeepS)
     * is the fork's one rule. The "probe reuse:" lines say every drop (debug.walkProbe).
     */
    private Answer pricedAsk(Minecraft mc, IBaritone b, BlockPos pos, String key, int[] me) {
        // 0.39.z57 review 2: a search for this goal is running - the fork would join it (every asker gets a future answered
        // from the feet when it lands, never cancelled for a move); one Pending per goal key lands once and re-prices once
        Pending running = pending.get(key);
        if (running != null && !running.future().isDone()) return Answer.computing(null);
        java.util.concurrent.CompletableFuture<IPathCost.Answer> f;
        try {
            f = b.getPricedPath().price(goalOf(key), limits());
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
            Answer wait = a == null ? null : PricedAnswers.notYet(a.reason, baritone.api.pathing.calc.IPricedPath.QUEUED, baritone.api.pathing.calc.IPricedPath.RESEARCH_WAIT,
                    baritone.api.pathing.calc.IPricedPath.WITHDRAWN, PathPrice.maxPending());
            if (wait != null) return wait;
            return answer(mc, b, a, key, pos);
        }
        pending.put(key, new Pending(f, me));
        started++;                                       // 0.39.z56 item 3: the load probe's count (game thread)
        f.whenComplete((a, err) -> mc.execute(() -> landPriced(mc, b, key, f, me, a, err)));
        return Answer.computing(null);
    }

    /**
     * 0.39.z57: the fork's limits from Tuning: pathCost.maxKeepS, pricedPath.keepTicks (no-path), maxSegments,
     * pathCost.maxPending.
     */
    private static baritone.api.pathing.calc.IPricedPath.Limits limits() {
        double tps = io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS;
        // 0.39.z58 (run Q 02:29:32: all slots held by re-searches): pathCost.reserveForRunning, researchMinS, queueKeepS
        return new baritone.api.pathing.calc.IPricedPath.Limits(
                (int) Math.ceil(PathPrice.maxKeepS() * tps),
                PathPrice.pricedKeepTicks(), PathPrice.pricedMaxSegments(), PathPrice.maxPending(),
                PathPrice.reserveForRunning(), (int) Math.ceil(PathPrice.researchMinS() * tps), (int) Math.ceil(PathPrice.queueKeepS() * tps));
    }

    /** 0.39.z58: the running step's walk target last told to the fork (null: none), compared so the fork is told on a change. */
    private String runningTold;
    private boolean runningSet;
    /** 0.39.z58 review 3: the fork instance it was told to (identity). */
    private Object runningFork;

    /**
     * 0.39.z58 item 1 (D3 pricing queue; run Q 02:29:32): the walk the running step does or does next (null: none) - the
     * fork's queue gives its price the first slot (and the pathCost.reserveForRunning ones). Told on a change only; game thread.
     * The scorer still picks the way (rule 1): this only orders searches.
     */
    public void running(BlockPos at) { running(at, false); }

    /**
     * 0.39.z76 review (the band walk is keyed "in:x,y,z" and walked with GoalBlock): {@code into} - the running walk's feet end
     * IN the cell; the fork is told that goal ({@link #goalOf}) and the sweep spares that key, as the ask itself is keyed.
     */
    public void running(BlockPos at, boolean into) {
        if (!PathPrice.pricedPathOn()) return;
        IBaritone b = Jobs.baritone();
        if (b == null) return;
        // review 3: told again when the fork instance changed (a world change or reconnect gives a new Baritone)
        Object fork = b.getPricedPath();
        String k = at == null ? null : key(at.getX(), at.getY(), at.getZ(), into);
        if (runningSet && fork == runningFork && java.util.Objects.equals(k, runningTold)) return;
        try {
            b.getPricedPath().runningGoal(k == null ? null : goalOf(k));
        } catch (LinkageError e) {
            none("this Baritone has no IPricedPath.runningGoal (an older fork? install the entropy fork z58): " + e);
            return;
        }
        runningTold = k;
        runningFork = fork;
        runningSet = true;
        LOG.debug("[entropybot] path queue: the running step's walk is {}", k == null ? "none" : k);
    }

    /** 0.39.z58 item 1: a new order (aa want) - the fork's "not answered yet in this order" class starts again. Game thread. */
    public void orderStarted() {
        if (!PathPrice.pricedPathOn()) return;
        IBaritone b = Jobs.baritone();
        if (b == null) return;
        try {
            b.getPricedPath().orderStarted();
        } catch (LinkageError e) {
            none("this Baritone has no IPricedPath.orderStarted (an older fork? install the entropy fork z58): " + e);
        }
    }

    /**
     * 0.39.z58 (Opus Design ruling): first answers (walks not answered yet in this order) running or queued in the fork; -1
     * when not known (the priced path off, no Baritone, an older fork: said once). Game thread.
     */
    public int firstAnswersPending() {
        if (!PathPrice.pricedPathOn()) return -1;
        IBaritone b = Jobs.baritone();
        if (b == null) return -1;
        try {
            return b.getPricedPath().firstAnswersPending();
        } catch (LinkageError e) {
            none("this Baritone has no IPricedPath.firstAnswersPending (an older fork? install the entropy fork z58): " + e);
            return -1;
        }
    }

    /** 0.39.z58: landings of searches the fork's queue started itself (no future of ours lands for them); 0 from an older fork. */
    private static long queueLandings() {
        if (!PathPrice.pricedPathOn()) return 0;
        IBaritone b = Jobs.baritone();
        if (b == null) return 0;
        try {
            return b.getPricedPath().queueLandings();
        } catch (LinkageError e) {
            return 0;                                    // an older fork: said once by running()/orderStarted()
        }
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

    /**
     * 0.39.z57: one search landed (game thread): the chain is told (landed++). Review 2 item 1 (rule 18): {@code a} is the
     * fork's answer from the feet at the landing (the memory asked again then: exact / suffix of the path just kept, or the
     * answer of the search started again from the feet), never the search's answer from its own start; the lava part is
     * that answer's (tied to the landing) and the probe line starts at the answer's first cell (the feet's cell on the path).
     */
    private void landPriced(Minecraft mc, IBaritone b, String key, java.util.concurrent.CompletableFuture<IPathCost.Answer> f, int[] asked,
                            IPathCost.Answer a, Throwable err) {
        Pending p = pending.get(key);
        if (p != null && p.future() == f) pending.remove(key);
        drops(b);
        // 0.39.z76 J3-mod item 2: a withdrawn ask is no answer - nothing landed, nothing to re-price on (rule 15: not known)
        if (err == null && a != null && baritone.api.pathing.calc.IPricedPath.WITHDRAWN.equals(a.reason)) {
            LOG.debug("[entropybot] path cost: walk to {}: {}", key.replace(',', ' '), a.reason);
            return;
        }
        int[] from = mc.player == null ? asked : Jobs.here(mc.player);
        BlockPos goal = posOf(key);
        Answer out;
        if (err != null) {
            LOG.warn("[entropybot] path cost to {}: the background search threw", key, err);
            out = Answer.none("the search threw: " + err);
        } else {
            Answer wait = a == null ? null : PricedAnswers.notYet(a.reason, baritone.api.pathing.calc.IPricedPath.QUEUED, baritone.api.pathing.calc.IPricedPath.RESEARCH_WAIT,
                    baritone.api.pathing.calc.IPricedPath.WITHDRAWN, PathPrice.maxPending());
            out = wait != null ? wait : answer(mc, b, a, key, goal);
        }
        if (WalkProbe.on() && a != null) {
            int[] on = a.path.isEmpty() ? from : new int[]{a.path.get(0).getX(), a.path.get(0).getY(), a.path.get(0).getZ()};
            if (!a.noPath) WalkProbe.price(goal, on, a);
            else if (IPathCost.Answer.UNREACHABLE.equals(a.reason) || baritone.api.pathing.calc.IPricedPath.NO_PROGRESS.equals(a.reason)) WalkProbe.unreachable(goal, from, a.reason);
        }
        lastLanded = "walk to " + key.replace(',', ' ') + ": " + (out.walk() != null ? Math.round(out.walk().seconds()) + " s, complete" : out.kind() + " (" + out.reason() + ")");
        LOG.debug("[entropybot] path cost: {}", lastLanded);
        landed++;
    }

    /**
     * 0.39.z57: Baritone's answer as the chain's; review 9: the lava part is read once per landing of the fork's memory
     * (IPricedPath.landingOf) and summed for that landing's tails - a re-search reads it again.
     */
    private Answer answer(Minecraft mc, IBaritone b, IPathCost.Answer a, String key, BlockPos pos) {
        if (a == null || a.noPath || !a.complete) {
            // J6b (job-vein a2d4e1c8): the priced search's own stop decides "no path" (exhausted) or "not known" (budget)
            baritone.api.pathing.calc.IPricedPath.SearchCalc calc = null;
            try {
                calc = b.getPricedPath().lastCalc(goalOf(key));
            } catch (LinkageError e) {
                calc = null;                             // an older fork: the stop is not known (PricedAnswers.NO_STOP_KEPT)
            }
            return answer(mc, a, calc);
        }
        java.util.List<int[]> cells = new java.util.ArrayList<>(a.path.size());
        for (BlockPos q : a.path) cells.add(new int[]{q.getX(), q.getY(), q.getZ()});
        long landing;
        try {
            landing = b.getPricedPath().landingOf(goalOf(key));
        } catch (LinkageError e) {
            landing = -1;                                // an older fork: read every time (said once by pricedAsk's install line)
        }
        double lava = lavaKept.lava(key, landing, PricedAnswers.cells(cells), () -> lavaCells(mc, cells));
        var dug = pickBreaks(mc, a.path);
        return Answer.priced(new Walk(TickPrice.walkS(a.ticks), TickPrice.walkS(lava), count(dug), dug));
    }

    /** structural: goal keys whose lava parts are kept (a chain asks a few dozen; past this they are read again) */
    private static final int LAVA_KEPT = 256;

    /** The goal a walk is priced with, and walked with when the price's path is reused (the fork matches goals by equals). */
    public static GoalGetToBlock goal(BlockPos pos) { return new GoalGetToBlock(pos); }

    /**
     * 0.39.z76 send-back 2 (run AN 23:16:18 "to y 56..56: Baritone's goal ended at y 57"; 13x in AK, 32x in AL): the band walk's
     * goal - the feet IN this cell (GoalBlock). The stand-by goal ({@link #goal}) is reached from the cell above and two below,
     * so a one-high band's walk stopped one block over it. DigDownGoal / ClimbGoal steer it as any priced goal.
     */
    public static baritone.api.pathing.goals.GoalBlock into(BlockPos pos) { return new baritone.api.pathing.goals.GoalBlock(pos); }

    /** 0.39.z76 send-back 2: the ask's key: "x,y,z" (stand by), "in:x,y,z" (feet in the cell). */
    static String key(int x, int y, int z, boolean into) { return AskSweep.key(x, y, z, into); }

    private static final String INTO = AskSweep.INTO;

    static BlockPos posOf(String key) {
        String[] q = (key.startsWith(INTO) ? key.substring(INTO.length()) : key).split(",");
        return new BlockPos(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]));
    }

    static baritone.api.pathing.goals.Goal goalOf(String key) { return key.startsWith(INTO) ? into(posOf(key)) : goal(posOf(key)); }

    /**
     * 0.39.z46: walk to stand by the block at pos: the fork's priced path walks the path its price kept (one search) when
     * the bot still stands at its start; else Baritone searches fresh. pricedPath.on 0: Baritone's CustomGoalProcess as z41.
     * 0.39.z57: from any cell of the kept path the fork walks its suffix (and keeps the path known along the way).
     * True: the kept path is walked.
     */
    public static boolean walk(IBaritone b, BlockPos pos) { return walk(b, pos, goal(pos)); }

    /** 0.39.z76 send-back 2: the band walk - the feet end IN the cell ({@link #into}), on its priced path. */
    public static boolean walkInto(IBaritone b, BlockPos pos) { return walk(b, pos, into(pos)); }

    private static boolean walk(IBaritone b, BlockPos pos, baritone.api.pathing.goals.Goal g) {
        if (!PathPrice.pricedPathOn()) {
            b.getCustomGoalProcess().setGoalAndPath(g);
            WalkProbe.walked(false);                         // 0.39.z54 B
            return false;
        }
        boolean kept = b.getPricedPath().walk(g);
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
        } else out = answer(mc, a, null);                    // the z41 route keeps no calc: a no-path is not known
        // 0.39.z54 B (run M: the walk vs the price; 38 "no progress" answers): the probe's lines, nothing built when off
        if (WalkProbe.on() && a != null) {
            BlockPos goal = posOf(key);
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
    private static Answer answer(Minecraft mc, IPathCost.Answer a, baritone.api.pathing.calc.IPricedPath.SearchCalc calc) {
        if (a == null) return Answer.none("the search gave no answer");
        if (!a.noPath && a.complete) {
            var dug = pickBreaks(mc, a.path);
            return Answer.priced(new Walk(TickPrice.walkS(a.ticks), TickPrice.walkS(lavaTicks(mc, a)), count(dug), dug));
        }
        if (!a.noPath) return Answer.none("budget: the search ran out before the goal (" + a + ")");
        // 0.39.z77 noprogress (run DH 04:39:57): only the search's end is a no-way; J6b (a2d4e1c8): from the fork's flags, never
        // the reason's words (PricedAnswers.noPath); no calc kept: not known
        return PricedAnswers.noPath(a.reason, calc == null ? null : calc.exhausted(), calc == null ? null : calc.budget(), IPathCost.Answer.CHUNKS);
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

    /**
     * 0.39.z62 item 2 (rule 14, rough): the blocks the walk breaks with a pickaxe - per path cell (the feet's cells) the feet
     * and head block, counted when the game tags it mineable with a pickaxe (BlockTags.MINEABLE_WITH_PICKAXE); the level now,
     * when the answer lands. 0: no level, no cells, or an unloaded cell (not counted). 0.39.z69 item 1 / review (d): by
     * block kind (block id -> count), what the chain prices each kind's breaks by hand and with a pickaxe from (its
     * hardness and harvest tier from the item data). Loader notes: vanilla Level#getBlockState, BlockState#is(TagKey),
     * BuiltInRegistries.BLOCK#getKey.
     */
    private static java.util.Map<String, Integer> pickBreaks(Minecraft mc, java.util.List<? extends BlockPos> path) {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        if (mc.level == null || path == null || path.isEmpty()) return out;
        var lv = mc.level;
        for (int i = 1; i < path.size(); i++) {          // the first cell is where the walk starts (nothing broken there)
            BlockPos p = path.get(i);
            for (int dy = 0; dy <= 1; dy++) {            // structural: the feet and the head cell
                BlockPos q = p.above(dy);
                if (!lv.isLoaded(q)) continue;
                var st = lv.getBlockState(q);
                if (!st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) continue;
                out.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString(), 1, Integer::sum);
            }
        }
        return out;
    }

    /** 0.39.z69: the count of a {@link #pickBreaks} map. */
    private static int count(java.util.Map<String, Integer> dug) {
        int n = 0;
        for (int v : dug.values()) n += v;
        return n;
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
