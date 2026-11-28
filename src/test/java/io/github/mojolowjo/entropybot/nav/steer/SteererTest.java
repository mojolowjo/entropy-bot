package io.github.mojolowjo.entropybot.nav.steer;

import org.junit.jupiter.api.Test;

import static io.github.mojolowjo.entropybot.nav.steer.FakeWaterSim.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Ghost steering S1 (plan to-do/nav-steer.md): the steerer swims, crosses a current, climbs out, and gives up with a reason. */
class SteererTest {
    static final SteerConfig CFG = new SteerConfig(15, 2, 4, 60, 0.25, 1.0, 20, 100, 0.1);

    /** Steers the real body until it arrives, gives up, or {@code max} ticks pass; fails on any hurt. */
    static Result run(FakeWaterSim real, Target t, int max, Steerer.Budget b) {
        Steerer s = new Steerer(CFG);
        FakeWaterSim ghost = new FakeWaterSim(real);
        for (int tick = 0; tick < max; tick++) {
            Steerer.Decision d = s.tick(real.x, real.y, real.z, t, ghost, b);
            if (d.status() != Steerer.Status.STEERING) return new Result(d, tick, s);
            real.step(d.choice(), d.yaw());
            assertFalse(real.hurt, "the real body got hurt at tick " + tick + " doing " + d.why());
        }
        return new Result(null, max, s);
    }

    record Result(Steerer.Decision end, int ticks, Steerer s) {}

    /** A stone floor at y 0 from x -10..20, z -10..10. */
    static FakeWaterSim.World floor() {
        return new FakeWaterSim.World().fill(-10, 0, -10, 20, 0, 10, SOLID);
    }

    @Test void walksToACellOnFlatGround() {
        FakeWaterSim real = new FakeWaterSim(floor(), 0.5, 1, 0.5);
        Result r = run(real, Target.cell(6, 1, 0), 200, Steerer.Budget.NONE);
        assertNotNull(r.end(), "did not finish in 200 ticks: " + r.s().status());
        assertEquals(Steerer.Status.ARRIVED, r.end().status(), r.end().why());
        assertTrue(r.ticks() < 60, "6 blocks took " + r.ticks() + " ticks");
    }

    @Test void swimsUpFromTheBottomOfADeepPoolAndClimbsOut() {
        // banks of stone y 1..6 round a 3x3 pool of water y 1..6; the bank's top is y 7
        FakeWaterSim.World w = floor().fill(-4, 1, -4, 6, 6, 4, SOLID).fill(0, 1, -1, 2, 6, 1, WATER);
        FakeWaterSim real = new FakeWaterSim(w, 1.5, 1, 0.5);
        Result r = run(real, Target.cell(4, 7, 0), 600, Steerer.Budget.NONE);
        assertNotNull(r.end(), "did not finish in 600 ticks: " + r.s().status() + " at " + real.x + " " + real.y + " " + real.z);
        assertEquals(Steerer.Status.ARRIVED, r.end().status(), r.end().why());
        assertTrue(real.air > 0);
    }

    @Test void crossesARiverWithACurrentToTheFarBank() {
        // a river 5 wide (z 0..4), 2 deep, flowing +x; banks' top y 3
        FakeWaterSim.World w = floor().fill(-10, 1, -10, 20, 2, 10, SOLID).fill(-10, 1, 0, 20, 2, 4, WATER)
                .current(-10, 1, 0, 20, 2, 4, 1, 0);
        FakeWaterSim real = new FakeWaterSim(w, 5.5, 1, 0.5);
        Result r = run(real, Target.cell(5, 3, 6), 600, Steerer.Budget.NONE);
        assertNotNull(r.end(), "did not finish: " + r.s().status() + " at " + real.x + " " + real.y + " " + real.z);
        assertEquals(Steerer.Status.ARRIVED, r.end().status(), r.end().why());
    }

    @Test void sealedInItGivesUpWithAReason() {
        FakeWaterSim.World w = floor().fill(-1, 1, -1, 1, 4, 1, SOLID).fill(0, 1, 0, 0, 2, 0, AIR);
        FakeWaterSim real = new FakeWaterSim(w, 0.5, 1, 0.5);
        Result r = run(real, Target.cell(6, 1, 0), 200, Steerer.Budget.NONE);
        assertNotNull(r.end());
        assertEquals(Steerer.Status.GAVE_UP, r.end().status());
        assertTrue(r.end().why().matches("no choice got closer than [0-9.]+ blocks for 61 ticks \\(feet at .*"), r.end().why());
    }

    @Test void itDoesNotFlipFlop() {
        FakeWaterSim.World w = floor().fill(-4, 1, -4, 6, 6, 4, SOLID).fill(0, 1, -1, 2, 6, 1, WATER);
        FakeWaterSim real = new FakeWaterSim(w, 1.5, 1, 0.5);
        Result r = run(real, Target.cell(4, 7, 0), 600, Steerer.Budget.NONE);
        assertTrue(r.s().switches() <= r.ticks() / CFG.hold() + 1, r.s().switches() + " switches in " + r.ticks() + " ticks");
    }

    @Test void aBudgetCutBeforeAnyTryHoldsAndCounts() {
        FakeWaterSim real = new FakeWaterSim(floor(), 0.5, 1, 0.5);
        Steerer s = new Steerer(CFG);
        Steerer.Decision d = s.tick(real.x, real.y, real.z, Target.cell(6, 1, 0), new FakeWaterSim(real), () -> false);
        assertEquals(Choice.STILL, d.choice());
        assertTrue(d.why().startsWith("budget cut before any try"), d.why());
        assertEquals(1, s.cuts());
    }

    @Test void aPartTestPicksOnlyFromTheOptionsTriedInFull() {
        FakeWaterSim real = new FakeWaterSim(floor(), 0.5, 1, 0.5);
        Steerer s = new Steerer(CFG);
        int[] left = {CFG.horizon() * 2};                     // two options in full, the third cut
        Steerer.Decision d = s.tick(real.x, real.y, real.z, Target.cell(6, 1, 0), new FakeWaterSim(real), () -> left[0]-- > 0);
        assertTrue(Choice.OPTIONS.subList(0, 2).contains(d.choice()), d.choice().name());
        assertTrue(d.why().contains("2 of 8 tried"), d.why());
        assertEquals(1, s.cuts());
    }

    @Test void aHurtingTryScoresWorseThanAFarMiss() {
        assertTrue(Score.of(-1, 0.5, true, 300, CFG) > Score.of(-1, 5, false, 300, CFG));
        assertTrue(Score.of(10, 0, false, 300, CFG) < Score.of(-1, 0.1, false, 300, CFG), "reaching beats any miss");
        assertTrue(Score.of(-1, 1, false, 20, CFG) > Score.of(-1, 1, false, 300, CFG), "low air costs");
    }

    @Test void inACellIsArrived() {
        assertTrue(Target.cell(3, 7, 0).holds(3.5, 7.0, 0.2));
        assertFalse(Target.cell(3, 7, 0).holds(3.5, 6.99, 0.2));
    }
}
