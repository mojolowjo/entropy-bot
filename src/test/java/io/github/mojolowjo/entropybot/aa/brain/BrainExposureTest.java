package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaDesires;
import io.github.mojolowjo.entropybot.aa.AaLadder;
import io.github.mojolowjo.entropybot.aa.Need;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND2 3+4 in the brain: exposure x danger, gates as prerequisites, the brain's own cooldown for idle desires. */
class BrainExposureTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    Desire d(String id, Desire.Kind k, double v, Desire.Exposure e) {
        return new Desire(id, k, RunJob.of(id + ".job", TaskKind.WAIT, java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Plan("do " + id)), "brain"), b -> v).cost(0).exposure(e);
    }

    void night(int difficulty) {
        bb.in.dayTime = 18000;
        bb.in.hungerDrains = true;
        bb.in.difficulty = difficulty;
    }

    @Test
    void aSurfaceWantHalvesAtDangerOneAndUnderWorkGains() {
        assertEquals(0.5, Brain.exposureFactor(Desire.Exposure.SURFACE, 1), 1e-9);
        assertEquals(1.5, Brain.exposureFactor(Desire.Exposure.UNDER, 1), 1e-9);
        Brain br = new Brain("brain", "t", d("explore", Desire.Kind.WANT, 20, Desire.Exposure.SURFACE),
                d("stone", Desire.Kind.UPKEEP, 16, Desire.Exposure.UNDER), d("tidy", Desire.Kind.WANT, 18, Desire.Exposure.ANY));
        bb.in.dayTime = 6000;
        bb.in.hungerDrains = true;
        assertEquals(Status.RUNNING, br.tick(bb));
        assertEquals("plan do explore", port.started.get(0).chain(), "day: no change");
        br.halt("test");
        night(2);
        bb.in.underDepth = 5;          // review 2 item 1: ANY work at the surface counts as SURFACE; here it is under ground
        double danger = Brain.danger(bb);
        var s = br.scoreAll(bb);
        assertEquals(20 / (1 + danger), s.get(0).score(), 1e-9);
        assertEquals(16 * (1 + danger / 2), s.get(1).score(), 1e-9);
        assertEquals(18 * (1 + danger / 2), s.get(2).score(), 1e-9, "AA_ROUND4 B1: no site, no label: where the bot is (under ground)");
        assertTrue(s.get(0).parts().contains("night x"), s.get(0).parts());
        assertTrue(s.get(2).score() > s.get(1).score() && s.get(1).score() > s.get(0).score(), "at night under ground: the work here, then the surface");
    }

    @Test
    void peacefulIsUnchanged() {
        Brain br = new Brain("brain", "t", d("explore", Desire.Kind.WANT, 20, Desire.Exposure.SURFACE), d("stone", Desire.Kind.UPKEEP, 16, Desire.Exposure.UNDER));
        night(2);
        bb.in.hungerDrains = false;
        var s = br.scoreAll(bb);
        assertEquals(20, s.get(0).score(), 1e-9);
        assertEquals(16, s.get(1).score(), 1e-9);
    }

    @Test
    void aSelfCoolingDesireRestsAfterANoRunFailureAndCoolsAfterARealOne() {
        RunJob none = new RunJob("w.job", TaskKind.STOCK_WOOD, b -> null);
        Desire w = new Desire("idle.wood", Desire.Kind.UPKEEP, none, b -> 20).cools(30_000, 30_000).lastTry();
        Desire wait = new Desire("idle.wait", Desire.Kind.UPKEEP, RunJob.of("wait", TaskKind.WAIT, java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Plan("wait 60")), "idle"), b -> 1).lastTry();
        Brain br = new Brain("brain", "t", w, wait);
        assertEquals(Status.FAILURE, br.tick(bb));
        assertTrue(br.failures.held("idle.wood", null, clock.now()) != null, "rests 30 s");
        assertEquals(0, br.streak("idle.wood"), "no failure counted");
        assertEquals(Status.RUNNING, br.tick(bb));
        assertEquals("plan wait 60", port.started.get(0).chain(), "the next option gets its turn");
        assertTrue(br.heldLines(clock.now()).get(0).startsWith("held: idle.wood "), br.heldLines(clock.now()).toString());
    }
}

