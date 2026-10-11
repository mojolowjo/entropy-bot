package io.github.mojolowjo.entropybot.motor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.27.0 motor: the arbiter's rules (one move, owners, gates, budget, stall, ring, failures) and the steering maths. */
class ArbiterTest {
    static final Arbiter.Gate OK = new Arbiter.Gate(true, false, false, false);
    static final Arbiter.Gate REFLEX_HOLDS = new Arbiter.Gate(true, false, false, true);

    static MotorPlan.Plan walk(String name) {
        return new MotorPlan.Plan(MotorPlan.Kind.STEP_TO, List.of(new int[]{1, 0, 0}), true, "1 steps", name);
    }

    @Test
    void oneMoveAtATimeForAJob() {
        Arbiter a = new Arbiter();
        assertNull(a.request(walk("a"), Arbiter.Owner.JOB, 0, 60, OK, "", 0));
        assertEquals("busy with a", a.request(walk("b"), Arbiter.Owner.JOB, 1, 60, OK, "", 0));
        assertEquals("a", a.plan().name());
    }

    @Test
    void aReflexMoveReplacesAJobMove() {
        Arbiter a = new Arbiter();
        a.request(walk("job"), Arbiter.Owner.JOB, 0, 60, OK, "", 0);
        assertNull(a.request(walk("reflex"), Arbiter.Owner.REFLEX, 5, 60, OK, "", 0));
        assertEquals(Arbiter.Owner.REFLEX, a.owner());
        assertTrue(a.last().get(0).end().startsWith("replaced by reflex"));
    }

    @Test
    void aJobMoveWaitsWhileTheReflexesHold() {
        Arbiter a = new Arbiter();
        assertEquals("the reflexes own the motor", a.request(walk("job"), Arbiter.Owner.JOB, 0, 60, REFLEX_HOLDS, "", 0));
        assertNull(a.request(walk("r"), Arbiter.Owner.REFLEX, 0, 60, REFLEX_HOLDS, "", 0));
        assertEquals("busy with r", a.request(walk("job"), Arbiter.Owner.JOB, 0, 60, OK, "", 0));
    }

    @Test
    void gatesRefuseEveryMove() {
        Arbiter a = new Arbiter();
        assertEquals("motor off", a.request(walk("x"), Arbiter.Owner.REFLEX, 0, 60, new Arbiter.Gate(false, false, false, false), "", 0));
        assertEquals("a screen is open", a.request(walk("x"), Arbiter.Owner.REFLEX, 0, 60, new Arbiter.Gate(true, true, false, false), "", 0));
        assertTrue(a.request(walk("x"), Arbiter.Owner.REFLEX, 0, 60, new Arbiter.Gate(true, false, true, false), "", 0).contains("human"));
        assertFalse(a.active());
        assertEquals(3, a.refusedCount());
    }

    @Test
    void aFailedPlanIsRefusedAndCounted() {
        Arbiter a = new Arbiter();
        MotorPlan.Plan bad = MotorPlan.fail(MotorPlan.Kind.BACK_OFF, "back off 2", "no standable cell (wall right there)");
        assertEquals("no standable cell (wall right there)", a.request(bad, Arbiter.Owner.REFLEX, 0, 60, OK, "", 1000));
        assertEquals(1, a.failuresLastHour(1000));
        assertTrue(a.last().get(0).end().startsWith("refused"));
        assertTrue(a.last().get(0).failed());
    }

    @Test
    void theBudgetEndsAMove() {
        Arbiter a = new Arbiter();
        a.request(walk("w"), Arbiter.Owner.REFLEX, 100, 20, OK, "", 0);
        double rem = 10;
        for (long t = 101; t < 120; t++) assertNull(a.tick(t, rem -= 0.2, false, OK, 0));
        assertTrue(a.tick(120, rem - 0.2, false, OK, 0).startsWith("out of time"));
        assertFalse(a.active());
        assertEquals(1, a.failuresLastHour(0));
    }

    @Test
    void holdEndsAtTheBudgetWithoutAFailure() {
        Arbiter a = new Arbiter();
        a.request(MotorPlan.hold("phantom"), Arbiter.Owner.REFLEX, 0, 30, OK, "", 0);
        for (long t = 1; t < 30; t++) assertNull(a.tick(t, Double.NaN, false, OK, 0), "hold has no stall rule");
        assertTrue(a.tick(30, Double.NaN, false, OK, 0).startsWith("done (held"));
        assertEquals(0, a.failuresLastHour(0));
    }

    @Test
    void noProgressForTenTicksStalls() {
        Arbiter a = new Arbiter();
        a.request(walk("w"), Arbiter.Owner.REFLEX, 0, 200, OK, "", 0);
        assertNull(a.tick(1, 3.0, false, OK, 0));
        for (long t = 2; t < 11; t++) assertNull(a.tick(t, 3.0, false, OK, 0));
        String end = a.tick(11, 3.0, false, OK, 0);
        assertNotNull(end);
        assertTrue(end.startsWith("stalled"), end);
        assertTrue(a.last().get(0).failed());
    }

    @Test
    void progressKeepsItGoing() {
        Arbiter a = new Arbiter();
        a.request(walk("w"), Arbiter.Owner.REFLEX, 0, 200, OK, "", 0);
        double r = 5;
        for (long t = 1; t < 60; t++) {
            if (t % 8 == 0) r -= 0.1;      // slow but steady
            assertNull(a.tick(t, r, false, OK, 0));
        }
    }

    @Test
    void doneEndsIt() {
        Arbiter a = new Arbiter();
        a.request(walk("w"), Arbiter.Owner.REFLEX, 0, 60, OK, "why", 0);
        assertEquals("done", a.tick(3, 0.1, true, OK, 0));
        assertEquals("w [reflex] done after 3 ticks (why)", a.last().get(0).text());
        assertFalse(a.last().get(0).failed());
    }

    @Test
    void theHumanTakingOverStopsAMove() {
        Arbiter a = new Arbiter();
        a.request(walk("w"), Arbiter.Owner.REFLEX, 0, 60, OK, "", 0);
        String end = a.tick(2, 1, false, new Arbiter.Gate(true, false, true, true), 0);
        assertTrue(end.startsWith("stopped: the human drives"));
        assertFalse(a.active());
    }

    @Test
    void stopAndIdle() {
        Arbiter a = new Arbiter();
        assertNull(a.stop(0, "x", 0));
        assertEquals("idle", a.tick(0, 1, false, OK, 0));
        a.request(walk("w"), Arbiter.Owner.REFLEX, 0, 60, OK, "", 0);
        assertEquals("reflex ended", a.stop(4, "reflex ended", 0));
        assertFalse(a.active());
    }

    @Test
    void theRingKeepsTen() {
        Arbiter a = new Arbiter();
        for (int i = 0; i < 15; i++) {
            a.request(walk("m" + i), Arbiter.Owner.REFLEX, i, 60, OK, "", 0);
            a.tick(i, 0, true, OK, 0);
        }
        List<Arbiter.Result> r = a.last();
        assertEquals(Arbiter.RING, r.size());
        assertEquals("m5", r.get(0).name());
        assertEquals("m14", r.get(9).name());
        assertTrue(a.lastText().contains("m14"));
    }

    @Test
    void moreThanTwentyFailuresAnHourIsAFinding() {
        Arbiter a = new Arbiter();
        MotorPlan.Plan bad = MotorPlan.fail(MotorPlan.Kind.STEP_TO, "step to 1 2 3", "no walk there within 8 steps");
        for (int i = 0; i < 20; i++) a.request(bad, Arbiter.Owner.REFLEX, i, 60, OK, "", i * 1000L);
        assertNull(a.finding(20_000));
        a.request(bad, Arbiter.Owner.REFLEX, 21, 60, OK, "", 21_000);
        String[] f = a.finding(21_000);
        assertNotNull(f);
        assertEquals("motorfail", f[0]);
        assertTrue(f[1].contains("21 times"));
        assertNull(a.finding(21_000 + Arbiter.HOUR_MS + 1), "an hour later they are gone");
    }

    // ---- steering ----

    /** Vanilla getInputVector at yaw r: (x cos - z sin, z cos + x sin) with x = left, z = forward. */
    static double[] world(float yaw, float fwd, float left) {
        double r = Math.toRadians(yaw);
        return new double[]{left * Math.cos(r) - fwd * Math.sin(r), fwd * Math.cos(r) + left * Math.sin(r)};
    }

    @Test
    void impulsesAlongTheAxes() {
        float[] k = MotorSteer.impulses(0f, 0, 1);         // yaw 0 faces south (+z)
        assertEquals(1f, k[0], 1e-5);
        assertEquals(0f, k[1], 1e-5);
        k = MotorSteer.impulses(0f, 0, -1);
        assertEquals(-1f, k[0], 1e-5);
        k = MotorSteer.impulses(0f, 1, 0);                 // facing south, east is on the left
        assertEquals(1f, k[1], 1e-5);
        k = MotorSteer.impulses(90f, -1, 0);               // yaw 90 faces west
        assertEquals(1f, k[0], 1e-5);
    }

    @Test
    void impulsesWalkTheAskedDirectionAtAnyYaw() {
        float[] yaws = {0f, 33f, -120f, 180f, 271f};
        double[][] dirs = {{1, 0}, {1, 1}, {-0.3, 0.8}, {0, -2}};
        for (float y : yaws) for (double[] d : dirs) {
            float[] k = MotorSteer.impulses(y, d[0], d[1]);
            assertTrue(Math.max(Math.abs(k[0]), Math.abs(k[1])) <= 1.00001f);
            double[] w = world(y, k[0], k[1]);
            double cos = (w[0] * d[0] + w[1] * d[1]) / (Math.hypot(w[0], w[1]) * Math.hypot(d[0], d[1]));
            assertEquals(1.0, cos, 1e-5, "yaw " + y);
        }
    }

    @Test
    void zeroDirectionNoKeys() {
        float[] k = MotorSteer.impulses(45f, 0, 0);
        assertEquals(0f, k[0]);
        assertEquals(0f, k[1]);
    }

    @Test
    void yawToFacesTheTarget() {
        assertEquals(0f, MotorSteer.yawTo(0, 0, 0, 5), 1e-4);
        assertEquals(90f, MotorSteer.yawTo(0, 0, -5, 0), 1e-4);
        assertEquals(-90f, MotorSteer.yawTo(0, 0, 5, 0), 1e-4);
        float y = MotorSteer.yawTo(0, 0, 3, 4);
        float[] k = MotorSteer.impulses(y, 3, 4);
        assertEquals(1f, k[0], 1e-4);
        assertEquals(0f, k[1], 1e-4);
    }
}
