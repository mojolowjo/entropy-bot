package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteerRulesTest {
    private static final float E = 1e-5f;

    /** Vanilla's Entity.getInputVector rotation: world (dx, dz) for impulses at a yaw. */
    private static double[] world(float forward, float left, float yaw) {
        double r = Math.toRadians(yaw);
        double s = Math.sin(r), c = Math.cos(r);
        return new double[]{left * c - forward * s, forward * c + left * s};
    }

    @Test
    void identityWhenTheYawsAreEqual() {
        assertArrayEquals(new float[]{1f, 0f}, SteerRules.rotate(1f, 0f, 37f, 37f), E);
        assertArrayEquals(new float[]{-1f, 1f}, SteerRules.rotate(-1f, 1f, -120f, -120f), E);
    }

    @Test
    void ninetyDegrees() {
        // the camera looks west (yaw 90), the player faces south (0): W must move west (-x)
        float[] r = SteerRules.rotate(1f, 0f, 90f, 0f);
        assertArrayEquals(new float[]{0f, -1f}, r, E);     // no forward, strafe right (= west for a south-facing player)
        double[] w = world(r[0], r[1], 0f);
        assertEquals(-1, w[0], E);
        assertEquals(0, w[1], E);
        assertEquals(0f, r[0]);                              // exactly 0: no stray forward impulse (sprint check)
    }

    @Test
    void oneEightyDegrees() {
        // the camera looks the other way than the player: W walks the player backwards, S forwards
        assertArrayEquals(new float[]{-1f, 0f}, SteerRules.rotate(1f, 0f, 180f, 0f), E);
        assertArrayEquals(new float[]{1f, 0f}, SteerRules.rotate(-1f, 0f, 180f, 0f), E);
        assertArrayEquals(new float[]{0f, -1f}, SteerRules.rotate(0f, 1f, -90f, 90f), E);
    }

    @Test
    void movesWhereTheCameraLooksForEveryKey() {
        float cam = 33f, player = -71f;
        float[][] keys = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {0.3f, 0.3f}};   // incl. sneaking (scaled)
        for (float[] k : keys) {
            float[] r = SteerRules.rotate(k[0], k[1], cam, player);
            double[] got = world(r[0], r[1], player), want = world(k[0], k[1], cam);
            assertEquals(want[0], got[0], 1e-4, "dx for " + k[0] + "," + k[1]);
            assertEquals(want[1], got[1], 1e-4, "dz for " + k[0] + "," + k[1]);
        }
    }

    @Test
    void diagonalKeepsItsLength() {
        float[] r = SteerRules.rotate(1f, 1f, 45f, 0f);
        assertEquals(Math.sqrt(2), Math.hypot(r[0], r[1]), 1e-5);
        assertArrayEquals(new float[]{(float) Math.sqrt(2), 0f}, r, E);  // W+A with the camera turned 45 to the player's right: straight ahead
        for (float d = -360; d <= 360; d += 17) {
            float[] q = SteerRules.rotate(0.7f, -0.4f, d, 12f);
            assertEquals(Math.hypot(0.7, 0.4), Math.hypot(q[0], q[1]), 1e-5);
        }
    }

    @Test
    void remapLeavesTheInputAloneWhenOffIdleOrTheBotDrives() {
        float[] in = {1f, 0f};
        assertArrayEquals(in, SteerRules.remap(SteerRules.State.OFF, true, 1f, 0f, 90f, 0f));
        assertArrayEquals(in, SteerRules.remap(SteerRules.State.IDLE, true, 1f, 0f, 90f, 0f));
        assertArrayEquals(in, SteerRules.remap(SteerRules.State.TUNNEL, false, 1f, 0f, 90f, 0f));   // a job/Baritone/reflex
        assertArrayEquals(in, SteerRules.remap(null, true, 1f, 0f, 90f, 0f));
        assertArrayEquals(new float[]{0f, 0f}, SteerRules.remap(SteerRules.State.V1, true, 0f, 0f, 90f, 0f));
        assertArrayEquals(new float[]{0f, -1f}, SteerRules.remap(SteerRules.State.V1, true, 1f, 0f, 90f, 0f), E);
    }

    @Test
    void humanOnlyNeedsEverySignal() {
        assertTrue(SteerRules.humanOnly(true, true, false, true, false));
        assertFalse(SteerRules.humanOnly(false, true, false, true, false));   // Baritone's PlayerMovementInput
        assertFalse(SteerRules.humanOnly(true, false, false, true, false));   // a job, chain or request
        assertFalse(SteerRules.humanOnly(true, true, true, true, false));     // a reflex (fight, eating, creeper duel)
        assertFalse(SteerRules.humanOnly(true, true, false, false, false));   // Baritone pathing / a process in control
        assertFalse(SteerRules.humanOnly(true, true, false, true, true));     // riding
    }

    @Test
    void effectiveStateFollowsOurViewsOnly() {
        assertEquals(SteerRules.State.OFF, SteerRules.effective(false, true, true, true));
        assertEquals(SteerRules.State.IDLE, SteerRules.effective(true, false, false, false));
        assertEquals(SteerRules.State.IDLE, SteerRules.effective(true, false, true, false));    // vanilla F5 alone: no
        assertEquals(SteerRules.State.IDLE, SteerRules.effective(true, true, false, false));    // v1 on, but first person
        assertEquals(SteerRules.State.V1, SteerRules.effective(true, true, true, false));
        assertEquals(SteerRules.State.TUNNEL, SteerRules.effective(true, false, false, true));
        assertEquals(SteerRules.State.TUNNEL, SteerRules.effective(true, true, true, true));    // the tunnel view shows first
        assertTrue(SteerRules.State.V1.active());
        assertFalse(SteerRules.State.IDLE.active());
    }

    @Test
    void errorGateLogsFiveThenEveryHundredAndTripsAtTwentyInAMinute() {
        SteerRules.ErrorGate g = new SteerRules.ErrorGate();
        for (int i = 1; i <= 5; i++) assertEquals(SteerRules.ErrorGate.Action.LOG_FULL, g.record(i * 10_000L));
        assertEquals(SteerRules.ErrorGate.Action.QUIET, g.record(60_000));
        assertFalse(g.tripped());
        // spread out: 20 errors, but never 20 within a minute
        SteerRules.ErrorGate slow = new SteerRules.ErrorGate();
        for (int i = 0; i < 100; i++) slow.record(i * 5_000L);
        assertFalse(slow.tripped());
        assertEquals(100, slow.total());
        // the 100th is logged as a summary
        SteerRules.ErrorGate h = new SteerRules.ErrorGate();
        SteerRules.ErrorGate.Action last = null;
        for (int i = 0; i < 100; i++) last = h.record(i * 10_000L);
        assertEquals(SteerRules.ErrorGate.Action.LOG_SUMMARY, last);
        // a burst trips it
        SteerRules.ErrorGate b = new SteerRules.ErrorGate();
        for (int i = 0; i < 19; i++) b.record(1000 + i);
        assertFalse(b.tripped());
        b.record(2000);
        assertTrue(b.tripped());
        b.reset();
        assertFalse(b.tripped());
        assertEquals(0, b.total());
    }

    @Test
    void theViewHoldsItsYawWhileTheHumanDrives() {
        // walking right (D) would pull a walk-following camera round: while driving it holds, turning only with the player
        assertEquals(30f, SteerRules.nextYaw(true, 30f, 45f, 0f), E);
        assertEquals(40f, SteerRules.nextYaw(true, 30f, 45f, 10f), E);
        assertEquals(45f, SteerRules.nextYaw(false, 30f, 45f, 10f), E);
        // a steady strafe with the hold: the remap delta stays the same tick after tick, so the walk is a straight line
        float cam = 0f, player = 0f;
        float[] first = SteerRules.rotate(0f, -1f, cam, player);
        for (int i = 0; i < 50; i++) cam = SteerRules.nextYaw(true, cam, WatchCamera.follow(cam, -1, 0), 0f);
        assertArrayEquals(first, SteerRules.rotate(0f, -1f, cam, player), E);
    }

    @Test
    void findings() {
        assertEquals(0, SteerRules.findings(true, true, null).size());
        assertEquals(0, SteerRules.findings(false, false, null).size());
        assertEquals("steerhook", SteerRules.findings(true, false, null).get(0).key());
        assertEquals("steererror", SteerRules.findings(true, true, "20 errors").get(0).key());
    }
}
