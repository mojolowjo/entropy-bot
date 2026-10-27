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
        /** Results of this trip dropped in a row since its last apply (ROUNDS caps them). */
        int dropsInRow;
        /** 0.39.z78 D6 (run DP): the result last applied, for its playbook and kept fuels - a new ask of the same is checked against it first. */
        PlannerWorker.Done applied;
        Playbook appliedPb;
        Set<String> appliedKept;
    }

    /** structural: results of one trip dropped in a row before it is resolved on the client tick (a fetch round or two is normal) */
    static final int ROUNDS = 8;

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
    public NeedChain.Legs legs(String key, Playbook pb, Set<String> kept, NeedChain.World w) {
        State s = states.computeIfAbsent(key, k -> new State());
        PlannerWorker.Done d = worker.poll(key);
        if (d != null) s.ready = d;
        Map<PlanNodes.Fact, Object> live = new HashMap<>();
        boolean same = s.version >= 0 && Deep.equal(pb, s.pb) && Deep.equal(kept, s.kept);
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
                    return NeedChain.keepingFuel(kept, () -> NeedChain.resolvePlaybookLegs(pb, w));
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
            // review (rule 14, rule 15): the rounds are bounded by a structural cap, never by a price - past it the facts keep
            // changing faster than the worker answers (a walk on the move, answers landing): resolved here on the facts of now
            // this once (the old resolve's freeze, exact), said loud
            if (++s.dropsInRow >= ROUNDS) {
                log.accept("planner: trip " + key + ": " + s.dropsInRow + " results in a row dropped (the facts kept changing while it thought; last: "
                        + changed.get(0) + "): resolved on the client tick this once (rule 15: never a stale price)");
                s.dropsInRow = 0;
                s.version = -1;
                caps++;
                return NeedChain.keepingFuel(kept, () -> NeedChain.resolvePlaybookLegs(pb, w));
            }
            s.waitWords = "thinking: " + (data ? r.read().dirty(live, w) + " nodes dirty" : "the game data changed");
        } else if (same && worker.busy(key)) {
            return NeedChain.thinking(s.waitWords);               // the request is being resolved: the stand goes on
        } else if (!same) {
            // 0.39.z78 D6 (run DP: a worker result re-priced the trip, the re-price asked the worker again, its result
            // re-priced the trip - 435 trip lines, a stand every ~250 ms): the result last applied for this very request
            // (the same playbook, the same kept fuels) is checked live first; while every fact it read holds it is the answer
            // again (its effects run again, fresh step objects: as a resolve now would give) - no worker round, no stand
            if (s.applied != null && !worker.busy(key) && Deep.equal(pb, s.appliedPb) && Deep.equal(kept, s.appliedKept)) {
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
        s.version = ++version;
        PlannerSnapshot snap = PlannerSnapshot.take(w, s.keys, live, s.version, tick.getAsLong());
        worker.submit(new PlannerWorker.Job(key, s.version, pb, s.kept, snap));
        lastSnapNs = System.nanoTime() - t0;
        io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.split("snapshot", lastSnapNs);
        return NeedChain.thinking(s.waitWords);
    }

    /** The start of a changed fact the snapshot did not have (a fetch: the next snapshot carries it, nothing is said). */
    static final String MISS = "not in the snapshot: ";

    /** The facts and bag words of {@code r}'s resolve that differ on {@code w} now (asked once each, answers kept in {@code live}). */
    static List<String> check(PlannerWorker.Done r, NeedChain.World w, Map<PlanNodes.Fact, Object> live) {
        List<String> out = new ArrayList<>();
        for (var e : r.read().facts().entrySet()) {
            PlanNodes.Fact f = e.getKey();
            Object now = timedAsk(w, f);
            live.put(f, now);
            if (same(f, PlanNodes.unboxed(e.getValue()), now)) continue;
            out.add((r.job().snap().copied(f) ? "" : MISS) + f.field() + (f.key() == null ? "" : " " + f.key()));
        }
        String bag = r.read().bagChanged(w);
        if (bag != null) out.add(bag);
        return out;
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
    static boolean same(PlanNodes.Fact f, Object kept, Object now) {
        if ("held".equals(f.field()) && kept instanceof List<?> a && now instanceof List<?> b) return new java.util.TreeSet<Object>(a).equals(new java.util.TreeSet<Object>(b)) && a.size() == b.size();
        return Deep.equal(kept, now);
    }

    /** The game data's epoch of {@code r} (snapshot, start, end) is the live one now. */
    static boolean epochHolds(PlannerWorker.Done r, NeedChain.World w) {
        return r.epochStart() != null && Deep.equal(r.job().snap().epoch(), r.epochStart()) && Deep.equal(r.epochStart(), r.epochEnd())
                && Deep.equal(r.epochEnd(), NeedChain.dataEpoch(w));
    }

    /** The probe text: the last applied resolve's nodes and times, the drops, the requests in flight. */
    public String probe() {
        return String.format(Locale.ROOT, "planner: %s, resolve %.1f ms (worker), apply %.1f ms, snapshot %.1f ms, applied %d, kept %d, dropped %d (+%d fetches), on the client tick %d, in flight %d",
                lastStats.text(), lastResolveNs / 1e6, lastApplyNs / 1e6, lastSnapNs / 1e6, resolves, kepts, drops, fetches, caps, worker.inFlight());     // structural: ns per ms
    }

    /** A new world or AA off: the requests are forgotten (a late result of the old world is dropped by its version). */
    public void forget() { states.clear(); }

    /** Tests: the trips with a request kept. */
    Map<String, ?> states() { return new LinkedHashMap<>(states); }
}
