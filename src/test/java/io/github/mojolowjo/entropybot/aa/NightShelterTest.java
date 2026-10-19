package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Desire;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Live aa377: at full night on the surface the shelter goes over the urgent line (after a step's grace). */
class NightShelterTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @Test
    void fullNightSurfaceIsUrgentHigherEarlyAndOffInPeacefulOrUnderground() {
        bb.in.hungerDrains = true;
        bb.in.dayTime = 18000;
        assertEquals(90, AaDesires.shelterScore(bb), 1e-9, "no armour, no bed");
        bb.in.armorPoints = 8;
        bb.in.bedKnown = true;
        assertEquals(80, AaDesires.shelterScore(bb), 1e-9);
        assertTrue(AaDesires.shelterScore(bb) >= Brain.URGENT);
        assertEquals("sleep", AaDesires.shelterChain(bb));
        bb.in.bedKnown = false;
        bb.in.baseKnown = true;
        assertEquals("go base then shelter", AaDesires.shelterChain(bb));
        bb.in.baseKnown = false;
        assertEquals("shelter", AaDesires.shelterChain(bb));
        bb.in.underGround = true;
        assertEquals(-1, AaDesires.shelterScore(bb), "dug in: night work goes on");
        bb.in.underGround = false;
        bb.in.hungerDrains = false;
        assertEquals(-1, AaDesires.shelterScore(bb), "Peaceful");
        bb.in.hungerDrains = true;
        bb.in.dayTime = 6000;
        assertEquals(-1, AaDesires.shelterScore(bb), "day");
        bb.in.dayTime = 12000;
        assertTrue(AaDesires.shelterScore(bb) > 0 && AaDesires.shelterScore(bb) < Brain.URGENT, "dusk: rising, not urgent");
    }

    @Test
    void urgentWithGraceWaitsForTheStepThenInterrupts() {
        double[] v = {74, 0};
        Brain br = new Brain("brain", "t",
                new Desire("tools", Desire.Kind.UPKEEP, RunJob.of("a.job", TaskKind.WAIT, "do tools", "brain"), b -> v[0]),
                new Desire("night.shelter", Desire.Kind.UPKEEP, RunJob.of("b.job", TaskKind.WAIT, "do shelter", "brain"), b -> v[1]).grace(8_000));
        br.tick(bb);
        assertEquals("do tools", port.started.get(port.started.size() - 1).chain());
        v[1] = 90;
        br.tick(bb);
        assertEquals("do tools", port.started.get(port.started.size() - 1).chain(), "the grace: the step finishes");
        clock.advance(8_000);
        br.tick(bb);
        assertEquals("do shelter", port.started.get(port.started.size() - 1).chain());
    }
}
