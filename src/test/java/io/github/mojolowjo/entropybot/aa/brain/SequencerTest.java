package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND3 2: the sequencer (pure) and the brain running the plan's next. */
class SequencerTest {
    static final Site HERE = Site.at(0, 64, 0), IRON = Site.at(100, 20, 0);

    @Test
    void coalAtTheIronsCaveIsInserted() {
        Sequencer.Plan p = Sequencer.build(HERE, List.of(new Sequencer.Item("iron", 200, IRON), new Sequencer.Item("coal", 30, Site.at(105, 20, 0))), 600);
        assertEquals(List.of("iron", "coal"), p.steps().stream().map(Sequencer.Step::id).toList());
        assertEquals("same place", p.steps().get(1).how());
        assertTrue(p.steps().get(1).savedS() >= 0.15 * 30, p.text());
        assertTrue(p.text().startsWith("iron (at 100 20 0) then coal (same place, saves "), p.text());
    }

    @Test
    void coalFarAwayIsNot() {
        Sequencer.Plan p = Sequencer.build(HERE, List.of(new Sequencer.Item("iron", 200, IRON), new Sequencer.Item("coal", 50, Site.at(0, 64, 200))), 600);
        assertEquals(1, p.steps().size(), p.text());
    }

    @Test
    void aPlanNeverExceedsThree() {
        List<Sequencer.Item> items = List.of(new Sequencer.Item("a", 200, IRON), new Sequencer.Item("b", 30, Site.at(101, 20, 0)),
                new Sequencer.Item("c", 30, Site.at(102, 20, 0)), new Sequencer.Item("d", 30, Site.at(103, 20, 0)), new Sequencer.Item("e", 30, Site.at(104, 20, 0)));
        assertEquals(3, Sequencer.build(HERE, items, 600).steps().size());
    }

    @Test
    void pastTheHorizonIsNotSequenced() {
        Sequencer.Plan p = Sequencer.build(HERE, List.of(new Sequencer.Item("iron", 200, IRON), new Sequencer.Item("coal", 30, Site.at(105, 20, 0))), 20);
        assertEquals(1, p.steps().size(), p.text());
    }

    @Test
    void noSiteNoInsertAndNoWinnerSiteNoPlan() {
        assertEquals(1, Sequencer.build(HERE, List.of(new Sequencer.Item("iron", 200, IRON), new Sequencer.Item("x", 30, null)), 600).steps().size());
        Sequencer.Plan p = Sequencer.build(HERE, List.of(new Sequencer.Item("x", 30, null), new Sequencer.Item("iron", 200, IRON)), 600);
        assertEquals(1, p.steps().size());
        assertTrue(p.text().contains("no site"), p.text());
    }

    @Test
    void otherDimensionIsNeverNear() {
        assertFalse(new Site("minecraft:the_nether", 0, 64, 0, 0).near(new Site("minecraft:overworld", 0, 64, 0, 0)));
        assertTrue(Double.isInfinite(new Site("a", 0, 0, 0, 0).travelS(new Site("b", 0, 0, 0, 0))));
    }

    // ---- the brain ----

    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());
    final double[] s = new double[3];

    Desire d(int i, String id, double cost, Site site) {
        Desire x = new Desire(id, Desire.Kind.WANT, RunJob.of(id + ".job", TaskKind.WAIT, "do " + id, "brain"), b -> s[i]).cost(cost);
        return site == null ? x : x.site(b -> site);
    }

    String last() { return port.started.get(port.started.size() - 1).chain(); }

    Brain brain() {
        bb.in.pos = new int[]{0, 64, 0};
        s[0] = 40;
        s[1] = 35;
        s[2] = 30;
        return new Brain("brain", "t", d(0, "iron", 200, IRON), d(1, "other", 100, null), d(2, "coal", 30, Site.at(105, 20, 0)));
    }

    @Test
    void thePlansNextRunsWhileItsSiteIsNear() {
        Brain br = brain();
        assertEquals(Status.RUNNING, br.tick(bb));
        assertEquals("do iron", last());
        assertTrue(br.planText().contains("iron (at 100 20 0) then coal"), br.planText());
        bb.in.pos = new int[]{100, 20, 0};              // at the cave
        port.done(1, "done");
        br.tick(bb);
        br.tick(bb);
        assertEquals("do coal", last(), "coal is the plan's next though other scores higher");
    }

    @Test
    void aNextWhoseSiteMovedAwayFallsBackToTheNormalPick() {
        Brain br = brain();
        br.tick(bb);
        bb.in.pos = new int[]{0, 64, 300};              // somewhere else when iron ends
        s[0] = -1;                                      // iron met
        port.done(1, "done");
        br.tick(bb);
        br.tick(bb);
        assertEquals("do other", last());
        assertTrue(br.planText().contains("dropped"), br.planText());
    }

    @Test
    void theOwnersAaDoIsNotHeldByTheBrainsCooling() {
        Brain br = new Brain("brain", "t", d(0, "needs.surface", 0, null), d(1, "idle", 0, null));
        s[0] = 100;
        s[1] = 20;
        br.failures.failed("needs.surface", null, "stuck", clock.now(), bb.in);
        br.tick(bb);
        assertEquals("do needs.surface", last());
    }
}
