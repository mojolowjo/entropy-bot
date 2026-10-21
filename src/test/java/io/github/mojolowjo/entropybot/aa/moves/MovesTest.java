package io.github.mojolowjo.entropybot.aa.moves;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Backbone step 2: Moves' state machine on a fake Baritone. */
class MovesTest {

    /** Records the commands; the test sets the status. */
    static class FakeCar implements Car {
        final List<String> calls = new ArrayList<>();
        Status status = Status.running("driving");
        int[] top;

        @Override public void runAway(int dist, List<int[]> from) { calls.add("runAway " + dist + " " + from.size()); }
        @Override public void pillar(int[] top) { this.top = top; calls.add("pillar " + s(top)); }
        @Override public void digInHere(int timeoutTicks) { calls.add("digInHere"); }
        double[] prefer;
        boolean ready;
        @Override public void runAway(int dist, List<int[]> from, double[] prefer) {
            this.prefer = prefer;
            calls.add("runAway " + dist + " " + from.size() + (prefer == null ? "" : " prefer"));
        }
        int depth; boolean up;
        @Override public void breathe() { calls.add("breathe"); }
        @Override public void toWater() { calls.add("toWater"); }
        @Override public void burrowHere(int depth, boolean up, int timeoutTicks) {
            this.depth = depth; this.up = up;
            calls.add("burrowHere " + depth + (up ? " up" : " down"));
        }
        @Override public boolean pathReady() { return ready; }
        @Override public void look(double x, double y, double z) { calls.add("look"); }
        @Override public Status status() { return status; }
        @Override public void stop(String why) { calls.add("stop " + why); }
        @Override public void release() { calls.add("release"); }

        static String s(int[] c) { return c[0] + " " + c[1] + " " + c[2]; }
    }

    @Test
    void burrowHereLeavesTheCellsToTheFork() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.burrowHere("burrow", 2, false);
        assertEquals(List.of("burrowHere 2 down"), car.calls, "only what and how deep: the fork picks the cells");
        assertEquals(Moves.Phase.RUNNING, m.tick(1));
        car.status = Car.Status.done("done: dug 2, sealed 1");
        assertEquals(Moves.Phase.ARRIVED, m.tick(2));
        assertTrue(m.why().contains("done"), m.why());
    }

    @Test
    void aFailedBurrowNamesTheStageTheBlockAndTheVerdict() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.burrowHere("burrow", 2, false);
        car.status = Car.Status.failed("dig 1/2 at 10 63 5 (grass_block): refused by the break rules (cost hook / Baritone settings)");
        assertEquals(Moves.Phase.FAILED, m.tick(5));
        assertTrue(m.why().startsWith("gave up at burrow 2 down here: dig 1/2 at 10 63 5 (grass_block): refused"), m.why());
        assertEquals(Moves.Phase.FAILED, m.tick(6), "a failure stays failed");
    }

    @Test
    void theAirReflexBurrowsUp() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.burrowHere("burrow up", 3, true);
        assertEquals("burrowHere 3 up", car.calls.get(0));
        assertEquals("burrow up", m.way());
    }
    @Test
    void aLegOverItsTimeFailsAndStopsTheCar() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.tick(100);
        m.pillar(new int[]{1, 64, 1}, 3);
        assertEquals("1 67 1", FakeCar.s(car.top));
        assertEquals(Moves.Phase.RUNNING, m.tick(100 + Moves.pillarTicks()));
        assertEquals(Moves.Phase.FAILED, m.tick(101 + Moves.pillarTicks()));
        assertTrue(car.calls.contains("stop timed out"));
        assertTrue(m.why().contains("timed out after 15 s"), m.why());
    }

    @Test
    void digInLeavesTheWallToTheFork() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.digInHere();
        assertEquals(List.of("digInHere"), car.calls, "no mod-picked wall, face or cell");
        car.status = Car.Status.failed("dig in at 0 64 0: north: side east of 0 64 -1 (air) is open; ...");
        assertEquals(Moves.Phase.FAILED, m.tick(1));
        assertTrue(m.why().startsWith("gave up at dig into the nearest wall: dig in at 0 64 0: north"), m.why());
    }

    @Test
    void digInFleeAndLookGoToTheCar() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.digInHere();
        m.flee(16, List.of(new int[]{0, 64, 0}, new int[]{1, 64, 1}));
        m.lookAt(0, 65, 0);
        assertEquals(List.of("digInHere", "stop replaced by flee", "runAway 16 2", "look"), car.calls);
    }

    @Test
    void noStatusOrAThrowIsAFailureNeverSilent() {
        Moves m = new Moves(new FakeCar() {
            @Override public Status status() { throw new IllegalStateException("boom"); }
        });
        m.digInHere();
        assertEquals(Moves.Phase.FAILED, m.tick(1));
        assertTrue(m.why().contains("boom"));
        FakeCar nul = new FakeCar();
        nul.status = null;
        Moves n = new Moves(nul);
        n.digInHere();
        assertEquals(Moves.Phase.FAILED, n.tick(1));
    }

    @Test
    void releaseHandsBaritoneBackAndGoesIdle() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.burrowHere("burrow", 2, false);
        m.release();
        assertEquals(Moves.Phase.IDLE, m.phase());
        assertTrue(car.calls.contains("release"));
        assertTrue(car.calls.contains("stop released"));
    }

    // ---- backbone step 4: the reflexes' goals ----

    @Test
    void airSwimsUpToAnyAirAndTheLatencyWaitsForThePath() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.tick(10);
        m.swimUp();
        assertEquals("breathe", car.calls.get(0), "the head in open air: Baritone picks the cell");
        assertEquals("swim up", m.way());
        assertEquals(10, m.legStart(), "started the tick it was asked (latency is Baritone's)");
        assertFalse(m.pathReady(), "no path yet (the fork holds jump in this gap, not the mod)");
        car.ready = true;
        assertTrue(m.pathReady());
        car.status = Car.Status.done("swim up to air");
        assertEquals(Moves.Phase.ARRIVED, m.tick(14));
        assertFalse(m.pathReady(), "a way that is over has no path to wait for");
    }

    @Test
    void creeperFleeIsARunAwayLeaningOnTheFleeHeading() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.flee(12, List.of(new int[]{0, 64, 3}, new int[]{2, 64, 0}), new double[]{-0.6, -0.8});
        assertEquals("runAway 12 2 prefer", car.calls.get(0));
        assertArrayEquals(new double[]{-0.6, -0.8}, car.prefer);
        assertEquals("flee", m.way());
        car.status = Car.Status.failed("Baritone found no path (run 12 away)");
        assertEquals(Moves.Phase.FAILED, m.tick(3));
        assertTrue(m.why().startsWith("gave up at run 12 from 2 mob(s) on the flee heading: Baritone found no path"), m.why());
    }

    @Test
    void corneredFleeHasNoLean() {
        FakeCar car = new FakeCar();
        new Moves(car).flee(12, List.<int[]>of(new int[]{0, 64, 3}), null);
        assertEquals("runAway 12 1", car.calls.get(0));
        assertNull(car.prefer);
    }

    @Test
    void lavaEscapeRunsFromTheFluidCells() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.escape("lava", 3, List.of(new int[]{0, 63, 0}, new int[]{1, 63, 0}));
        assertEquals("runAway 3 2", car.calls.get(0));
        assertEquals("escape lava", m.way());
        assertEquals(Moves.Phase.RUNNING, m.tick(1));
        assertEquals(Moves.Phase.FAILED, m.tick(2 + Moves.reflexTicks()), "a reflex leg has its own time limit");
    }

    @Test
    void aReflexWayReplacesTheLastOne() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.swimUp();
        m.flee(12, List.<int[]>of(new int[]{0, 64, 3}), null);
        assertTrue(car.calls.contains("stop replaced by flee"), car.calls.toString());
        assertEquals("flee", m.way());
    }

    @Test
    void theRunLeansTowardTheHeading() {
        double along = RunPrefer.bias(10, 0, 0, 0, 1, 0), against = RunPrefer.bias(-10, 0, 0, 0, 1, 0), side = RunPrefer.bias(0, 10, 0, 0, 1, 0);
        assertTrue(along < side && side < against, along + " " + side + " " + against);
        assertEquals(0, side, 1e-9);
        assertArrayEquals(new double[]{0.6, 0.8}, RunPrefer.unit(new double[]{3, 4}), 1e-9);
        assertNull(RunPrefer.unit(new double[]{0, 0}));
        assertNull(RunPrefer.unit(null));
    }
}
