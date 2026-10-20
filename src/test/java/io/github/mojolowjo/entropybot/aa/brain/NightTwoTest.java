package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.BuildProgress;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** aa-night2 P0 (normal-2026-10-20): gone reasons on the own predicate, no fight unarmed, the sword, a build's progress. */
class NightTwoTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @Test
    void aGateWhoseFlowDriesUpKeepsRunningUntilItsOwnPredicate() {
        boolean[] met = {false};
        double[] v = {40};
        Brain br = new Brain("brain", "t",
                new Desire("needs.surface", Desire.Kind.GATE, RunJob.of("s.job", TaskKind.TABLE_FURNACE, "climb", "brain"), b -> v[0]).met(b -> met[0]));
        br.tick(bb);
        assertEquals(1, port.started.size());
        v[0] = -1;                          // the flow dried up: "needs.surface wanted -1"
        clock.advance(1_000);
        br.tick(bb);
        assertTrue(port.cancelled.isEmpty(), "not a gone reason");
        met[0] = true;
        clock.advance(1_000);
        br.tick(bb);
        assertFalse(port.cancelled.isEmpty(), "climbed: done");
    }

    @Test
    void reasonGoneWithoutPredicateAsBefore() {
        Desire d = new Desire("x", Desire.Kind.UPKEEP, RunJob.of("x.job", TaskKind.WAIT, "x", "brain"), b -> 0);
        assertTrue(Brain.reasonGone(new Brain.Scored(d, -1, -1, "", "not wanted", -1, 0), null));
        assertFalse(Brain.reasonGone(new Brain.Scored(d, -1, -1, "", "not wanted", -1, 0), false));
    }


    @Test
    void swordOnceAZombieWasSeen() {
        assertEquals(0, Costs.sword(false, false));
        assertEquals(Costs.SWORD, Costs.sword(true, false));
        assertEquals(0, Costs.sword(true, true));
    }

    @Test
    void aBuildSpendingBlocksIsProgress() {
        assertTrue(BuildProgress.counts(TaskKind.BASE_BED));
        assertFalse(BuildProgress.counts(TaskKind.DEPOSIT));
        assertFalse(BuildProgress.counts(null));
    }
}
