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
    private long lastResolveNs, lastApplyNs, lastSnapNs, resolves, drops, fetches, caps, kepts, takes;

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
        /** job-freeze: the reason of the last drop (a fact's field and key) and how many results in a row were dropped for it. */
        String dropReason;
        /**
         * job-planner-stick (worlds run 2, w097 07:06:55-07:08:35: 126 results in a row dropped, the reasons rotating - feet,
         * mobs, seen, price - so the same-reason count started over each time and no answer was taken for 100 s): the
         * results in a row dropped for a said reason (a changed fact, whichever) - the take counts these.
         */
        int sameDropsInRow;
        /** Review (b): the tick the cap's line was last said (said again every RESAY_S while the streak lasts). */
        long capSaidAt;
        /** 0.39.z78 D6 (run DP): the result last applied, for its playbook and kept fuels - a new ask of the same is checked against it first. */
        PlannerWorker.Done applied;
        /**
         * job-planner-stick (worlds run 2, w079 06:02:15.066 -> .110: an answer taken after the drops was asked again 44 ms
         * later, checked against the facts its resolve had read - the very ones it was taken in spite of - and dropped: "a
         * new request for this trip", 24 takes that changed nothing): the live answers the applied result was TAKEN on
         * (null: applied with every fact holding) - the kept check reads these, so a taken answer holds while the facts
         * stay as they were when it was taken.
         */
        Map<PlanNodes.Fact, Object> appliedLive;
        /** The live bag at the take (with {@link #appliedLive}): a taken answer whose difference was the bag holds while the bag stays so. */
        Map<String, Integer> appliedBag;
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

    /**
     * job-planner-stick, review round 2: a TAKEN answer was resolved on the facts before the last change, so it is the
     * stale one by exactly that change (a stand taken with the last walk still computing stayed the answer with every
     * walk priced). It is the answer only UNTIL a result resolved on the live facts lands: while it holds on the facts of
     * its take, a request on the live facts is out ({@link #ask0} submits it) and the taken answer stands in for the
     * thinking stand. A result that holds replaces it; a fact moved since the take ends it.
     */
    private NeedChain.Legs ask(String key, Playbook pb, Set<String> kept, BatchWay.OwnAsk own, NeedChain.World w) {
        Map<PlanNodes.Fact, Object> live = new HashMap<>();
        NeedChain.Legs l = ask0(key, pb, kept, own, w, live);
        if (!NeedChain.thinking(l.chain())) return l;
        NeedChain.Legs t = taken(states.get(key), pb, kept, own, w, live);
        return t != null ? t : l;
    }

    /** The taken answer of {@code s} for this request while the facts stay as they were at its take; null: none, or ended (forgotten). */
    private NeedChain.Legs taken(State s, Playbook pb, Set<String> kept, BatchWay.OwnAsk own, NeedChain.World w, Map<PlanNodes.Fact, Object> live) {
        if (s == null || s.applied == null || s.appliedLive == null) return null;
        if (!(Deep.equal(pb, s.appliedPb) && Deep.equal(kept, s.appliedKept) && Deep.equal(own, s.appliedOwn))) return null;
        long c0 = System.nanoTime();
        List<String> changed = epochHolds(s.applied, w) ? check(s.applied, w, live, s.appliedLive, s.appliedBag) : List.of("game data");
        io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.split("check", System.nanoTime() - c0);
        if (!changed.isEmpty()) {
            s.applied = null;
            s.appliedLive = null;
            s.appliedBag = null;
            return null;
        }
        s.applied.read().runEffects(w.path());
        kepts++;
        return NeedChain.fresh(s.applied.legs());
    }

    private NeedChain.Legs ask0(String key, Playbook pb, Set<String> kept, BatchWay.OwnAsk own, NeedChain.World w, Map<PlanNodes.Fact, Object> live) {
        State s = states.computeIfAbsent(key, k -> new State());
        PlannerWorker.Done d = worker.poll(key);
        if (d != null) s.ready = d;
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
            // job-freeze (run JAP: 293 results in a row dropped, the trip stood 2:28 until it parked): results dropped
            // planner.dropsBeforeTake times in a row - this answer is taken, said once (rule 7: a planner that cannot
            // settle says so and acts). A fetch (a fact the snapshot lacked) is never counted: its next round settles it.
            // job-planner-stick (w097): counted whatever the changed fact is - reasons that rotate never reached the take
            String why = null, first = null;
            for (String c : changed) if (!c.startsWith(MISS)) { why = reason(c); first = c; break; }
            s.dropReason = why;
            s.sameDropsInRow = why == null ? 0 : s.sameDropsInRow + 1;
            boolean take = why != null && s.sameDropsInRow > dropsBeforeTake();
            if (take) {
                log.accept("planner: trip " + key + ": the facts kept changing " + (s.sameDropsInRow - 1) + " times (last: " + first
                        + "); this answer is taken (rule 7)");
                takes++;
                s.dropReason = null;
                s.sameDropsInRow = 0;
            }
            if (changed.isEmpty() || take) {
                long a0 = System.nanoTime();
                r.read().runEffects(w.path());
                laps.split("apply", System.nanoTime() - a0);
                s.applied = r;
                s.appliedLive = take ? new HashMap<>(live) : null;     // job-planner-stick: a taken answer is kept on the facts it was taken on
                s.appliedBag = take ? startBag(r.job(), w) : null;
                s.appliedPb = pb;
                s.appliedKept = Set.copyOf(kept);
                s.appliedOwn = own;
                lastApplyNs = System.nanoTime() - t0;
                lastResolveNs = r.resolveNs();
                lastStats = r.read().stats();
                resolves++;
                s.version = -1;                                  // applied: the next ask is a new request
                s.dropsInRow = 0;
                if (!take) { s.dropReason = null; s.sameDropsInRow = 0; }
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
            s.waitWords = "thinking: " + (data ? r.read().dirty(live, startBag(r.job(), w)) + " nodes dirty" : "the game data changed");
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
            // review round 2: a TAKEN answer (appliedLive) is never the kept one here - the request goes out on the live
            // facts and the taken answer stands in until its result lands (ask, taken)
            if (s.applied != null && s.appliedLive == null && !worker.busy(key) && Deep.equal(pb, s.appliedPb) && Deep.equal(kept, s.appliedKept) && Deep.equal(own, s.appliedOwn)) {
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
            s.dropReason = null;
            s.sameDropsInRow = 0;
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

    /** job-freeze: results dropped in a row for one reason before the next is taken (Tuning planner.dropsBeforeTake). */
    static int dropsBeforeTake() { return Math.max(1, (int) Math.round(io.github.mojolowjo.entropybot.aa.brain.Tuning.get("planner.dropsBeforeTake"))); }

    /** job-freeze: a drop's reason - the changed fact's field and key, the text before its ": old -> new" (pure). */
    static String reason(String changed) {
        int i = changed.indexOf(": ");
        return i < 0 ? changed : changed.substring(0, i);
    }

    /** The start of a changed fact the snapshot did not have (a fetch: the next snapshot carries it, nothing is said). */
    static final String MISS = "not in the snapshot: ";

    /** The facts and bag words of {@code r}'s resolve that differ on {@code w} now (asked once each, answers kept in {@code live}). */
    static List<String> check(PlannerWorker.Done r, NeedChain.World w, Map<PlanNodes.Fact, Object> live) { return check(r, w, live, null, null); }

    /**
     * job-planner-stick: {@link #check(PlannerWorker.Done, NeedChain.World, Map)} for a result that was TAKEN - a fact in
     * {@code base} (the live answers at the take) is compared with that answer, never with the one the resolve read (the
     * take said those differ and took it), and a bag that differs from the one the resolve read with {@code baseBag} (the
     * live bag at the take); null: every fact and the bag against the resolve's own. A fact already in {@code live}
     * (asked this tick) is not asked again.
     */
    static List<String> check(PlannerWorker.Done r, NeedChain.World w, Map<PlanNodes.Fact, Object> live, Map<PlanNodes.Fact, Object> base, Map<String, Integer> baseBag) {
        List<String> out = new ArrayList<>();
        boolean cell = usesSurfaceCell(r);                               // item 25: the surface fact matters as far as the result used it
        for (var e : r.read().facts().entrySet()) {
            PlanNodes.Fact f = e.getKey();
            Object now = live.containsKey(f) ? live.get(f) : timedAsk(w, f);
            live.put(f, now);
            Object kept = base != null && base.containsKey(f) ? base.get(f) : PlanNodes.unboxed(e.getValue());
            if (same(f, kept, now, cell)) continue;
            // D11 fix probe (run FF: 6,801 "surface" drops the log could not explain - the fact's key alone was said): old -> new
            out.add((r.job().snap().copied(f) ? "" : MISS) + f.field() + (f.key() == null ? "" : " " + f.key()) + ": " + brief(kept) + " -> " + brief(now));
        }
        // job-own-aside-worker: an own chain read the bag less its held -d amount (PlannerWorker.resolve): compared like with like
        Map<String, Integer> start = startBag(r.job(), w);
        String bag = r.read().bagChanged(start);
        if (bag != null && baseBag != null && start.equals(baseBag)) bag = null;      // the bag of the take, unchanged since
        if (bag != null) out.add(bag);
        return out;
    }

    /**
     * job-own-aside-worker: the bag a result of {@code j} is checked against, the one its resolve would start from now -
     * {@code w}'s live bag for the trip's legs, that bag less the order's own held -d amount (OwnAsk.bagLess, as
     * PlannerWorker.resolve gives it) for an own chain. Review (key order): lowered in the live order, never through a World
     * made again (OwnAsk.pricedOn: Map.copyOf re-orders the keys, and the scans and the whole bag are compared in order).
     */
    static java.util.LinkedHashMap<String, Integer> startBag(PlannerWorker.Job j, NeedChain.World w) {
        java.util.LinkedHashMap<String, Integer> live = NeedChain.liveBag(w);
        return j == null || j.own() == null ? live : j.own().bagLess(live);
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
     * <p>job-planner-stick (worlds run 2, w079: 104 drops "held", the list flipping between two forms on alternate rounds):
     * a held line is "key: why" (World#withoutSources) and the why of one key differs with the order that asks - the
     * learner reads its own reason, every other order the world fact's ("... (a world fact: X learned it at ...)",
     * WorldFacts.text). Review round 2: the planner does read a held reason (NeedChain: BandEnd.heldColumn takes the dig
     * column from it), so the reason is compared too - only the world fact's appended words (who learned it and where:
     * WorldFacts.bare) are left out, the lines as a set ({@link #heldLines}).
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
        if ("held".equals(f.field()) && kept instanceof List<?> a && now instanceof List<?> b) return heldLines(a).equals(heldLines(b));
        if ("surface".equals(f.field()) && kept instanceof NeedChain.Surface a && now instanceof NeedChain.Surface b) return sameSurface(a, b, usesCell);
        if ("price".equals(f.field()) && kept instanceof io.github.mojolowjo.entropybot.aa.PathPrice.Answer a
                && now instanceof io.github.mojolowjo.entropybot.aa.PathPrice.Answer b) return samePrice(a, b);
        return Deep.equal(kept, now);
    }

    /**
     * job-start (runs JAH 17, JAI 103, JAK 17 "re-planned while thinking: price ..."; JAK: COMPUTING -> COMPUTING with only
     * the queue words changed, each drop a new worker round while the bot stood): two answers of one walk agree for a result
     * when both are still computing - the words of why (queued, re-search waiting) do not change which way wins. A priced
     * walk is compared by value: D6's rule (D6WorkerTest, D6ReplayTest) is that an applied trip is the resolve on the facts
     * now, so a price margin would apply a stale price (rule 15). Pure.
     */
    static boolean samePrice(io.github.mojolowjo.entropybot.aa.PathPrice.Answer a, io.github.mojolowjo.entropybot.aa.PathPrice.Answer b) {
        if (a.kind() == io.github.mojolowjo.entropybot.aa.PathPrice.Kind.COMPUTING && b.kind() == a.kind()) return true;
        // job-planner-stick (worlds run 2, w079: 259 drops "price ... kind=ESTIMATED, reason=not known (timed out 31 s ago)
        // -> (timed out 34 s ago)", the same walk and seconds): an estimated walk's words carry the hold's running clock
        // (NotKnownHold.words) - for the log only; the plan reads its walk and that it is an estimate
        if (a.kind() == io.github.mojolowjo.entropybot.aa.PathPrice.Kind.ESTIMATED && b.kind() == a.kind()) return Deep.equal(a.walk(), b.walk());
        return Deep.equal(a, b);
    }

    /** job-planner-stick (pure): a held list as the set of its "key: why" lines, each without the world fact's appended words (WorldFacts.bare). */
    static java.util.Set<String> heldLines(List<?> held) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Object o : held) out.add(io.github.mojolowjo.entropybot.aa.brain.WorldFacts.bare(String.valueOf(o)));
        return out;
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
        return String.format(Locale.ROOT, "planner: %s, resolve %.1f ms (worker), apply %.1f ms, snapshot %.1f ms, applied %d, kept %d, dropped %d (+%d fetches), stood past the cap %d, taken after drops %d, in flight %d",
                lastStats.text(), lastResolveNs / 1e6, lastApplyNs / 1e6, lastSnapNs / 1e6, resolves, kepts, drops, fetches, caps, takes, worker.inFlight());     // structural: ns per ms
    }

    /** A new world or AA off: the requests are forgotten (a late result of the old world is dropped by its version). */
    public void forget() { states.clear(); }

    /** Tests: the trips with a request kept. */
    Map<String, ?> states() { return new LinkedHashMap<>(states); }
}
