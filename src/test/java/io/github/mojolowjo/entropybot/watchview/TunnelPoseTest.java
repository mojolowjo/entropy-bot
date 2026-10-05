package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Camera v2's camera since 0.15.4: a noclip camera above and behind the bot, through rock, ground and trees. */
class TunnelPoseTest {
    @Test
    void itSitsWhereWantedWhateverIsThere() {
        TunnelPose.Pose p = TunnelPose.place(0.5, 65.6, 0.5, 0f, 12, 6);
        assertEquals("free", p.how());
        assertEquals(77.6, p.y(), 1e-9);
        assertEquals(0.5 - 6, p.z(), 1e-9, "behind = north of a bot looking south (yaw 0)");
        assertEquals(0.5, p.x(), 1e-9);
        assertEquals(0f, p.yaw(), 0.01f, "looking south at the bot");
        assertTrue(p.pitch() > 60f && p.pitch() < 65f, "looking down: " + p.pitch());
    }

    /** The old guard climbed out of rock or pulled in to the bot: 120 blocks underground the camera ended in the tunnel. */
    @Test
    void deepUndergroundItDoesNotPullIn() {
        TunnelPose.Pose p = TunnelPose.place(313.5, -44.4, 20.5, 90f, 12, 5);   // walking west, deep down
        assertEquals(-32.4, p.y(), 1e-9, "12 up, in the rock, no climbing and no pulling in");
        assertEquals(313.5 + 5, p.x(), 1e-9, "5 behind = east of a bot looking west");
        assertEquals(20.5, p.z(), 1e-9);
    }

    @Test
    void anyYawKeepsTheDistanceAndLooksAtTheBot() {
        Random rnd = new Random(11);
        for (int i = 0; i < 500; i++) {
            float yaw = rnd.nextFloat() * 360f - 180f;
            double h = 4 + rnd.nextInt(37), back = 4 + rnd.nextInt(5);
            double tx = rnd.nextGaussian() * 100, ty = rnd.nextInt(380) - 64, tz = rnd.nextGaussian() * 100;
            TunnelPose.Pose p = TunnelPose.place(tx, ty, tz, yaw, h, back);
            assertEquals(h, p.y() - ty, 1e-9);
            assertEquals(back, Math.hypot(p.x() - tx, p.z() - tz), 1e-9);
            float[] a = TunnelPose.lookAt(p.x(), p.y(), p.z(), tx, ty, tz);
            assertEquals(a[0], p.yaw(), 1e-4f);
            assertEquals(a[1], p.pitch(), 1e-4f);
            assertEquals(0f, io.github.mojolowjo.entropybot.engine.WatchCamera.turn(yaw, p.yaw()), 0.01f, "the camera looks the way of the view's yaw");
        }
    }

    @Test
    void lookAtAngles() {
        float[] a = TunnelPose.lookAt(0, 10, 0, 0, 0, 10);              // down and south
        assertEquals(0f, a[0], 0.01f);
        assertEquals(45f, a[1], 0.01f);
        a = TunnelPose.lookAt(0, 0, 0, -5, 0, 0);                        // west
        assertEquals(90f, a[0], 0.01f);
        assertEquals(0f, a[1], 0.01f);
        a = TunnelPose.lookAt(0, 10, 0, 0, 0, 0);                        // straight down
        assertEquals(90f, a[1], 0.01f);
    }

    @Test
    void followSmoothsAndJumpsOnATeleport() {
        TunnelPose.Pose a = new TunnelPose.Pose(0, 0, 0, 0, 0, "free");
        TunnelPose.Pose b = new TunnelPose.Pose(100, 0, 0, 0, 0, "free");
        assertEquals(100, TunnelPose.follow(a, b, 0.15, 100, -5, 0).x(), 1e-9);
        TunnelPose.Pose c = new TunnelPose.Pose(10, 0, 0, 0, 0, "free");
        TunnelPose.Pose s = TunnelPose.follow(a, c, 0.15, 10, -5, 0);
        assertEquals(1.5, s.x(), 1e-9);
        float[] look = TunnelPose.lookAt(1.5, 0, 0, 10, -5, 0);
        assertEquals(look[1], s.pitch(), 1e-4f, "re-aimed at the bot");
        assertSame(c, TunnelPose.follow(null, c, 0.15, 0, 0, 0));
    }

    @Test
    void smoothingSettlesOnTheWantedSpot() {
        TunnelPose.Pose want = TunnelPose.place(0.5, 11.6, 0.5, 45f, 12, 6);
        TunnelPose.Pose p = new TunnelPose.Pose(want.x() + 8, want.y() - 5, want.z(), 0, 0, "free");
        for (int i = 0; i < 120; i++) p = TunnelPose.follow(p, want, 0.15, 0.5, 11.6, 0.5);
        assertEquals(want.x(), p.x(), 1e-6);
        assertEquals(want.y(), p.y(), 1e-6);
        assertEquals(want.z(), p.z(), 1e-6);
    }

    @Test
    void turnsAndSnaps() {
        assertEquals(90f, TunnelPose.quarter(0f, false));
        assertEquals(-90f, TunnelPose.quarter(0f, true));
        assertEquals(180f, TunnelPose.quarter(90f, false));
        assertEquals(-90f, TunnelPose.quarter(180f, false));
        assertEquals(45f, TunnelPose.snap45(37f));
        assertEquals(180f, TunnelPose.snap45(-170f));
        assertEquals(0f, TunnelPose.snap45(-10f), 0f);
    }

    @Test
    void followWalkTurnsSlowlyTowardsTheWalkingDirectionAndStaysWhenStanding() {
        float yaw = 0f;                                   // facing south
        assertEquals(0f, TunnelPose.followWalk(yaw, 0.0, 0.0), 1e-6);          // standing: unchanged
        float west = TunnelPose.followWalk(yaw, -0.2, 0.0);                    // walking west (yaw 90)
        assertTrue(west > 0f && west < 10f, "a small step towards 90, not a jump: " + west);
        float y = yaw;
        for (int i = 0; i < 150; i++) y = TunnelPose.followWalk(y, -0.2, 0.0);
        assertEquals(90f, y, 1f);                                              // it settles on the walking direction
    }
}
