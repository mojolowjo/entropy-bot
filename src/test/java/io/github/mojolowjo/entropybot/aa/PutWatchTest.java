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
        w.ended(TASK, false, new Step.Craft("torch", 4, false), "craft failed");
        assertTrue(l.s().deposits.owed().isEmpty(), "failed after the put: the put reached the chest");
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
