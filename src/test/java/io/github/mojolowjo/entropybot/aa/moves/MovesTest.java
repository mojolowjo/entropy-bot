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
        List<int[]> dig, seal;
        int[] stand, top;

        @Override public void cover(int[] cell) { calls.add("cover " + s(cell)); }
        @Override public void runAway(int dist, List<int[]> from) { calls.add("runAway " + dist + " " + from.size()); }
        @Override public void pillar(int[] top) { this.top = top; calls.add("pillar " + s(top)); }
        @Override public void burrow(List<int[]> dig, int[] stand, List<int[]> seal, int timeoutTicks) {
            this.dig = dig; this.stand = stand; this.seal = seal;
            calls.add("burrow " + dig.size() + " " + seal.size());
        }
        @Override public void look(double x, double y, double z) { calls.add("look"); }
        @Override public Status status() { return status; }
        @Override public void stop(String why) { calls.add("stop " + why); }
        @Override public void release() { calls.add("release"); }

        static String s(int[] c) { return c[0] + " " + c[1] + " " + c[2]; }
    }

    @Test
    void burrowDigsTheCellsUnderTheFeetAndSealsOverTheHead() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.burrow(new int[]{10, 64, 5}, 2);
        assertEquals("burrow 2 1", car.calls.get(0));
        assertEquals("10 63 5", FakeCar.s(car.dig.get(0)));
        assertEquals("10 62 5", FakeCar.s(car.dig.get(1)));
        assertNull(car.stand);
        assertEquals("10 64 5", FakeCar.s(car.seal.get(0)), "depth 2 seals the old feet cell (over the head)");
        assertEquals(Moves.Phase.RUNNING, m.tick(1));
        car.status = Car.Status.done("done: dug 2, sealed 1");
        assertEquals(Moves.Phase.ARRIVED, m.tick(2));
        assertTrue(m.why().contains("done"), m.why());
    }

    @Test
    void depthThreeSealsTheFirstDugCell() {
        FakeCar car = new FakeCar();
        new Moves(car).burrow(new int[]{0, 70, 0}, 3);
        assertEquals("0 69 0", FakeCar.s(car.seal.get(0)));
        assertEquals(3, car.dig.size());
    }

    @Test
    void aFailedBurrowNamesTheStageTheBlockAndTheVerdict() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.burrow(new int[]{10, 64, 5}, 2);
        car.status = Car.Status.failed("dig 1/2 at 10 63 5 (grass_block): refused by the break rules (cost hook / Baritone settings)");
        assertEquals(Moves.Phase.FAILED, m.tick(5));
        assertTrue(m.why().startsWith("gave up at burrow 2 down at 10 64 5: dig 1/2 at 10 63 5 (grass_block): refused"), m.why());
        assertEquals(Moves.Phase.FAILED, m.tick(6), "a failure stays failed");
    }

    @Test
    void aLegOverItsTimeFailsAndStopsTheCar() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.tick(100);
        m.pillar(new int[]{1, 64, 1}, 3);
        assertEquals("1 67 1", FakeCar.s(car.top));
        assertEquals(Moves.Phase.RUNNING, m.tick(100 + Moves.PILLAR_TICKS));
        assertEquals(Moves.Phase.FAILED, m.tick(101 + Moves.PILLAR_TICKS));
        assertTrue(car.calls.contains("stop timed out"));
        assertTrue(m.why().contains("timed out after 15 s"), m.why());
    }

    @Test
    void digInDigsTheWallStepsInAndSealsTheEntrance() {
        FakeCar car = new FakeCar();
        new Moves(car).digIn(new int[]{0, 64, 0}, 1, 0);
        assertEquals("1 64 0", FakeCar.s(car.dig.get(0)));
        assertEquals("1 65 0", FakeCar.s(car.dig.get(1)));
        assertEquals("1 64 0", FakeCar.s(car.stand));
        assertEquals("0 64 0", FakeCar.s(car.seal.get(0)));
        assertEquals("0 65 0", FakeCar.s(car.seal.get(1)));
    }

    @Test
    void coverFleeAndLookGoToTheCar() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.cover(new int[]{3, 64, 3});
        m.flee(16, List.of(new int[]{0, 64, 0}, new int[]{1, 64, 1}));
        m.lookAt(0, 65, 0);
        assertEquals(List.of("cover 3 64 3", "stop replaced by flee", "runAway 16 2", "look"), car.calls);
    }

    @Test
    void noStatusOrAThrowIsAFailureNeverSilent() {
        Moves m = new Moves(new FakeCar() {
            @Override public Status status() { throw new IllegalStateException("boom"); }
        });
        m.cover(new int[]{0, 0, 0});
        assertEquals(Moves.Phase.FAILED, m.tick(1));
        assertTrue(m.why().contains("boom"));
        FakeCar nul = new FakeCar();
        nul.status = null;
        Moves n = new Moves(nul);
        n.cover(new int[]{0, 0, 0});
        assertEquals(Moves.Phase.FAILED, n.tick(1));
    }

    @Test
    void releaseHandsBaritoneBackAndGoesIdle() {
        FakeCar car = new FakeCar();
        Moves m = new Moves(car);
        m.burrow(new int[]{0, 64, 0}, 2);
        m.release();
        assertEquals(Moves.Phase.IDLE, m.phase());
        assertTrue(car.calls.contains("release"));
        assertTrue(car.calls.contains("stop released"));
    }
}
