package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 stand (run CS 19:33:38-19:34:08): the stand waited on one walk (the descent to y 16); its answer landed at 5.8 s
 * ("unreachable", 0 walks computing) and the stand still stood out its 30 s before the order was priced again - ~24 s lost
 * per order, five orders in a row. A stand ends the moment the answer it waits for lands, and the order is priced again.
 * Hands: Z76StandTest's (the hands' own StandWait on the game clock, 20 ticks a second).
 */
class Z77StandLandsTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final int TPS = 20;      // game fact: 20 game ticks a second
    static final String LANDED = "walk to 12 0 4: UNREACHABLE (no path: Baritone's search made no progress (no progress))";

    /** The scenario: a stand on one walk; that walk's answer lands at 3 s, the order's price then has no stand (no way now). */
    @Test
    void theAnswerLandsAt3s_theStandEndsThenAndTheOrderIsPricedAgain() {
        Z76StandTest.FeetHands h = new Z76StandTest.FeetHands();
        Steps run = new Steps(h);
        List<FactChange.Fact> asked = new ArrayList<>();
        Step walk = new Step.Walk(30, 64, 0, 0);
        run.start("goal.iron", List.of(new Step.AwaitPaths(30)), 0, (failed, fact, why) -> {
            asked.add(fact);
            // the landing's re-price: no way now (the walk the stand waited on is unreachable); the stand's end: the next way
            return fact.text().equals(LANDED) ? new Steps.Routine(null, "playbook: descent to y 16: unreachable") : new Steps.Routine(List.of(walk), null);
        });
        Z76StandTest.tick(run, h, 3 * TPS);
        assertTrue(asked.isEmpty(), "nothing landed yet: the stand stands");
        run.repriceAtNextStep(FactChange.priced(LANDED));   // the walk's answer lands at 3 s
        Z76StandTest.tick(run, h, 1);
        assertEquals(2, asked.size(), "the landing priced the order, and the stand's end priced it again: " + asked);
        assertTrue(asked.get(1).price(), "the stand's end is a price fact (no try, no park): " + asked.get(1));
        assertTrue(asked.get(1).text().contains("the walks' answer landed (" + LANDED + ")"), asked.get(1).text());
        assertTrue(asked.get(1).text().contains("the stand ends now, the order is priced again"), asked.get(1).text());
        assertEquals(List.of("stand while paths compute (up to 30 s)", walk.text()), h.started, "the next way starts at 3 s, not at 30 s");
        assertFalse(h.stand.standing(), "the stand's clock ended (the hands' cancel)");
        assertTrue(run.running());
    }

    /** CS itself: the stand's end re-plan finds no way either - the order ends at 3 s with both reasons, not after 30 s. */
    @Test
    void noWayAfterTheLanding_theRunEndsAtTheLandingWithItsReason() {
        Z76StandTest.FeetHands h = new Z76StandTest.FeetHands();
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.AwaitPaths(30)), 0,
                (failed, fact, why) -> new Steps.Routine(null, "playbook: descent to y 16: unreachable"));
        Z76StandTest.tick(run, h, 3 * TPS);
        run.repriceAtNextStep(FactChange.priced(LANDED));
        Z76StandTest.tick(run, h, 1);
        assertFalse(run.running(), "ended at the landing: " + run.why());
        assertTrue(run.why().contains("the walks' answer landed"), run.why());
        assertTrue(run.why().contains("descent to y 16: unreachable"), run.why());
        assertEquals(1, h.started.size(), "no second stand: " + h.started);
    }

    /** A landing whose price still stands on a walk (another walk computing), or a seen source still waiting: the stand stands on. */
    @Test
    void aLandingThatStillStands_theStandRunsOn() {
        Z76StandTest.FeetHands h = new Z76StandTest.FeetHands();
        Steps run = new Steps(h);
        List<FactChange.Fact> asked = new ArrayList<>();
        run.start("goal.iron", List.of(new Step.AwaitPaths(30)), 0, (failed, fact, why) -> {
            asked.add(fact);
            if (fact.text().equals("one of two landed")) return new Steps.Routine(List.of(new Step.AwaitPaths(30)), null);
            return new Steps.Routine(null, FactChange.SEEN_WAITS + "seen iron at 20 28 19");
        });
        Z76StandTest.tick(run, h, 3 * TPS);
        run.repriceAtNextStep(FactChange.priced("one of two landed"));
        Z76StandTest.tick(run, h, 1);
        run.repriceAtNextStep(FactChange.seen("iron", 20, 28, 19, "seen iron at 20 28 19"));
        Z76StandTest.tick(run, h, 1);
        assertEquals(2, asked.size(), asked.toString());
        assertTrue(run.running());
        assertEquals(List.of("stand while paths compute (up to 30 s)"), h.started, "the stand runs on");
        assertTrue(h.stand.standing());
    }
}
