package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.moves.ReflexLadder.Kind;
import io.github.mojolowjo.entropybot.aa.moves.ReflexLadder.Way;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflex choosers: each reflex's ladder on the fake Baritone (MovesTest.FakeCar). A way the car fails is charged in
 * Failures under reflex.<kind> <cell>; after reflex.triesPerWay tries the next named way runs; all spent = FAILED with a
 * line for the danger desire. The same sequence the game code runs (Reflexes.airStep / creeperTry, FireReflex.escape).
 */
class ReflexLadderTest {
    static final int[] CELL = {10, 55, -4};
    Failures f;

    @BeforeEach
    void fresh() {
        f = new Failures();
        ReflexLadder.bind(f);
    }

    /** Runs a way on Moves the way the reflex does. */
    static void start(Moves m, Way w, double[] heading) {
        switch (w) {
            case SURFACE -> m.swimUp();
            case BURROW_UP -> m.burrowHere("burrow up", 3, true);
            case RUN_FROM_HAZARD -> m.escape("lava", 3, List.of(new int[]{10, 55, -4}));
            case TO_WATER -> m.toWater("to water");
            case RUN_AWAY -> m.flee(12, List.of(new int[]{16, 55, -4}), heading);
            case BURROW -> m.burrowHere("burrow", 2, false);
        }
    }

    /** Fails every try on the car until the ladder is spent; returns the car's calls. */
    List<String> failAll(Kind k, Way from, double[] heading) {
        MovesTest.FakeCar car = new MovesTest.FakeCar();
        Moves m = new Moves(car);
        Way w = ReflexLadder.way(k, CELL, from);
        int guard = 0;
        long t = 0;
        while (w != null && guard++ < 50) {
            start(m, w, heading);
            car.status = Car.Status.failed("Baritone found no path (" + w.label + ")");
            assertEquals(Moves.Phase.FAILED, m.tick(++t));
            w = ReflexLadder.charge(k, CELL, m.why(), from);
        }
        assertNull(w, "the ladder has a concrete end");
        assertEquals(k.ways.size() - Math.max(0, from == null ? 0 : k.ways.indexOf(from)),
                car.calls.stream().filter(c -> !c.startsWith("stop")).distinct().count(), car.calls.toString());
        return car.calls;
    }

    @Test
    void airSurfaceThenBurrowUpThenFailed() {
        List<String> calls = failAll(Kind.AIR, null, null);
        int n = ReflexLadder.triesPerWay();
        assertEquals("breathe", calls.get(0), "the surface goal first: the head in air, Baritone picks the cell");
        assertEquals(n, calls.stream().filter("breathe"::equals).count());
        assertEquals(n, calls.stream().filter("burrowHere 3 up"::equals).count(), "then the fork digs up");
        assertEquals(2 * n, f.fails("reflex.air", "10 55 -4"), "every try charged under reflex.air <cell>");
        String line = ReflexLadder.spent(Kind.AIR, CELL, "no path");
        assertTrue(line.startsWith("reflex.air 10 55 -4: FAILED after " + 2 * n + " tries (surface goal, burrow up)"), line);
    }

    @Test
    void lavaRunsFromTheHazardThenToWaterThenFailed() {
        List<String> calls = failAll(Kind.LAVA, null, null);
        assertEquals("runAway 3 1", calls.get(0));
        assertTrue(calls.contains("toWater"), "the second way: feet in water, Baritone picks the water");
        assertEquals(2 * ReflexLadder.triesPerWay(), f.fails("reflex.lava", "10 55 -4"));
    }

    @Test
    void fireWithWaterNearGoesToWaterFirstThenStillRuns() {
        List<String> calls = failAll(Kind.FIRE.waterNear(), null, null);
        assertEquals("toWater", calls.get(0));
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("runAway")), "staged 0.39.s1: unreachable water must not skip the run");
        assertEquals(2 * ReflexLadder.triesPerWay(), f.fails("reflex.fire", "10 55 -4"), "the same record as the plain fire ladder");
    }

    @Test
    void creeperWithTheHeadingRunsWithTheCostTermThenBurrows() {
        List<String> calls = failAll(Kind.CREEPER, null, new double[]{0, 1});
        assertEquals("runAway 12 1 prefer", calls.get(0), "the heading only as the goal's heuristic term");
        assertTrue(calls.contains("burrowHere 2 down"), "then the fork's burrow here");
    }

    @Test
    void creeperWithoutAHeadingIsPlainRunAway() {
        List<String> calls = failAll(Kind.CREEPER, null, null);
        assertEquals("runAway 12 1", calls.get(0));
    }

    @Test
    void noPathFailsWithAReasonAndEscalates() {
        MovesTest.FakeCar car = new MovesTest.FakeCar();
        Moves m = new Moves(car);
        m.swimUp();
        car.status = Car.Status.failed("Baritone found no path (swim up to air)");
        assertEquals(Moves.Phase.FAILED, m.tick(1));
        assertTrue(m.why().contains("found no path"), m.why());
        Way w = null;
        for (int i = 0; i < ReflexLadder.triesPerWay(); i++) w = ReflexLadder.charge(Kind.AIR, CELL, m.why(), null);
        assertEquals(Way.BURROW_UP, w, "after the tries the next named way, no key-press fallback");
        assertTrue(f.reason("reflex.air", "10 55 -4").contains("found no path"), "the reason is kept with the charge");
    }

    @Test
    void aReflexThatEndsWellForgetsItsTries() {
        ReflexLadder.charge(Kind.CREEPER, CELL, "x", null);
        ReflexLadder.ended(Kind.CREEPER);
        assertEquals(0, ReflexLadder.fails(Kind.CREEPER, CELL));
        assertEquals(Way.RUN_AWAY, ReflexLadder.way(Kind.CREEPER, CELL));
    }
}
