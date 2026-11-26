package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits.Kind;
import io.github.mojolowjo.entropybot.aa.brain.PutWatch;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * deposit-goal stage 4, review 3 (fault 1, serious): replays of the runtime's put sequence (AaRuntime: putStarted from
 * StepHands, the task's id given after runTyped returns, typedEnded / cancel / poll's lost runner / the goals tick's net)
 * on PutWatch and the one owed store. The serious case: a bot already by the main chest gets [Deposit] alone (no ToStation
 * for a 0 s walk), so the put starts inside the new task's start, before its id - it was tied to -1 and never read again.
 */
class PutWatchTest {
    static final String AT = "0 64 0";

    /** A line of cobblestone 32 (met) and oak_log (parked, 11 in the bag) comes due; the store and the orders. */
    record Line(OrderLineSim s, Goals.Goal cob, Goals.Goal log) {}

    static Line due() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 32);
        s.bag.put("oak_log", 11);
        assertEquals(Kind.NOTED, s.deposits.weigh(cob, "done", s.bag, AT).kind());
        log.parked = "the explore from here froze";
        assertEquals(Kind.DUE, s.deposits.sweep(log, s.bag, AT).kind());
        assertFalse(s.deposits.owed().isEmpty());
        return new Line(s, cob, log);
    }

    static final long PREVIOUS = -1;          // test: the id current holds while a new task's start runs (no task yet)
    static final long TASK = 7, OTHER = 8;    // test: two task ids

    @Test
    void theFirstStepPut_isTiedToItsNewTask_andItsDoneEndReachesTheChest() {
        Line l = due();
        PutWatch w = new PutWatch(l.s().deposits);
        int reached = l.s().deposits.putsReached();
        w.started(PREVIOUS, true);                          // [Deposit] alone: the put starts inside runTyped
        assertTrue(w.on());
        assertNull(w.ended(PREVIOUS, true, null, "done"), "the previous task's id never reads it");
        assertNull(w.idle(PREVIOUS, false), "pending: the net waits for its task's id");
        w.taskStarted(TASK);                                // current = ++seq
        assertEquals(TASK, w.task());
        assertNull(w.ended(OTHER, true, null, "done"), "another task's end never reads it");
        w.legOver(l.s().deposits.onItsWay(), true);         // StepHands: the step ended done with its count
        String line = w.ended(TASK, true, null, "ok: deposited 43 items");
        assertNotNull(line);
        assertTrue(line.contains("reached the chest"), line);
        assertFalse(w.on());
        assertTrue(l.s().deposits.owed().isEmpty(), "in the chest: nothing owed");
        assertEquals(reached + 1, l.s().deposits.putsReached());
    }

    @Test
    void theFirstStepPut_cancelled_isOwedAgain_andAWakeTakesItBack() {
        Line l = due();
        Map<String, Integer> owed = l.s().deposits.owed();
        PutWatch w = new PutWatch(l.s().deposits);
        w.started(PREVIOUS, true);
        w.taskStarted(TASK);
        assertNull(w.cancelled(OTHER, "aa goal reached"), "another task's cancel reads nothing");
        String line = w.cancelled(TASK, "a higher desire");
        assertNotNull(line);
        assertTrue(line.contains("did not reach the chest whole") && line.contains("nothing went in"), line);
        assertFalse(w.on());
        assertEquals(owed, l.s().deposits.owed(), "owed again, never counted in the chest");
        // the put is no longer on its way: a wake of the line's parked order takes the deposit back
        LineDeposits.Out back = l.s().deposits.reopen(l.log());
        assertEquals(Kind.WOKE_TAKEN_BACK, back.kind(), back.text());
        assertTrue(l.s().deposits.owed().isEmpty(), "taken back: nothing owed");
    }

    @Test
    void theFirstStepPut_preempted_isOwedAgain_onlyTheRestWhenSomeWentIn() {
        Line l = due();
        Map<String, Integer> owed = l.s().deposits.owed();
        String k = owed.keySet().iterator().next();
        PutWatch w = new PutWatch(l.s().deposits);
        w.started(PREVIOUS, true);
        w.taskStarted(TASK);
        w.moved(Map.of(k, 1));                              // one went in before another run took the runner
        assertNull(w.lost(OTHER));
        String line = w.lost(TASK);
        assertNotNull(line);
        assertTrue(line.contains("went in: "), line);
        Map<String, Integer> want = new java.util.TreeMap<>(owed);
        if (owed.get(k) == 1) want.remove(k); else want.put(k, owed.get(k) - 1);
        assertEquals(want, l.s().deposits.owed(), "only the rest is owed again, never sent twice");
    }

    /** Review 4 (point 2): the clicks' count is handed over at the clicks; a cancel in the tick before the count knows it. */
    @Test
    void aCancelBetweenTheClicksAndTheCount_owesOnlyTheRest_andALateCountIsIgnored() {
        Line l = due();
        Map<String, Integer> owed = l.s().deposits.owed();
        String k = owed.keySet().iterator().next();
        PutWatch w = new PutWatch(l.s().deposits);
        w.started(TASK, false);
        w.moved(Map.of(k, owed.get(k)));                    // DepositLeg.putOnly: the clicks, read from the bag at once
        String line = w.cancelled(TASK, "a higher desire");  // AaRuntime.cancel reads the put before the hands' cancel
        assertNotNull(line);
        assertTrue(line.contains("went in: "), line);
        w.moved(Map.of(k, 99));                              // StepHands.cancel hands it over again: the put is over, ignored
        Map<String, Integer> want = new java.util.TreeMap<>(owed);
        want.remove(k);
        assertEquals(want, l.s().deposits.owed(), "what went in is not owed again, so no second put takes it from another order");
    }

    /** Review 4 (point 3): the same task starting the put again keeps what its earlier leg moved. */
    @Test
    void theSameTaskStartsThePutAgain_theEarlierLegsCountStays() {
        Line l = due();
        Map<String, Integer> owed = l.s().deposits.owed();
        String k = owed.keySet().iterator().next();
        PutWatch w = new PutWatch(l.s().deposits);
        w.started(TASK, false);
        w.moved(Map.of(k, 1));                              // the first leg moved one, then an escape
        w.started(TASK, false);                             // the resume, the same task
        assertEquals(Map.of(k, 1), w.movedSoFar(), "kept");
        w.moved(Map.of());                                  // the second leg moved nothing yet
        String line = w.lost(TASK);
        assertTrue(line.contains("went in: "), line);
        Map<String, Integer> want = new java.util.TreeMap<>(owed);
        if (owed.get(k) == 1) want.remove(k); else want.put(k, owed.get(k) - 1);
        assertEquals(want, l.s().deposits.owed());
    }

    /**
     * Review 5 (serious): two legs in one task. The first leg's Deposit step fails after moving part; the in-run re-plan
     * reads owed() and must not ask again for that part, so the second leg's put (planned from owed) passes putDiffers and
     * moves only the rest; the chest gets each item once.
     */
    @Test
    void twoLegsInOneTask_theSecondNeverAsksForWhatTheFirstPut() {
        Line l = due();
        LineDeposits d = l.s().deposits;
        Map<String, Integer> owed = d.owed();
        String k = owed.keySet().iterator().next();
        int all = owed.get(k);
        PutWatch w = new PutWatch(d);
        int reached = d.putsReached();
        w.started(TASK, false);
        w.moved(Map.of(k, 1));                              // the clicks: one went in
        w.legEnded(false);                                       // StepHands.depositStatus: the step failed after it
        Map<String, Integer> replan = d.owed();             // Goals.chain for the deposit goal: what the re-plan asks
        Map<String, Integer> want = new java.util.TreeMap<>(owed);
        if (all == 1) want.remove(k); else want.put(k, all - 1);
        assertEquals(want, replan, "the re-plan asks only for the rest");
        assertNull(d.putDiffers(replan), "the second leg's planned put is what is owed");
        w.started(TASK, false);                             // the second leg, same task
        assertEquals(Map.of(k, 1), w.movedSoFar());
        Map<String, Integer> second = new java.util.TreeMap<>(replan);
        w.moved(second);
        w.legEnded(true);
        String line = w.ended(TASK, true, null, "ok");
        assertNotNull(line);
        assertTrue(line.contains("reached the chest"), line);
        assertTrue(d.owed().isEmpty());
        assertEquals(reached + 1, d.putsReached());
        int cobPut = l.cob().depositPut.values().stream().mapToInt(Integer::intValue).sum();
        int logPut = l.log().depositPut.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(owed.values().stream().mapToInt(Integer::intValue).sum(), cobPut + logPut, "each item once");
    }

    /** Review 5: a leg that put all of it and failed after is reached at the task's failed end. */
    @Test
    void aLegThatPutAllOfIt_isReachedWhenTheTaskFailsAtThatStep() {
        Line l = due();
        LineDeposits d = l.s().deposits;
        Map<String, Integer> owed = d.owed();
        PutWatch w = new PutWatch(d);
        w.started(TASK, false);
        w.moved(owed);
        w.legEnded(false);                                  // the step failed after its clicks
        w.ended(TASK, false, new Step.Deposit(null, owed, new int[]{0, 64, 0}), "deposit: the chest closed");
        assertTrue(d.owed().isEmpty(), "all went in: nothing owed again");
    }

    /** Review 5 (once): an order weighed again after part of it went in asks only for the rest (cobblestone 32, 10 in). */
    @Test
    void anOrderWeighedAgain_afterAPartialPut_asksOnlyForTheRest() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b9", true);
        cob.depositPut.put("cobblestone", 10);
        s.bag.put("cobblestone", 64);
        LineDeposits.Out o = s.deposits.weigh(cob, "done", s.bag, AT);
        assertEquals(Kind.DUE, o.kind(), o.text());
        assertEquals(22, o.items().values().stream().mapToInt(Integer::intValue).sum(), o.text());
    }

    /** Review 5 (serious): a split stack that stayed for want of an empty slot is a full chest; its words make the hold. */
    @Test
    void aShortPutWithASplitThatStayed_isAFullChestHold() {
        int[] c = {3, 64, -5};
        String words = io.github.mojolowjo.entropybot.aa.brain.DepositRule.shortWords(0, 32, c, "cobblestone 32",
                " (32 of a split stack stayed: no empty chest slot for it)", 0, Map.of("cobblestone", 32));
        var h = io.github.mojolowjo.entropybot.aa.brain.MainChestHold.refusal(words);
        assertNotNull(h, words);
        assertEquals(io.github.mojolowjo.entropybot.aa.brain.MainChestHold.Kind.FULL, h.kind());
        assertEquals(3, h.x());
        assertEquals(-5, h.z());
        assertTrue(h.why().contains("is full"), h.why());
    }

    /**
     * Review 6, review 7 (point 4): a short put whose chest still showed empty slots says what is true - refused N items
     * while it showed M empty slots, never "full" - and its hold is not lifted by room shown, only by the owner's word, a
     * new main chest, or the refused kind no longer owed.
     */
    @Test
    void aShortPutWithRoomShown_isHeldAsRefusing_notFull_andLiftsOnlyByWhatCanHappen() {
        int[] c = {3, 64, -5};
        String words = io.github.mojolowjo.entropybot.aa.brain.DepositRule.shortWords(10, 32, c, "cobblestone 32", "", 4, Map.of("cobblestone", 22));
        assertFalse(words.contains("full"), words);
        assertTrue(words.contains("chest at 3 64 -5 refused 22 items (cobblestone 22) while it showed 4 empty slots"), words);
        var store = new io.github.mojolowjo.entropybot.aa.brain.MainChestHold();
        String rec = store.refused("deposit: " + words);
        assertNotNull(rec);
        var h = store.hold();
        assertEquals(io.github.mojolowjo.entropybot.aa.brain.MainChestHold.Kind.REFUSED, h.kind());
        assertFalse(h.why().contains("is full"), h.why());
        assertTrue(h.why().contains("refused 22 of the last put (cobblestone 22) while it showed 4 empty slots"), h.why());
        assertEquals(java.util.Set.of("minecraft:cobblestone"), h.items());
        assertNull(store.roomSeen(3, 64, -5, 4), "room shown cannot lift it: it showed room when it refused");
        var known = new io.github.mojolowjo.entropybot.aa.brain.MainChest.Known(3, 64, -5);
        assertInstanceOf(io.github.mojolowjo.entropybot.aa.brain.MainChest.Held.class, store.apply(known, java.util.Set.of("minecraft:cobblestone")).answer(), "still owed: held");
        assertInstanceOf(io.github.mojolowjo.entropybot.aa.brain.MainChest.Held.class, store.apply(known, null).answer(), "owed not known: held");
        var lifted = store.apply(known, java.util.Set.of("minecraft:oak_log"));
        assertEquals(known, lifted.answer(), "the refused kind is no longer owed: lifted");
        assertTrue(lifted.line().contains("none of the refused items is owed any more"), lifted.line());
        // the owner's word and a new main chest lift it too
        store.refused("deposit: " + words);
        assertNotNull(store.lift("the owner set the main chest"));
        store.refused("deposit: " + words);
        assertEquals(new io.github.mojolowjo.entropybot.aa.brain.MainChest.Known(9, 64, 9), store.apply(new io.github.mojolowjo.entropybot.aa.brain.MainChest.Known(9, 64, 9), java.util.Set.of("minecraft:cobblestone")).answer());
    }

    /** Review 8: a refusal whose kinds were not read says so (never "?") and lifts when nothing is owed, not before. */
    @Test
    void aRefusalOfKindsNotRead_liftsWhenNothingIsOwed() {
        int[] c = {3, 64, -5};
        String words = io.github.mojolowjo.entropybot.aa.brain.DepositRule.refusedWords(c, 22, Map.of(), 4);
        assertFalse(words.contains("?"), words);
        assertTrue(words.contains(io.github.mojolowjo.entropybot.aa.brain.DepositRule.REFUSED_NOT_READ), words);
        var store = new io.github.mojolowjo.entropybot.aa.brain.MainChestHold();
        assertNotNull(store.refused("deposit: " + words));
        assertEquals(io.github.mojolowjo.entropybot.aa.brain.MainChestHold.Kind.REFUSED, store.hold().kind());
        assertTrue(store.hold().items().isEmpty(), String.valueOf(store.hold().items()));
        var known = new io.github.mojolowjo.entropybot.aa.brain.MainChest.Known(3, 64, -5);
        assertInstanceOf(io.github.mojolowjo.entropybot.aa.brain.MainChest.Held.class, store.apply(known, java.util.Set.of("minecraft:oak_log")).answer(), "something owed: held");
        var lifted = store.apply(known, java.util.Set.of());
        assertEquals(known, lifted.answer(), "nothing owed: lifted");
        assertTrue(lifted.line().contains("nothing is owed any more"), lifted.line());
    }

    /** Review 6 (rule 7): a gone chest recorded by the reader, then by the put's words, is one hold, said once. */
    @Test
    void theSameGoneCell_byTwoWordings_isSaidOnce() {
        var hold = new io.github.mojolowjo.entropybot.aa.brain.MainChestHold();
        assertNotNull(hold.refused("deposit: chest at 3 64 -5 gone (stone there)"));
        assertNull(hold.refused("deposit: chest at 3 64 -5 gone"));
    }

    /** Review 7 (point 3, rule 7): another task's start ends the earlier put as lost, said, with what it moved counted. */
    @Test
    void anotherTaskStartingAPut_endsTheEarlierAsLost_saidWithWhatItMoved() {
        Line l = due();
        LineDeposits d = l.s().deposits;
        Map<String, Integer> owed = d.owed();
        PutWatch w = new PutWatch(d);
        String k = owed.keySet().iterator().next();
        assertNull(w.started(TASK, false), "nothing on before: nothing to say");
        w.moved(Map.of(k, 1));
        String line = w.started(OTHER, false);
        assertNotNull(line, "the overwritten put is said");
        assertTrue(line.contains("did not reach the chest whole") && line.contains("went in: {" + k + "=1}") && line.contains("task 7"), line);
        assertTrue(w.movedSoFar().isEmpty(), "the new put's count starts afresh");
        assertEquals(OTHER, w.task());
        Map<String, Integer> want = new java.util.TreeMap<>(owed);
        if (owed.get(k) == 1) want.remove(k); else want.put(k, owed.get(k) - 1);
        assertEquals(want, d.owed(), "the one that went in is not owed again; the rest rides with the new put");
        w.legOver(d.onItsWay(), true);                      // the new put's step ended done with the rest
        assertNotNull(w.ended(OTHER, true, null, "ok"));
        assertTrue(d.owed().isEmpty());
    }

    /** Review 7 (point 3): a pending put overwritten by another new task's start is ended lost too, said. */
    @Test
    void aPendingPutOverwrittenByANewStart_isSaid() {
        Line l = due();
        PutWatch w = new PutWatch(l.s().deposits);
        w.startBegins();
        w.started(PREVIOUS, true);
        w.startBegins();                                    // another new task's start (the first one's end never read)
        String line = w.started(PREVIOUS, true);
        assertNotNull(line);
        assertTrue(line.contains("the pending put"), line);
        assertTrue(w.on());
    }

    /** Review 8: a pending put started again inside the same new task's start is the same put: nothing said lost. */
    @Test
    void aPendingPutStartedAgainInTheSameStart_isTheSamePut() {
        Line l = due();
        Map<String, Integer> owed = l.s().deposits.owed();
        PutWatch w = new PutWatch(l.s().deposits);
        w.startBegins();
        assertNull(w.started(PREVIOUS, true));
        w.moved(Map.of("cobblestone", 32));
        assertNull(w.started(PREVIOUS, true), "the same start: no lost line");
        assertEquals(Map.of("cobblestone", 32), w.movedSoFar(), "its first leg's count kept");
        w.taskStarted(TASK);
        assertEquals(TASK, w.task());
        w.legOver(l.s().deposits.onItsWay(), true);         // the step ended done (StepHands: the count of the whole put)
        String end = w.ended(TASK, true, null, "ok");
        assertTrue(end.contains("reached the chest"), end);
        assertTrue(l.s().deposits.owed().isEmpty());
        assertFalse(owed.isEmpty());
    }

    /**
     * Review 7 (point 2): a second start in one task while a remainder is on its way, after the order came due again,
     * adds the new due amount to the remainder; nothing is replaced, so the chest gets the whole amount.
     */
    @Test
    void aSecondStartInOneTask_addsWhatCameDue_toTheRemainderOnItsWay() {
        OrderLineSim s = new OrderLineSim();
        LineDeposits d = s.deposits;
        Goals.Goal cob = s.order("cobblestone", 32, "b2", true);
        s.bag.put("cobblestone", 20);
        cob.parked = "the explore from here froze";
        assertEquals(Kind.DUE, d.sweep(cob, s.bag, AT).kind());     // stopped with 20: the line's deposit of 20
        assertEquals(Map.of("minecraft:cobblestone", 20), d.owed());
        PutWatch w = new PutWatch(d);
        int chest = 0;
        w.started(TASK, false);
        w.moved(Map.of("cobblestone", 10));                 // the first leg: 10 went in, 10 still on its way
        w.legEnded(false);
        chest += 10;
        s.bag.put("cobblestone", 10);
        // the order wakes while its remainder is on its way, gathers the rest and is met: its line comes due again
        cob.parked = null;
        assertEquals(Kind.WOKE_OPEN, d.reopen(cob).kind());
        s.bag.put("cobblestone", 22);
        LineDeposits.Out again = d.weigh(cob, "done", s.bag, AT);
        assertEquals(Kind.DUE, again.kind(), again.text());
        assertEquals(Map.of("cobblestone", 12), again.items(), "due again: the 12 the line had not taken");
        w.started(TASK, false);                             // the second leg in the same task
        assertEquals(Map.of("minecraft:cobblestone", 22), d.owed(), "the remainder 10 and the 12 due again, added, nothing replaced");
        w.moved(Map.of("cobblestone", 22));
        w.legEnded(true);
        chest += 22;
        assertNotNull(w.ended(TASK, true, null, "ok"));
        assertTrue(d.owed().isEmpty());
        assertEquals(32, chest, "the -d amount reached the chest whole, once");
        int noted = cob.depositPut.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(0, Math.max(0, cob.deposit - noted), "nothing of the order is asked again (amount " + cob.deposit + ", in the chest for it " + noted + ")");
    }

    /** Review 8: cobblestone 32 -d stopped with 20, a put of it on its way, 10 in; the order wakes (its amount cut to 12). */
    record Woken(OrderLineSim s, LineDeposits d, Goals.Goal cob, PutWatch w) {}

    static Woken wokenWithTenOnItsWay() {
        OrderLineSim s = new OrderLineSim();
        LineDeposits d = s.deposits;
        Goals.Goal cob = s.order("cobblestone", 32, "b3", true);
        s.bag.put("cobblestone", 20);
        cob.parked = "the explore from here froze";
        assertEquals(Kind.DUE, d.sweep(cob, s.bag, AT).kind());
        PutWatch w = new PutWatch(d);
        w.started(TASK, false);
        w.moved(Map.of("cobblestone", 10));
        w.legEnded(false);
        s.bag.put("cobblestone", 10);
        cob.parked = null;
        assertEquals(Kind.WOKE_OPEN, d.reopen(cob).kind());
        assertEquals(12, cob.deposit, "the line took 20 of it: 10 in the chest, 10 on its way");
        return new Woken(s, d, cob, w);
    }

    /**
     * Review 8 (serious): the wake, then the put's task lost, then the order's re-end. The 10 that was on its way is the
     * order's own again (its amount back to 22, nothing of it left in due to be replaced or taken back), so the re-end
     * owes 22 and the chest gets 32.
     */
    @Test
    void aWakeThenALostPut_thenTheReEnd_putsTheWholeAmount() {
        Woken k = wokenWithTenOnItsWay();
        assertNotNull(k.w().lost(TASK));
        assertEquals(22, k.cob().deposit, "the 10 that did not go in is back on its amount");
        assertTrue(k.d().owed().isEmpty(), "nothing of it is left in due (a re-end or a take-back cannot replace or drop it)");
        k.s().bag.put("cobblestone", 22);
        LineDeposits.Out again = k.d().weigh(k.cob(), "done", k.s().bag, AT);
        assertEquals(Kind.DUE, again.kind(), again.text());
        assertEquals(Map.of("minecraft:cobblestone", 22), k.d().owed());
        k.w().started(OTHER, false);
        k.w().legOver(Map.of("cobblestone", 22), true);
        assertNotNull(k.w().ended(OTHER, true, null, "ok"));
        assertTrue(k.d().owed().isEmpty());
        assertEquals(32, 10 + 22, "the -d amount reached the chest whole, once");
    }

    /** Review 8: the wake, the re-end (12 due), then the put lost: the 10 and the 12 are owed together, the amount 22. */
    @Test
    void aWakeThenTheReEnd_thenALostPut_owesBothParts() {
        Woken k = wokenWithTenOnItsWay();
        k.s().bag.put("cobblestone", 22);
        assertEquals(Kind.DUE, k.d().weigh(k.cob(), "done", k.s().bag, AT).kind());
        assertNotNull(k.w().lost(TASK));
        assertEquals(Map.of("minecraft:cobblestone", 22), k.d().owed(), "the 10 back beside the 12, added");
        assertEquals(22, k.cob().deposit);
        k.w().started(OTHER, false);
        k.w().legOver(Map.of("cobblestone", 22), true);
        assertNotNull(k.w().ended(OTHER, true, null, "ok"));
        assertTrue(k.d().owed().isEmpty());
        // a later wake of it (the line's deposit went) takes what went off its amount: nothing more is asked
        k.cob().parked = null;
        LineDeposits.Out late = k.d().reopen(k.cob());
        assertEquals(Kind.WOKE_GONE, late.kind(), late.text());
    }

    @Test
    void aPutWhoseTaskIsGoneWithNoEndRead_endsOnTheNet() {
        Line l = due();
        Map<String, Integer> owed = l.s().deposits.owed();
        PutWatch w = new PutWatch(l.s().deposits);
        w.started(TASK, false);                             // a re-plan inside task 7 (its id known)
        assertNull(w.idle(TASK, false), "its own task is current");
        assertNull(w.idle(OTHER, true), "something still runs");
        String line = w.idle(OTHER, false);
        assertNotNull(line);
        assertEquals(owed, l.s().deposits.owed());
        assertNull(w.idle(OTHER, false), "read once");
    }

    @Test
    void aStartRefusedAfterThePutBegan_isNotReached() {
        Line l = due();
        Map<String, Integer> owed = l.s().deposits.owed();
        PutWatch w = new PutWatch(l.s().deposits);
        w.started(PREVIOUS, true);
        String line = w.startRefused("failed at step 1: deposit: chest at 0 64 0 gone");
        assertNotNull(line);
        assertFalse(w.on());
        assertEquals(owed, l.s().deposits.owed());
        assertNull(w.startRefused("again"), "read once");
    }

    @Test
    void aFailedPutStep_isNotReached_aStepAfterIt_is() {
        Line l = due();
        PutWatch w = new PutWatch(l.s().deposits);
        w.started(TASK, false);
        Step put = new Step.Deposit(null, l.s().deposits.owed(), new int[]{0, 64, 0});
        w.ended(TASK, false, put, "deposit: the chest closed");
        assertFalse(l.s().deposits.owed().isEmpty(), "failed at the put itself: owed again");
        w.started(TASK, false);
        w.legOver(l.s().deposits.owed(), true);             // the put step ended done, a later step failed
        w.ended(TASK, false, new Step.Craft("torch", 4, false), "craft failed");
        assertTrue(l.s().deposits.owed().isEmpty(), "failed after the put: the put reached the chest");
    }

    /** The runtime's goals-pass hooks with nothing to say (the deposit goal's sync is what is read). */
    static final io.github.mojolowjo.entropybot.aa.brain.OrderEnds.Hooks QUIET = new io.github.mojolowjo.entropybot.aa.brain.OrderEnds.Hooks() {
        @Override public boolean carriesOn(Goals.Goal g) { return false; }
        @Override public void got(String msg) { }
        @Override public void deposit(LineDeposits.Out out) { }
    };

    static void sync(OrderLineSim s) { io.github.mojolowjo.entropybot.aa.brain.OrderEnds.depositSync(s.bb, s.brain, s.deposits, QUIET); }

    /**
     * Merge review 2026-11-25 (1a, serious): the put stalls (its Deposit step fails on a no-progress fact, nothing moved),
     * the in-run re-plan holds the chest and offers the walk up, and the task ends done on [ToSurface]. The put did not
     * reach the chest: the 32 stay owed, the deposit goal stays, nothing counts as in the chest.
     */
    @Test
    void aRunEndingDoneOnAWalkUpAfterAStalledPut_isNotReached() {
        OrderLineSim s = new OrderLineSim();
        LineDeposits d = s.deposits;
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true);
        s.bag.put("cobblestone", 32);
        assertEquals(Kind.DUE, d.weigh(cob, "done", s.bag, AT).kind());
        sync(s);
        assertNotNull(s.depositGoal(), "the line's deposit is a goal");
        Map<String, Integer> owed = d.owed();
        int reached = d.putsReached();
        PutWatch w = new PutWatch(d);
        w.started(TASK, false);                             // [ToStation, Deposit]: the Deposit starts
        w.legOver(Map.of(), false);                         // StepHands.depositStatus: the step failed, nothing moved
        String line = w.ended(TASK, true, null, "ok: on the surface");   // the re-plan's [ToSurface] ended done
        assertNotNull(line);
        assertTrue(line.contains("did not reach the chest whole") && line.contains("did not finish"), line);
        assertEquals(owed, d.owed(), "still owed, and kept");
        assertEquals(reached, d.putsReached());
        sync(s);
        assertNotNull(s.depositGoal(), "the deposit goal stays (never 'in the chest')");
    }

    /**
     * Merge review 2026-11-25 (1b, serious): leg 1 fails and moves nothing; during the re-plan's walk another line comes
     * due, so the second Deposit start finds the owed amount changed and ends "ok: no put", and the task ends done. Both
     * lines stay owed: the 32 cobblestone are never dropped from the way.
     */
    @Test
    void aRunEndingDoneOnANoPutStep_isNotReached_bothLinesStayOwed() {
        OrderLineSim s = new OrderLineSim();
        LineDeposits d = s.deposits;
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true);
        s.bag.put("cobblestone", 32);
        assertEquals(Kind.DUE, d.weigh(cob, "done", s.bag, AT).kind());
        Map<String, Integer> planned = d.owed();
        PutWatch w = new PutWatch(d);
        w.started(TASK, false);
        w.legOver(Map.of(), false);                         // leg 1 failed with a fact, nothing moved
        Goals.Goal log = s.order("oak_log", 4, "b2", true);
        s.bag.put("oak_log", 4);
        assertEquals(Kind.DUE, d.weigh(log, "done", s.bag, AT).kind(), "another line comes due during the walk");
        assertNotNull(d.putDiffers(planned), "the second Deposit start: owed changed, 'ok: no put'");
        assertNotNull(w.ended(TASK, true, null, "ok: no put"));
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 4), d.owed(), "both owed, nothing dropped");
        sync(s);
        assertNotNull(s.depositGoal());
    }

    /**
     * Merge review 2026-11-25 (14, serious): the line owes 32 and the bag holds 64. The leg clicks 32 in; before its count a
     * pause cancels the hands (StepHands.cancel's hand-over ends the leg there); the resume's put finds nothing owed and
     * puts nothing; the task's end counts the 32 reached. The chest gets 32, once.
     */
    @Test
    void aPauseBetweenTheClicksAndTheCount_theResumeNeverPutsItAgain() {
        OrderLineSim s = new OrderLineSim();
        LineDeposits d = s.deposits;
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true);
        s.bag.put("cobblestone", 64);
        assertEquals(Kind.DUE, d.weigh(cob, "done", s.bag, AT).kind());
        Map<String, Integer> only = d.owed();               // the plan's Deposit(only)
        assertEquals(Map.of("minecraft:cobblestone", 32), only);
        PutWatch w = new PutWatch(d);
        w.started(TASK, false);
        w.moved(Map.of("cobblestone", 32));                 // DepositLeg.putOnly: the clicks
        w.legOver(Map.of("cobblestone", 32), false);        // the pause: StepHands.cancel's hand-over
        assertNotNull(d.putDiffers(only), "the resume of the same Deposit step finds nothing owed: no second put");
        String end = w.ended(TASK, true, null, "ok: no put");
        assertTrue(end.contains("reached the chest"), end);
        assertTrue(d.owed().isEmpty());
        assertEquals(32, cob.depositPut.values().stream().mapToInt(Integer::intValue).sum(), "32 in the chest for it, once");
    }

    /** Merge review 2026-11-25 (14): the hands' cancel and the step's end hand the leg over the one way (PutWatch.legOver). */
    @Test
    void theHandsHandTheLegOverTheOneWay() throws java.io.IOException {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        assertFalse(src.contains("putMoved("), "no hand-over of the count alone: the leg ends with it");
        assertEquals(2, src.split("putLegOver\\(", -1).length - 1, "the cancel and the step's end");
        assertTrue(src.contains("putLegOver(deposit.putItems(), false)"), "the cancel ends the leg not done");
    }

    @Test
    void theOwnersCancel_endsAPutOfAnyTask() {
        Line l = due();
        PutWatch w = new PutWatch(l.s().deposits);
        assertNull(w.dropped("aa cancel deposit"), "no put on: nothing");
        w.started(PREVIOUS, true);
        assertNotNull(w.dropped("aa cancel deposit"));
        assertFalse(w.on());
    }
}
