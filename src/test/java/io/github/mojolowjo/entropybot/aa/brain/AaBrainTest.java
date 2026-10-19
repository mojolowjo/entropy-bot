package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** BRAIN_PLAN B1: the scored list: pick order, commitment, interrupts, the stuck watchdog. */
class AaBrainTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Tracer tracer = new Tracer();
    final Blackboard bb = new Blackboard(clock, port, tracer);
    final double[] s = new double[4];

    Desire d(int i, String id, Desire.Kind k) {
        return new Desire(id, k, RunJob.of(id + ".job", TaskKind.WAIT, "do " + id, "brain"), b -> s[i]).cost(0);
    }

    String last() { return port.started.get(port.started.size() - 1).chain(); }

    @Test
    void highestRunsAndACloseChallengerWaits() {
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.PROGRESS), d(1, "b", Desire.Kind.UPKEEP));
        s[0] = 40;
        s[1] = 30;
        assertEquals(Status.RUNNING, br.tick(bb));
        assertEquals("do a", last());
        s[1] = 70;                       // not urgent: waits however high
        for (int i = 0; i < 5; i++) br.tick(bb);
        assertEquals(1, port.started.size());
        assertEquals(0, br.interrupts);
        port.done(1, "done");
        br.tick(bb);
        br.tick(bb);
        assertEquals("do b", last());
    }

    @Test
    void urgentUpkeepInterruptsAndIsNotPointless() {
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.PROGRESS), d(1, "food", Desire.Kind.UPKEEP));
        s[0] = 40;
        br.tick(bb);
        s[1] = 80;
        br.tick(bb);
        assertEquals("do food", last());
        assertEquals(1, br.interrupts);
        assertEquals(0, tracer.counts.pointless, tracer.lastSwitchText(clock.now()));
    }

    @Test
    void urgentUnderTheMarginStillWaits() {
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.UPKEEP), d(1, "b", Desire.Kind.UPKEEP));
        s[0] = 70;
        br.tick(bb);
        s[1] = 80;                       // 80 <= 70 + 15
        br.tick(bb);
        assertEquals(1, port.started.size());
    }

    @Test
    void ownerGoalWaitsTheMinimumRunAndNeverBeatsUrgentUpkeep() {
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.PROGRESS), d(1, "food", Desire.Kind.UPKEEP));
        s[0] = 40;
        br.tick(bb);
        Desire goal = d(2, "osmium", Desire.Kind.OWNER).fresh(clock.now() + 600_000);
        s[2] = 90;
        br.add(goal);
        br.tick(bb);
        assertEquals(1, port.started.size(), "under the 60 s minimum run");
        for (int i = 0; i < Brain.MIN_RUN_MS / 1000; i++) {      // ticked all along (a gap would be paused time)
            clock.advance(1000);
            port.progress.put(1L, (long) i);
            br.tick(bb);
        }
        assertEquals("do osmium", last());
        s[1] = 76;                       // urgent upkeep beats the owner's 90
        br.tick(bb);
        assertEquals("do food", last());
        // with nothing running, urgent upkeep still ranks over the owner's goal
        port.done(3, "done");
        br.tick(bb);
        br.tick(bb);
        assertEquals("do food", last());
    }

    @Test
    void stuckWatchdogHaltsCoolsDownAndTheNextRuns() {
        // a node that answers RUNNING with no task (a planner wait that never ends): only the brain's watchdog sees it
        io.github.mojolowjo.entropybot.aa.core.Node hang = new io.github.mojolowjo.entropybot.aa.core.Node("a.hang", "hangs") {
            @Override protected Status run(Blackboard b) { return Status.RUNNING; }
        };
        Brain br = new Brain("brain", "t", new Desire("a", Desire.Kind.PROGRESS, hang, b -> s[0]), d(1, "b", Desire.Kind.PROGRESS));
        s[0] = 40;
        s[1] = 20;
        br.tick(bb);
        for (int i = 0; i < 180; i++) {
            clock.advance(1000);
            br.tick(bb);
        }
        clock.advance(1000);
        assertEquals(Status.FAILURE, br.tick(bb));
        assertTrue(br.reason().startsWith("stuck: a"), br.reason());
        assertEquals(1, br.stuck);
        assertEquals(1, port.incidents.size());
        assertEquals(1, br.streak("a"));
        br.tick(bb);
        assertEquals("do b", last(), "a cools down; b runs");
        assertEquals(0, tracer.counts.pointless);
    }

    @Test
    void progressKeepsTheWatchdogQuiet() {
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.PROGRESS));
        s[0] = 40;
        br.tick(bb);
        for (int i = 0; i < 400; i++) {
            clock.advance(1000);
            bb.in.pos = new int[]{i, 64, 0};
            port.progress.put(1L, (long) i);
            assertEquals(Status.RUNNING, br.tick(bb));
        }
        assertEquals(0, br.stuck);
    }

    /** B2 (2.2): a blocked goal lifts its prerequisite to 90 % of its own score; interrupts ignore the lift. */
    @Test
    void valueFlowsToThePrerequisite() {
        Brain br = new Brain("brain", "t", d(0, "iron", Desire.Kind.PROGRESS), d(1, "osmium", Desire.Kind.OWNER).prereq(b -> "iron"),
                d(2, "wood", Desire.Kind.UPKEEP));
        s[0] = 20;
        s[1] = 80;
        s[2] = 50;
        var all = br.scoreAll(bb);
        assertEquals(72, all.get(0).score(), 1e-9, all.get(0).parts());
        assertEquals(20, all.get(0).own(), 1e-9);
        assertTrue(all.get(0).parts().contains("osmium"), all.get(0).parts());
        s[0] = 75;
        assertEquals(75, br.scoreAll(bb).get(0).score(), 1e-9, "already worth more: no lift");
    }

    /** AA_ROUND2 6: a modded item in the bag is progress, and its rise clears a NO_ITEM failure. */
    @Test
    void aModdedItemInTheBagIsProgressAndClearsNoItem() {
        // a node with no task (only the brain's watchdog looks at it)
        io.github.mojolowjo.entropybot.aa.core.Node busy = new io.github.mojolowjo.entropybot.aa.core.Node("a.busy", "busy") {
            @Override protected Status run(Blackboard b) { return Status.RUNNING; }
        };
        Brain br = new Brain("brain", "t", new Desire("a", Desire.Kind.PROGRESS, busy, b -> s[0]));
        s[0] = 40;
        bb.in.pos = new int[]{0, 64, 0};
        br.tick(bb);
        for (int i = 0; i < 400; i++) {
            clock.advance(1000);
            bb.in.bag = java.util.Map.of("osmium_ore", i);       // only the modded count moves
            assertEquals(Status.RUNNING, br.tick(bb));
        }
        assertEquals(0, br.stuck);
        var m = new io.github.mojolowjo.entropybot.aa.Options.OptionMemory();
        bb.in.bag = java.util.Map.of("dirt", 3);
        m.failed("k", "I have no osmium", clock.now(), bb.in);
        assertTrue(m.blocked("k", clock.now()));
        bb.in.bag = java.util.Map.of("dirt", 3, "osmium_ingot", 1);
        assertEquals(1, m.sweep(bb.in).size());
        assertFalse(m.blocked("k", clock.now()));
    }

    @Test
    void failuresInARowLowerP() {
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.PROGRESS));
        s[0] = 40;
        for (int k = 0; k < 2; k++) {
            br.tick(bb);
            port.fail(port.nextId - 1, "error: no");
            br.tick(bb);
        }
        assertEquals(2, br.streak("a"));
        assertEquals(20, br.scoreAll(bb).get(0).score(), 1e-9, "P 0.5");
        assertEquals(2, br.stats.n("a"));
    }

    @Test
    void whyLinesShowScores() {
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.PROGRESS), d(1, "b", Desire.Kind.UPKEEP));
        s[0] = 40;
        s[1] = -1;
        br.tick(bb);
        assertTrue(br.whyLines(5).get(0).startsWith("a PROGRESS 40"), br.whyLines(5).toString());
        assertTrue(br.whyLines(5).get(1).contains("not wanted"), br.whyLines(5).toString());
    }
}
