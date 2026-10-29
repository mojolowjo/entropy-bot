package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.Deep;
import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.Playbook;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 0.39.z78 D6 (design "the worker protocol"): the trip's legs off the render thread, the client tick's side
 * (BatchWay.Planner, installed as BatchWay.PLANNER_FACT). Per trip key: a result of the worker for the request asked now
 * (the same playbook, the same kept fuels, the latest version) is CHECKED - every fact its resolve read is asked of the
 * live World now (this is where the result's path asks are posted, as the resolve on the client tick posted them) and the
 * bag words it read are compared - and applied when all of them hold (its effects run, its legs returned: the same chain
 * the client tick would have resolved now, rule 1/22); a fact that changed drops it ("re-planned while thinking: <fact>",
 * said once per drop in the log - a fact the snapshot did not have is a fetch, not said) and the request goes again with
 * a snapshot of the live answers. While a result is awaited the trip is the thinking stand (NeedChain.thinking: "thinking:
 * N nodes dirty"; the bot stands, rule 22). A result posted is {@link #ready} for the kept thinking trip (BatchWay prices it
 * again) and {@link #landed} for the running stand (it re-prices on a new one, as on a landed path). A request
 * the worker could not resolve (it threw) is said and resolved here, on the client tick (rule 7). No Minecraft types
 * (JUnit: D6WorkerTest). Loader notes: none.
 */
public final class PlannerPort implements BatchWay.Planner {
    private final PlannerWorker worker;
    private final LongSupplier tick;
    private final Consumer<String> log;
    private final Map<String, State> states = new HashMap<>();
    private long version;

    /** The probe numbers (client tick): the last applied resolve and the tick-side costs. */
    private PlanNodes.Stats lastStats = PlanNodes.Stats.NONE;
    private long lastResolveNs, lastApplyNs, lastSnapNs, resolves, drops, fetches, caps, kepts;

    /** One trip's request and its last result's facts. */
    private static final class State {
        Playbook pb;
        Set<String> kept;
        long version = -1;
        Collection<PlanNodes.Fact> keys = List.of();
        PlannerWorker.Done ready;
        String waitWords = "thinking: the first resolve of this trip";
        /** Results of this trip dropped in a row since its last apply (past ROUNDS the stand says why). */
        int dropsInRow;
        /** Review (b): the tick the cap's line was last said (said again every RESAY_S while the streak lasts). */
        long capSaidAt;
        /** 0.39.z78 D6 (run DP): the result last applied, for its playbook and kept fuels - a new ask of the same is checked against it first. */
        PlannerWorker.Done applied;
        Playbook appliedPb;
        Set<String> appliedKept;
        /** 0.39.z78 D6 (run DT): an order's own chain request (null: the trip's legs) - asked and applied as the legs are. */
        BatchWay.OwnAsk own, appliedOwn;
    }

    /** structural: results of one trip dropped in a row before the trip stands with the reason (a fetch round or two is normal) */
    static final int ROUNDS = 8;

    /** structural: seconds between the cap's line said again while a trip stands past the cap (a person reads the log) */
    static final int RESAY_S = 30;

    public PlannerPort(PlannerWorker worker, LongSupplier tick, Consumer<String> log) {
        this.worker = worker;
        this.tick = tick;
        this.log = log;
    }

    /** Results posted by the worker since the start (the client tick re-prices the running stand on a new one). */
    public long landed() { return worker.landed(); }

    @Override
    public boolean ready(String key) {
        State s = states.get(key);
        if (s == null) return false;
        PlannerWorker.Done d = worker.poll(key);
        if (d != null) s.ready = d;
        return s.ready != null;
    }

    @Override
    public NeedChain.Legs legs(String key, Playbook pb, Set<String> kept, NeedChain.World w) { return ask(key, pb, kept, null, w); }

    /**
     * 0.39.z78 D6 (run DT 16:25:09-21: the one-leg trip's own chain, 2.1-2.2 s on the client tick at each landing): an order's
     * own chain on the worker - the same protocol as the legs (snapshot, the live check, the kept applied result, the drop
     * cap's stand); the chain, or the thinking stand (NeedChain.thinking) while the worker resolves.
     */
    @Override
    public NeedChain.Chain own(String key, BatchWay.OwnAsk own, NeedChain.World w) { return ask(key, null, Set.of(), own, w).chain(); }

    /** The request here, on the client tick (rule 7: the worker threw): the legs, or the own chain as legs. */
    private static NeedChain.Legs here(Playbook pb, Set<String> kept, BatchWay.OwnAsk own, NeedChain.World w) {
        if (own != null) return new NeedChain.Legs(own.resolve(w), Map.of(), -1);
        return NeedChain.keepingFuel(kept, () -> NeedChain.resolvePlaybookLegs(pb, w));
    }

    private NeedChain.Legs ask(String key, Playbook pb, Set<String> kept, BatchWay.OwnAsk own, NeedChain.World w) {
        State s = states.computeIfAbsent(key, k -> new State());
        PlannerWorker.Done d = worker.poll(key);
        if (d != null) s.ready = d;
        Map<PlanNodes.Fact, Object> live = new HashMap<>();
        boolean same = s.version >= 0 && Deep.equal(pb, s.pb) && Deep.equal(kept, s.kept) && Deep.equal(own, s.own);
        PlannerWorker.Done r = s.ready;
        s.ready = null;
        if (r != null && same && r.job().version() == s.version) {
            if (r.error() != null) {
                io.github.mojolowjo.entropybot.Quiet.warn("PlannerPort.legs: the worker's resolve of " + key + " threw", r.error());
                s.version = -1;
                s.dropsInRow = 0;
                if (r.error() instanceof RuntimeException) {
                    // rule 7: the worker could not resolve it - said, and resolved here as before D6
                    log.accept("planner: the trip " + key + " could not be resolved off the render thread (" + r.error() + "): resolved on the client tick");
                    return here(pb, kept, own, w);
                }
                // review: a stack overflow or out of memory is not tried again on the render thread (its stack is the smaller):
                // the trip fails with the reason, said loud; a new request (a changed fact) asks the worker again
                String why = "the planner could not resolve the trip off the render thread (" + r.error() + "; rule 7)";
                log.accept("planner: trip " + key + ": " + why);
                return new NeedChain.Legs(new NeedChain.Chain(List.of(), 0, NeedChain.PLAYBOOK + ": " + why, false, why), Map.of(), -1);
            }
            long t0 = System.nanoTime();
            var laps = io.github.mojolowjo.entropybot.aa.core.TickLaps.AA;
            // review: a result priced in other game data (items, tiers, Tuning, the loot book) than the snapshot's and the live
            // one's - changed while it thought - is dropped as any changed fact
            boolean data = epochHolds(r, w);
            List<String> changed = data ? check(r, w, live) : List.of("game data (items, tiers, Tuning or the loot book changed while it thought)");
            laps.split("check", System.nanoTime() - t0);
            s.keys = r.read().facts().keySet();
            if (changed.isEmpty()) {
                long a0 = System.nanoTime();
                r.read().runEffects(w.path());
                laps.split("apply", System.nanoTime() - a0);
                s.applied = r;
                s.appliedPb = pb;
                s.appliedKept = Set.copyOf(kept);
                s.appliedOwn = own;
                lastApplyNs = System.nanoTime() - t0;
                lastResolveNs = r.resolveNs();
                lastStats = r.read().stats();
                resolves++;
                s.version = -1;                                  // applied: the next ask is a new request
                s.dropsInRow = 0;
                return r.legs();
            }
            String said = null;
            for (String c : changed) if (!c.startsWith(MISS)) { said = c; break; }
            if (said != null) {
                drops++;
                log.accept("planner: trip " + key + ": re-planned while thinking: " + said + (changed.size() > 1 ? " (+" + (changed.size() - 1) + " more)" : ""));
            } else fetches++;
            // review (rule 14, rule 15): the rounds are counted against a structural cap, never a price. Run DR round (manager
            // 2026-10-27; runs DQ/DR: the fallback's full resolve on the client tick was the 870 ms tick, 16 of them in DR):
            // past it the trip is never resolved on the client tick - it stands with the reason (rule 22; its leaf is "not
            // priced yet", rule 15), said loud once a streak, and asks the worker again as on any drop
            s.waitWords = "thinking: " + (data ? r.read().dirty(live, w) + " nodes dirty" : "the game data changed");
            if (++s.dropsInRow >= ROUNDS) {
                // review (b): nothing else bounds this stand (its AwaitPaths re-prices every pathCost.firstAnswerWaitS, a price
                // fact, never a park) - the reason is said again every RESAY_S while the streak lasts
                long now = tick.getAsLong();
                if (s.dropsInRow == ROUNDS || now - s.capSaidAt >= RESAY_S * io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS) {
                    log.accept("planner: trip " + key + ": " + s.dropsInRow + " results in a row dropped (the facts kept changing while it thought; last: "
                            + changed.get(0) + "): the trip stands until a result holds (rule 22; rule 15: never a stale price)");
                    if (s.dropsInRow == ROUNDS) caps++;
                    s.capSaidAt = now;
                }
                s.waitWords = "thinking: the facts keep changing while it thinks (" + s.dropsInRow + " results in a row dropped; last: " + changed.get(0) + ")";
            }
        } else if (same && worker.busy(key)) {
            return NeedChain.thinking(s.waitWords);               // the request is being resolved: the stand goes on
        } else if (!same) {
            // 0.39.z78 D6 (run DP: a worker result re-priced the trip, the re-price asked the worker again, its result
            // re-priced the trip - 435 trip lines, a stand every ~250 ms): the result last applied for this very request
            // (the same playbook, the same kept fuels) is checked live first; while every fact it read holds it is the answer
            // again (its effects run again, fresh step objects: as a resolve now would give) - no worker round, no stand
            if (s.applied != null && !worker.busy(key) && Deep.equal(pb, s.appliedPb) && Deep.equal(kept, s.appliedKept) && Deep.equal(own, s.appliedOwn)) {
                long c0 = System.nanoTime();
                List<String> changed = epochHolds(s.applied, w) ? check(s.applied, w, live) : List.of("game data");
                io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.split("check", System.nanoTime() - c0);
                if (changed.isEmpty()) {
                    long a0 = System.nanoTime();
                    s.applied.read().runEffects(w.path());
                    io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.split("apply", System.nanoTime() - a0);
                    kepts++;
                    return NeedChain.fresh(s.applied.legs());
                }
                s.keys = s.applied.read().facts().keySet();
                s.applied = null;
            }
            s.dropsInRow = 0;
            s.waitWords = s.keys.isEmpty() ? "thinking: the first resolve of this trip" : "thinking: a new request for this trip";
        }
        long t0 = System.nanoTime();
        s.pb = pb;
        s.kept = Set.copyOf(kept);
        s.own = own;
        s.version = ++version;
        PlannerSnapshot snap = PlannerSnapshot.take(w, s.keys, live, s.version, tick.getAsLong());
        worker.submit(new PlannerWorker.Job(key, s.version, pb, s.kept, snap, own));
        lastSnapNs = System.nanoTime() - t0;
        io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.split("snapshot", lastSnapNs);
        return NeedChain.thinking(s.waitWords);
    }

    /** The start of a changed fact the snapshot did not have (a fetch: the next snapshot carries it, nothing is said). */
    static final String MISS = "not in the snapshot: ";

    /** The facts and bag words of {@code r}'s resolve that differ on {@code w} now (asked once each, answers kept in {@code live}). */
    static List<String> check(PlannerWorker.Done r, NeedChain.World w, Map<PlanNodes.Fact, Object> live) {
        List<String> out = new ArrayList<>();
        boolean cell = usesSurfaceCell(r);                               // item 25: the surface fact matters as far as the result used it
        for (var e : r.read().facts().entrySet()) {
            PlanNodes.Fact f = e.getKey();
            Object now = timedAsk(w, f);
            live.put(f, now);
            Object kept = PlanNodes.unboxed(e.getValue());
            if (same(f, kept, now, cell)) continue;
            // D11 fix probe (run FF: 6,801 "surface" drops the log could not explain - the fact's key alone was said): old -> new
            out.add((r.job().snap().copied(f) ? "" : MISS) + f.field() + (f.key() == null ? "" : " " + f.key()) + ": " + brief(kept) + " -> " + brief(now));
        }
        String bag = r.read().bagChanged(w);
        if (bag != null) out.add(bag);
        return out;
    }

    /** structural: a fact's value in the drop line is cut past this many characters (a list of cells can run long) */
    static final int BRIEF = 160;

    /** A fact's value for the drop line: arrays by content, anything else its own text, cut at {@link #BRIEF}. */
    static String brief(Object v) {
        String s = v == null ? "null" : v instanceof int[] a ? java.util.Arrays.toString(a) : v instanceof Object[] a ? java.util.Arrays.deepToString(a) : v.toString();
        return s.length() <= BRIEF ? s : s.substring(0, BRIEF) + "...";
    }

    /** 0.39.z78 D6 (run DQ: the check and the snapshot at 100-160 ms): one live fact ask, its time told to the aatick line by field. */
    static Object timedAsk(NeedChain.World w, PlanNodes.Fact f) {
        var laps = io.github.mojolowjo.entropybot.aa.core.TickLaps.AA;
        long t0 = laps.now();
        Object v = NeedChain.ask(w, null, f);
        if (t0 != 0) laps.ask(f.field(), laps.now() - t0);
        return v;
    }

    /**
     * 0.39.z78 D6 (run DP: 25 drops "re-planned while thinking: held"): a kept answer and the live one agree. The holds are
     * the batch's holds, the same set whoever of its orders asks, each asker's own first (BatchWay.trip; its trip key reads them
     * as a set): compared as a set. Everything else by value.
     */
    static boolean same(PlanNodes.Fact f, Object kept, Object now) { return same(f, kept, now, true); }

    /**
     * Queue item 25 (runs FG 655, FP 3,936 "surface" drops; FP: 176 results in a row dropped, 127 stands past the cap, the
     * own trips starved for minutes): the live surface fact moves with every block the bot walks (its distance, the nearest
     * sky cell), but a result that walks to no surface cell used only whether the bot is UNDER ground ({@code usesCell} false:
     * no ToSurface step in it) - two surface facts with the same under-ground state are the same for it; a result with a walk
     * up ({@code usesCell}) compares the cell, the depth, the hole and the floor, never the straight distance (rule 14: rough).
     */
    static boolean same(PlanNodes.Fact f, Object kept, Object now, boolean usesCell) {
        if ("held".equals(f.field()) && kept instanceof List<?> a && now instanceof List<?> b) return new java.util.TreeSet<Object>(a).equals(new java.util.TreeSet<Object>(b)) && a.size() == b.size();
        if ("surface".equals(f.field()) && kept instanceof NeedChain.Surface a && now instanceof NeedChain.Surface b) return sameSurface(a, b, usesCell);
        return Deep.equal(kept, now);
    }

    /** Item 25 (pure): two surface facts agree for a result - see {@link #same(PlanNodes.Fact, Object, Object, boolean)}. */
    static boolean sameSurface(NeedChain.Surface a, NeedChain.Surface b, boolean usesCell) {
        if (surfaceUnder(a) != surfaceUnder(b) || a.hole() != b.hole()) return false;
        if (!usesCell) return true;
        return a.depth() == b.depth() && a.x() == b.x() && a.y() == b.y() && a.z() == b.z() && a.floor() == b.floor()
                && java.util.Arrays.equals(a.top(), b.top()) && (a.dist() < 1) == (b.dist() < 1);   // structural: "the target is here" (NeedChain.surfaceWalk)
    }

    /** The explore leaf's own under-ground test (NeedChain: run EH's ruling - a hole round the feet is below the surface at any depth). */
    static boolean surfaceUnder(NeedChain.Surface s) {
        return s.depth() >= io.github.mojolowjo.entropybot.aa.AaLadder.surfaceDepth() || s.hole() && s.depth() > 0;
    }

    /** Item 25: the result's steps walk to a surface cell (a ToSurface step) - then the surface fact's cell matters to it. */
    static boolean usesSurfaceCell(PlannerWorker.Done r) {
        NeedChain.Legs l = r.legs();
        for (NeedChain.Chain c : new NeedChain.Chain[]{l == null ? null : l.chain(), l == null ? null : l.whole()}) {
            if (c == null || c.steps() == null) continue;
            for (io.github.mojolowjo.entropybot.aa.moves.Step s : c.steps()) if (s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.ToSurface) return true;
        }
        return false;
    }

    /** The game data's epoch of {@code r} (snapshot, start, end) is the live one now. */
    static boolean epochHolds(PlannerWorker.Done r, NeedChain.World w) {
        return r.epochStart() != null && Deep.equal(r.job().snap().epoch(), r.epochStart()) && Deep.equal(r.epochStart(), r.epochEnd())
                && Deep.equal(r.epochEnd(), NeedChain.dataEpoch(w));
    }

    /** The probe text: the last applied resolve's nodes and times, the drops, the requests in flight. */
    public String probe() {
        return String.format(Locale.ROOT, "planner: %s, resolve %.1f ms (worker), apply %.1f ms, snapshot %.1f ms, applied %d, kept %d, dropped %d (+%d fetches), stood past the cap %d, in flight %d",
                lastStats.text(), lastResolveNs / 1e6, lastApplyNs / 1e6, lastSnapNs / 1e6, resolves, kepts, drops, fetches, caps, worker.inFlight());     // structural: ns per ms
    }

    /** A new world or AA off: the requests are forgotten (a late result of the old world is dropped by its version). */
    public void forget() { states.clear(); }

    /** Tests: the trips with a request kept. */
    Map<String, ?> states() { return new LinkedHashMap<>(states); }
}
