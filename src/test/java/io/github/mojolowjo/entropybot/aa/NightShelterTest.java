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
        assertEquals(85 * 1.05, AaDesires.shelterScore(bb), 1e-9, "aa-costs: Normal, no armour, no bed, dark");
        assertTrue(AaDesires.shelterScore(bb) >= Brain.URGENT);
        bb.in.armorPoints = 8;
        bb.in.bedKnown = true;
        assertEquals(85 * 0.76, AaDesires.shelterScore(bb), 1e-9, "armour 8 lowers the danger under the urgent line");
        bb.in.hostilesNear = 3;
        assertEquals(85 * 0.76 * 1.45, AaDesires.shelterScore(bb), 1e-9, "three hostiles near: urgent again");
        bb.in.hostilesNear = 0;
        assertEquals("sleep", AaDesires.shelterChain(bb));
        bb.in.bedKnown = false;
        bb.in.baseKnown = true;
        assertEquals("go base then shelter", AaDesires.shelterChain(bb));
        bb.in.baseKnown = false;
        assertEquals("burrow then wait 120", AaDesires.shelterChain(bb), "aa-night3: no blocks for a shell: dig down");
        bb.in.bag = java.util.Map.of("dirt", 12);
        assertEquals("shelter", AaDesires.shelterChain(bb));
        bb.in.bag = java.util.Map.of();
        bb.in.underGround = true;
        assertTrue(AaDesires.shelterScore(bb) > 0, "aa-night2: no sky (a tree, an overhang) is no shelter");
        bb.in.underDepth = AaLadder.SURFACE_DEPTH;
        assertEquals(-1, AaDesires.shelterScore(bb), "deep under: night work goes on");
        bb.in.underDepth = 0;
        bb.in.sheltered = true;
        assertEquals(-1, AaDesires.shelterScore(bb), "in my shell");
        bb.in.sheltered = false;
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
    void normalRunSpawnNightScores99() {
        bb.in.hungerDrains = true;
        bb.in.dayTime = 18000;
        bb.in.underGround = true;           // the 01:44 case: a staircase's first steps, no sky
        bb.in.hostilesNear = 2;
        assertEquals(99, AaDesires.shelterScore(bb), 1e-9);
    }

    @Test
    void fullNightShelterTrumpsAHighToolsRunButNotStarvingFood() {
        double[] v = {88, 0};
        Brain br = new Brain("brain", "t",
                new Desire("needs.food", Desire.Kind.UPKEEP, RunJob.of("a.job", TaskKind.WAIT, "do food", "brain"), b -> v[0]),
                new Desire("night.shelter", Desire.Kind.UPKEEP, RunJob.of("b.job", TaskKind.WAIT, "do shelter", "brain"), b -> v[1]).trumps());
        bb.in.food = 2;
        br.tick(bb);
        assertEquals("do food", port.started.get(port.started.size() - 1).chain());
        v[1] = 99;
        br.tick(bb);
        assertEquals("do food", port.started.get(port.started.size() - 1).chain(), "food under 4 keeps running");
        bb.in.food = 10;
        br.tick(bb);
        assertEquals("do shelter", port.started.get(port.started.size() - 1).chain(), "99 beats 88 + the margin");
    }

    @Test
    void hideAndHeal() {
        assertEquals(-1, AaDesires.hideScore(false, 0, 8, 20, 1, false), "day, nothing near");
        assertEquals(-1, AaDesires.hideScore(false, 2, 12, 20, 1, false), "day, hostiles near, 12 hp: not yet");
        assertEquals(56, AaDesires.hideScore(false, 2, 12, 20, 1, true), 1e-9, "started: holds to 16");
        assertEquals(70, AaDesires.hideScore(false, 2, 10, 20, 1, true), 1e-9);
        assertTrue(AaDesires.hideScore(false, 2, 8, 20, 1, false) >= Brain.URGENT, "day, hostiles near, 8 hp: urgent");
        assertEquals(99, AaDesires.hideScore(true, 0, 6, 20, 1.37, false), 1e-9, "night 6 hp: full");
        assertEquals(-1, AaDesires.hideScore(true, 3, 16, 20, 1.37, true), "16 hp: done");
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
