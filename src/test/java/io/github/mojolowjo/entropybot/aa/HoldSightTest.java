package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-hold-sight point 1, replaying worlds run 4 w044 (log lines 1311-1317): oak_log parked at 07:41:09.915 ("stand while
 * paths compute ... wakes ... or a new source is seen"), the line's deposit went 2 ms later ("nothing it waits on can change
 * now"), and at 07:41:12.953 - 3 s later, nearby.cacheMs, the bot where it stood - oak_log woke: "a new source seen: oak_log
 * at -176 71 -223". The nearby scan's answers the park read were older than the park: a new source could still be seen.
 * Wanted: the hold lasts until the scan has read again since the park (Inputs.sightAtMs, a fact), the wake comes first and
 * the deposit never goes short; and the run 3 circle stays closed: with no new source, the hold ends once the scan read
 * again, and the deposit goes.
 */
class HoldSightTest {
    @AfterEach
    void clear() { Tuning.reset(); }

    /** The w044 line: cobblestone met, oak_log 13 of 16, oak_log parked with the bot standing, its looks taken. */
    static OrderLineSim w044(Goals.Goal[] out) {
        OrderLineSim s = new OrderLineSim();
        s.bag.put("wooden_pickaxe", 1);
        s.bag.put("cobblestone", 32);
        s.bag.put("oak_log", 13);
        s.feet[0] = -176; s.feet[1] = 71; s.feet[2] = -220;
        s.surface = new NeedChain.Surface(0, -176, 71, -220, 0);
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true);
        Goals.Goal log = s.order("oak_log", 16, "b1", true);
        s.bb.in.pos = s.feet.clone();
        s.bb.in.bag = Map.copyOf(s.bag);
        // 07:41:09.915: oak_log stops; the scan's answers it read were made before (the tree just cut)
        assertTrue(Goals.park(s.bb, log, "stopped at step 1 (stand while paths compute (up to 30 s)): the walks' answer landed (walk to -173 71 -217: 1 s, complete)"));
        log.looked = true;                       // w044: no look-around was due (it had run on a changed fact)
        s.bb.in.sightAtMs = s.clock.now() - 500;
        out[0] = cob;
        out[1] = log;
        return s;
    }

    @Test
    void w044_theScanNotReadSinceThePark_holdsTheDeposit_andTheWakeComesFirst() {
        Goals.Goal[] g = new Goals.Goal[2];
        OrderLineSim s = w044(g);
        Goals.Goal log = g[1];
        s.run(1, 2);
        assertTrue(s.ordersLeft().contains(log), "oak_log is still an order: " + s.said);
        assertNotNull(log.parked, "oak_log stays stopped: " + s.said);
        assertTrue(s.due.isEmpty(), "the deposit does not go while a new source can still be seen from here: " + s.said);
        String why = Goals.stillChanges(Goals.of(s.bb), log, false, false, false, s.bb.in.sightAtMs);
        assertNotNull(why, "the scan has not read again since the park");
        assertTrue(why.contains("not read again since it stopped"), why);
        // 07:41:12.953: the renewal finds the next tree, the bot where it stood
        s.seen.put("oak_log", List.of(new Spot(-176, 71, -223, 3, "minecraft:oak_log", 6)));
        s.bb.in.sightAtMs = s.clock.now() + 3000;
        s.run(1, 3000);
        assertNull(log.parked, "oak_log woke on the new source: " + s.said);
        assertTrue(s.said.stream().noneMatch(l -> l.contains("short: oak_log")), "the line's deposit never went short: " + s.said);
        assertTrue(s.due.isEmpty(), "nothing went due: " + s.due);
    }

    @Test
    void run3Circle_noNewSource_theHoldEndsOnceTheScanReadAgain() {
        Goals.Goal[] g = new Goals.Goal[2];
        OrderLineSim s = w044(g);
        Goals.Goal log = g[1];
        s.run(1, 2);
        assertTrue(s.due.isEmpty(), "held while the scan is behind the park: " + s.said);
        // the scan renews by itself while the bot stands: the moment passes the park, nothing new is seen
        s.bb.in.sightAtMs = s.clock.now();
        s.run(2, 3000);
        assertNotNull(log.parked, "still stopped (nothing new): " + s.said);
        assertNull(Goals.stillChanges(Goals.of(s.bb), log, false, false, false, s.bb.in.sightAtMs), "nothing can change now");
        assertEquals(List.of(Map.of("cobblestone", 32, "oak_log", 13)), s.due, "the deposit goes with the shortfall named: " + s.said);
        assertTrue(s.said.stream().anyMatch(l -> l.contains("short: oak_log 13 of 16")), "named (rule 7): " + s.said);
    }

    /**
     * Send-back item 4: the live sight fact from its own parts, not set by hand - the nearby scan's answers (the rule
     * NearbyLive.readSince runs, Inputs.oldestInUse), the chunk pass's arrivals (PassSchedule.pendingArrivals, as
     * ChunkPass stamps arrivalsReadAt when none is left) and the wiring's min (Inputs.sightAt). The hold lasts through a
     * deferred renewal and an arrived chunk not read yet, and ends once both have read since the park: the run 3 circle
     * stays closed while the bot stands.
     */
    @Test
    void theLiveSightFact_fromTheScanAndThePass_holdsThenEnds() {
        Goals.Goal[] g = new Goals.Goal[2];
        OrderLineSim s = w044(g);
        Goals.Goal log = g[1];
        long fresh = Tuning.l("nearby.cacheMs");
        long p = log.parkedAtMs;
        long[] answer = {p - 500, p};                  // {at, askedAt}: made before the park, asked at it
        long[] stale = {p - 9000, p - 2 * fresh};     // a set nobody asks any more: no answer in use
        io.github.mojolowjo.entropybot.sense.PassSchedule pass = new io.github.mojolowjo.entropybot.sense.PassSchedule();
        pass.loaded(4, 7, p);                          // a chunk arrived at the park, not read yet
        long[] arrivalsReadAt = {p - 1000};
        java.util.function.LongSupplier sight = () -> io.github.mojolowjo.entropybot.aa.core.Inputs.sightAt(
                io.github.mojolowjo.entropybot.aa.core.Inputs.oldestInUse(s.clock.now(), fresh, List.of(answer, stale)), arrivalsReadAt[0]);
        java.util.function.Supplier<String> held = () -> Goals.stillChanges(List.of(log), log, false, false, false, sight.getAsLong());

        s.clock.advance(100);
        assertEquals(p - 1000, sight.getAsLong(), "the older of the two readings: the pass, with a chunk arrived since");
        assertNotNull(held.get(), "held: the scan and the pass both behind the park");
        s.clock.advance(fresh);
        answer[1] = s.clock.now();                     // asked again, the renewal deferred by the budget: its answer stays old
        assertNotNull(held.get(), "a deferred renewal holds on");
        answer[0] = s.clock.now();                     // renewed
        assertEquals(p - 1000, sight.getAsLong(), "the scan read again, the pass not yet");
        assertNotNull(held.get());
        assertEquals(1, pass.pendingArrivals());
        pass.missed(5, 7, s.clock.now());              // a missed patch of a chunk not loaded: nothing
        pass.take(4, 7, s.clock.now(), 8);
        if (pass.pendingArrivals() == 0) arrivalsReadAt[0] = s.clock.now();     // as ChunkPass.tick stamps it
        assertTrue(sight.getAsLong() >= p, "both read since the park");
        assertNull(held.get(), "nothing can change now: the hold ends (the run 3 circle stays closed)");
        pass.loaded(4, 7, s.clock.now());
        assertTrue(pass.missed(4, 7, s.clock.now() + 1), "a patch on a chunk due: deduped");
        assertEquals(1, pass.pendingArrivals(), "a resend counts as an arrival");
        pass.take(4, 7, s.clock.now(), 8);
        assertTrue(pass.missed(4, 7, s.clock.now()));
        assertEquals(0, pass.pendingArrivals(), "a missed patch's rescan is no arrival (no new chunk came)");
    }

    /** Pure: the sight fact counts only for a park that waits on a new source, and for every order of the line. */
    @Test
    void stillChanges_sight_perPark() {
        Goals.Goal[] g = new Goals.Goal[2];
        OrderLineSim s = w044(g);
        Goals.Goal log = g[1];
        long parked = log.parkedAtMs;
        assertNotNull(Goals.stillChanges(List.of(log), log, false, false, false, parked - 1), "the scan behind the park");
        assertNull(Goals.stillChanges(List.of(log), log, false, false, false, parked), "the scan read at the park");
        assertNull(Goals.stillChanges(List.of(log), log, false, false, false), "no live scan (the simulator): as before");
        log.park = new Goals.Park(Goals.ParkKind.PLACE, new int[]{0, 64, 0}, 0, null, java.util.Set.of());
        assertNull(Goals.stillChanges(List.of(log), log, false, false, false, parked - 1), "sources not known at the park: no sight wake (rule 15)");
    }

    /**
     * Send-back (the reviewer: a chunk pass tick that throws before its stamp every time froze arrivalsReadAt, and every
     * park waiting on a sight held the deposit for ever): a failed tick leaves no pass to wait on - said once a streak - so
     * the hold reads the nearby scan alone and ends once it read again (the run 3 circle stays closed).
     */
    @Test
    void aFailingChunkPass_isNoReaderToWaitOn_saidOnce() {
        Goals.Goal[] g = new Goals.Goal[2];
        w044(g);
        Goals.Goal log = g[1];
        long parked = log.parkedAtMs;
        int[] said = {0};
        long frozen = parked - 1;                                    // the last stamp, before the park
        assertNotNull(Goals.stillChanges(List.of(log), log, false, false, false, io.github.mojolowjo.entropybot.aa.core.Inputs.sightAt(parked + 5, frozen)), "frozen: held");
        long after = io.github.mojolowjo.entropybot.sense.ChunkPass.arrivalsAfterError(frozen, () -> said[0]++);
        after = io.github.mojolowjo.entropybot.sense.ChunkPass.arrivalsAfterError(after, () -> said[0]++);
        assertEquals(1, said[0], "said once a streak (rule 7)");
        assertNull(Goals.stillChanges(List.of(log), log, false, false, false, io.github.mojolowjo.entropybot.aa.core.Inputs.sightAt(parked + 5, after)), "the nearby scan read since the park: the hold ends");
        assertNotNull(Goals.stillChanges(List.of(log), log, false, false, false, io.github.mojolowjo.entropybot.aa.core.Inputs.sightAt(parked - 1, after)), "the nearby scan not read since: still held on it");
    }
}
