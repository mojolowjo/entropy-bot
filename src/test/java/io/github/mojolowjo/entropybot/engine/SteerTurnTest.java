package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** watch turn (2026-10-05): A/D strafe and turn the camera, the turn keys only turn it. The pure side in SteerRules. */
class SteerTurnTest {
    private static final float E = 1e-4f;
    private static final SteerRules.State T = SteerRules.State.TUNNEL;

    /** Vanilla's Entity.getInputVector rotation: world (dx, dz) for impulses at a yaw. */
    private static double[] world(float forward, float left, float yaw) {
        double r = Math.toRadians(yaw);
        double s = Math.sin(r), c = Math.cos(r);
        return new double[]{left * c - forward * s, forward * c + left * s};
    }

    @Test
    void directionAndCancelling() {
        assertEquals(0, SteerRules.turnDirection(false, false, false, false));
        assertEquals(-1, SteerRules.turnDirection(true, false, false, false));     // A: left
        assertEquals(1, SteerRules.turnDirection(false, true, false, false));      // D: right
        assertEquals(-1, SteerRules.turnDirection(false, false, true, false));     // turn-left key
        assertEquals(1, SteerRules.turnDirection(false, false, false, true));
        assertEquals(0, SteerRules.turnDirection(true, true, false, false));       // A+D cancel
        assertEquals(0, SteerRules.turnDirection(true, false, false, true));       // A + turn right cancel
        assertEquals(1, SteerRules.turnDirection(false, true, false, true));       // two keys the same way: not faster
    }

    @Test
    void deltaIsRateTimesTimeAndRightGrowsTheYaw() {
        assertEquals(2.25f, SteerRules.turnDelta(false, true, false, false, 45f, 0.05f), E);
        assertEquals(-2.25f, SteerRules.turnDelta(true, false, false, false, 45f, 0.05f), E);
        assertEquals(0f, SteerRules.turnDelta(false, true, false, false, 0f, 0.05f));
        assertEquals(0f, SteerRules.turnDelta(false, true, false, false, Float.NaN, 0.05f));
        assertEquals(0f, SteerRules.turnDelta(false, true, false, false, 45f, -1f));
        // south (0) turned right a quarter is west (90), as the mouse does it
        assertEquals(90f, SteerRules.turnYaw(false, true, false, false, 90f, 1f, 0f), E);
    }

    @Test
    void wrapStaysInRange() {
        assertEquals(-180f, SteerRules.wrap(180f), E);
        assertEquals(-170f, SteerRules.wrap(190f), E);
        assertEquals(170f, SteerRules.wrap(-190f), E);
        assertEquals(10f, SteerRules.wrap(730f), E);
        assertEquals(0f, SteerRules.wrap(0f), E);
        assertEquals(-179f, SteerRules.turnYaw(false, true, false, false, 20f, 0.1f, 179f), E);
    }

    @Test
    void rateParsing() {
        assertEquals(45f, SteerRules.parseTurnRate(" 45 "));
        assertEquals(5f, SteerRules.parseTurnRate("5"));
        assertEquals(180f, SteerRules.parseTurnRate("180"));
        assertEquals(-1f, SteerRules.parseTurnRate("4"));
        assertEquals(-1f, SteerRules.parseTurnRate("181"));
        assertEquals(-1f, SteerRules.parseTurnRate("fast"));
        assertEquals(-1f, SteerRules.parseTurnRate(""));
    }

    @Test
    void wAndSNeverTurn() {
        float[] w = SteerRules.steerTick(T, true, true, false, false, false, false, 45f, 0.05f, 1f, 0f, 20f, 20f);
        assertEquals(0f, w[0]);
        assertEquals(1f, w[1], E);
        float[] s = SteerRules.steerTick(T, true, true, false, false, false, false, 45f, 0.05f, -1f, 0f, 20f, 20f);
        assertEquals(0f, s[0]);
    }

    @Test
    void turnKeysTurnWithoutMoving() {
        float[] r = SteerRules.steerTick(T, true, true, false, false, false, true, 45f, 0.05f, 0f, 0f, 0f, 0f);
        assertEquals(2.25f, r[0], E);
        assertEquals(0f, r[1]);
        assertEquals(0f, r[2]);
    }

    @Test
    void offNotHumanOrNoViewLeavesItAlone() {
        // turn off: A/D still remapped (steer) but no turn
        float[] off = SteerRules.steerTick(T, true, false, false, true, false, true, 45f, 0.05f, 0f, -1f, 90f, 0f);
        assertEquals(0f, off[0]);
        assertEquals(-1f, off[1], E);          // camera west, player south: D (camera right = north) is backwards for the player
        float[] bot = SteerRules.steerTick(T, false, true, false, true, false, false, 45f, 0.05f, 0f, -1f, 90f, 0f);
        assertEquals(0f, bot[0]);
        assertEquals(-1f, bot[2]);              // untouched
        float[] idle = SteerRules.steerTick(SteerRules.State.IDLE, true, true, false, true, false, false, 45f, 0.05f, 0f, -1f, 90f, 0f);
        assertEquals(0f, idle[0]);
        assertEquals(-1f, idle[2]);
    }

    @Test
    void theRemapUsesTheYawAfterTheTurn() {
        // one step of a full second at 90 deg/s from south: the camera now looks west, so D walks to its right = north (-z)
        float[] r = SteerRules.steerTick(T, true, true, false, true, false, false, 90f, 1f, 0f, -1f, 0f, 0f);
        assertEquals(90f, r[0], E);
        double[] w = world(r[1], r[2], 0f);
        assertEquals(0, w[0], 1e-5);
        assertEquals(-1, w[1], 1e-5);
    }

    /** A held D, tick by tick, without momentum: the camera yaw, the path. */
    private static double[][] holdD(float rate, int ticks, float playerYaw, double step, float[] camOut) {
        float cam = 10f;
        double x = 0, z = 0;
        double[][] path = new double[ticks + 1][];
        path[0] = new double[]{x, z};
        for (int i = 1; i <= ticks; i++) {
            float[] r = SteerRules.steerTick(T, true, true, false, true, false, false, rate, SteerRules.TICK_SECONDS, 0f, -1f, cam, playerYaw);
            cam = SteerRules.wrap(cam + r[0]);
            double[] w = world(r[1], r[2], playerYaw);
            x += w[0] * step;
            z += w[1] * step;
            path[i] = new double[]{x, z};
        }
        camOut[0] = cam;
        return path;
    }

    private static double[] circumcentre(double[] a, double[] b, double[] c) {
        double d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]));
        double a2 = a[0] * a[0] + a[1] * a[1], b2 = b[0] * b[0] + b[1] * b[1], c2 = c[0] * c[0] + c[1] * c[1];
        return new double[]{(a2 * (b[1] - c[1]) + b2 * (c[1] - a[1]) + c2 * (a[1] - b[1])) / d,
                (a2 * (c[0] - b[0]) + b2 * (a[0] - c[0]) + c2 * (b[0] - a[0])) / d};
    }

    @Test
    void aHeldDWalksAClosedCircleOfTheExpectedRadius() {
        // 120 deg/s for 60 ticks = one full turn: the path closes; 6 degrees a tick, a regular 60-gon
        float[] cam = new float[1];
        double step = 0.2;                                  // blocks a tick (about walking speed)
        double[][] p = holdD(120f, 60, 37f, step, cam);
        assertEquals(10f, cam[0], 1e-3f, "the camera is back where it started after 360 degrees");
        assertEquals(0, Math.hypot(p[60][0] - p[0][0], p[60][1] - p[0][1]), 1e-4, "the path closes");
        double expected = step / (2 * Math.sin(Math.toRadians(6) / 2));      // the 60-gon's circumradius
        double[] c = circumcentre(p[0], p[20], p[40]);
        for (double[] q : p) assertEquals(expected, Math.hypot(q[0] - c[0], q[1] - c[1]), 1e-4, "every point on the circle");
    }

    @Test
    void theDefaultRateTurns135DegreesIn60TicksOnTheSameCircle() {
        float[] cam = new float[1];
        double step = 0.2;
        double[][] p = holdD(45f, 60, -75f, step, cam);
        assertEquals(SteerRules.wrap(10f + 135f), cam[0], 1e-3f, "45 deg/s for 3 s");
        double expected = step / (2 * Math.sin(Math.toRadians(2.25) / 2));   // about step / theta: 5.1 blocks at 0.2 b/t
        double[] c = circumcentre(p[0], p[30], p[60]);
        for (double[] q : p) assertEquals(expected, Math.hypot(q[0] - c[0], q[1] - c[1]), 1e-3, "on the circle");
        // the same keys give the same path whatever the player's own yaw (the remap is exact)
        double[][] p2 = holdD(45f, 60, 120f, step, cam);
        for (int i = 0; i < p.length; i++) {
            assertEquals(p[i][0], p2[i][0], 1e-4);
            assertEquals(p[i][1], p2[i][1], 1e-4);
        }
    }

    @Test
    void withVanillasMomentumTheCircleIsStillSteady() {
        // vanilla on the ground: v = (v + a) * 0.546 per tick (friction 0.6 * 0.91). The velocity lags the keys a little,
        // but the turn is constant, so after settling the bot runs a circle of constant radius.
        float rate = 45f, cam = 0f, py = 0f;
        double k = 0.546, acc = 0.1, vx = 0, vz = 0, x = 0, z = 0;
        java.util.List<double[]> pts = new java.util.ArrayList<>();
        int settle = 100, lap = 160;                         // 45 deg/s = 160 ticks a lap
        for (int i = 0; i < settle + lap; i++) {
            float[] r = SteerRules.steerTick(T, true, true, false, true, false, false, rate, SteerRules.TICK_SECONDS, 0f, -1f, cam, py);
            cam = SteerRules.wrap(cam + r[0]);
            double[] w = world(r[1], r[2], py);
            vx = (vx + w[0] * acc) * k;
            vz = (vz + w[1] * acc) * k;
            x += vx;
            z += vz;
            if (i >= settle) pts.add(new double[]{x, z});
        }
        double[] c = circumcentre(pts.get(0), pts.get(53), pts.get(106));
        double theta = Math.toRadians(rate * SteerRules.TICK_SECONDS);
        // steady speed: |a k / (1 - k e^{i theta})|
        double speed = acc * k / Math.hypot(1 - k * Math.cos(theta), k * Math.sin(theta));
        double expected = speed / (2 * Math.sin(theta / 2));
        for (double[] q : pts) assertEquals(expected, Math.hypot(q[0] - c[0], q[1] - c[1]), 0.01 * expected, "steady radius");
        // a lap later the bot is back where the lap started (the last point is one tick short of it)
        double[] first = pts.get(0), last = pts.get(lap - 1);
        assertEquals(speed, Math.hypot(first[0] - last[0], first[1] - last[1]), 0.01);
    }

    @Test
    void drawnYawGoesTheShortWayAndClamps() {
        assertEquals(5f, WatchCamera.drawnYaw(0f, 10f, 0.5f), E);
        assertEquals(10f, WatchCamera.drawnYaw(0f, 10f, 1.5f), E);
        assertEquals(0f, WatchCamera.drawnYaw(0f, 10f, -1f), E);
        assertEquals(180f, WatchCamera.drawnYaw(175f, -175f, 0.5f), E);      // across the wrap, not round the long way
    }

    @Test
    void findings() {
        assertTrue(SteerRules.turnFindings(true, true, true, true).isEmpty());
        assertTrue(SteerRules.turnFindings(false, true, false, false).isEmpty(), "turn off: nothing to say");
        assertTrue(SteerRules.turnFindings(true, false, false, false).isEmpty(), "steer off: steer's own finding covers it");
        List<io.github.mojolowjo.entropybot.commands.SelfCheck.Finding> a = SteerRules.turnFindings(true, true, false, false);
        assertEquals(1, a.size());
        assertTrue(a.get(0).toString().contains("turnhook"), a.get(0).toString());
        List<io.github.mojolowjo.entropybot.commands.SelfCheck.Finding> b = SteerRules.turnFindings(true, true, true, false);
        assertTrue(b.get(0).toString().contains("turnkeys"), b.get(0).toString());
    }

    @Test
    void settingsKeepTheTurn() {
        WatchSettings.Values v = new WatchSettings.Values(3f, 6, true, true, false, 90f);
        assertEquals(v, WatchSettings.parse(WatchSettings.toJson(v)).values());
        WatchSettings.Parsed old = WatchSettings.parse("{\"distance\": 5, \"steer\": true}");
        assertTrue(old.values().turn());
        assertEquals(45f, old.values().turnRate());
        WatchSettings.Parsed bad = WatchSettings.parse("{\"turn\": 1, \"turnRate\": 500}");
        assertTrue(bad.values().turn());
        assertEquals(45f, bad.values().turnRate());
        assertTrue(bad.note().contains("turn") && bad.note().contains("turnRate"), bad.note());
    }
}
