package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Camera v2's camera: above and behind the bot, never inside rock, never in air the bot doesn't know. */
class TunnelPoseTest {
    /** ok = open and (sky-lit or known), like TunnelView.cameraOk. */
    static TunnelPose.CellOk ok(GridWorld w, Set<Long> known) {
        return (x, y, z) -> w.kind(x, y, z) == Shell.OPEN && (w.sky(x, y, z) || known.contains(CellKey.of(x, y, z)));
    }

    @Test
    void inTheOpenItSitsWhereWanted() {
        GridWorld w = new GridWorld();
        w.skyY = 64;
        TunnelPose.Pose p = TunnelPose.place(0.5, 65.6, 0.5, 0f, 12, 6, 48, ok(w, Set.of()));
        assertEquals("above", p.how());
        assertEquals(77.6, p.y(), 1e-9);
        assertEquals(0.5 - 6, p.z(), 1e-9, "behind = north of a bot looking south (yaw 0)");
        assertEquals(0f, p.yaw(), 0.01f, "looking south at the bot");
        assertTrue(p.pitch() > 60f && p.pitch() < 65f, "looking down: " + p.pitch());
    }

    @Test
    void underGroundItClimbsOutIntoTheSky() {
        GridWorld w = new GridWorld().openBox(0, 10, 0, 4, 11, 0);
        w.skyY = 40;
        Set<Long> known = new HashSet<>(w.open);
        TunnelPose.Pose p = TunnelPose.place(0.5, 11.6, 0.5, 0f, 12, 6, 48, ok(w, known));
        assertTrue(p.how().startsWith("raised"), p.how());
        assertTrue(p.y() - TunnelPose.NEAR >= 40, "the whole box in the sky: " + p.y());
        assertTrue(TunnelPose.boxOk(p.x(), p.y(), p.z(), ok(w, known)));
    }

    @Test
    void tooDeepItPullsInAlongTheLineLikeVanilla() {
        GridWorld w = new GridWorld().openBox(-1, 10, -8, 1, 13, 1);   // a known room round the bot
        w.skyY = 200;
        Set<Long> known = new HashSet<>(w.open);
        TunnelPose.Pose p = TunnelPose.place(0.5, 11.6, 0.5, 0f, 12, 6, 48, ok(w, known));
        assertEquals("pulled in", p.how());
        assertTrue(TunnelPose.boxOk(p.x(), p.y(), p.z(), ok(w, known)));
        assertTrue(p.y() > 11.6 && p.y() + TunnelPose.NEAR < 14.0, "stopped under the ceiling (rock from y 14): " + p.y());
    }

    @Test
    void anUnknownCaveIsNoPlaceForTheCamera() {
        GridWorld w = new GridWorld().openBox(0, 10, 0, 0, 11, 0);      // the bot's cell
        w.openBox(-3, 20, -9, 3, 26, -3);                               // a cave right where the camera wants to be
        w.skyY = 300;
        Set<Long> known = new HashSet<>();
        known.add(CellKey.of(0, 10, 0));
        known.add(CellKey.of(0, 11, 0));
        TunnelPose.Pose p = TunnelPose.place(0.5, 11.6, 0.5, 0f, 12, 6, 48, ok(w, known));
        int cx = (int) Math.floor(p.x()), cy = (int) Math.floor(p.y()), cz = (int) Math.floor(p.z());
        assertFalse(cy >= 20 && cy <= 26 && cz <= -3, "not in the unknown cave: " + p);
        assertTrue(TunnelPose.boxOk(p.x(), p.y(), p.z(), ok(w, known)));
    }

    @Test
    void randomWorldsNeverPutTheCameraInRock() {
        Random rnd = new Random(11);
        for (int round = 0; round < 200; round++) {
            GridWorld w = new GridWorld();
            w.skyY = 20 + rnd.nextInt(40);
            for (int i = 0; i < 400; i++) w.open(rnd.nextInt(20) - 10, rnd.nextInt(30), rnd.nextInt(20) - 10);
            w.openBox(0, 10, 0, 0, 11, 0);
            Set<Long> known = new HashSet<>();
            for (long k : w.open) if (rnd.nextInt(3) == 0) known.add(k);
            known.add(CellKey.of(0, 10, 0));
            known.add(CellKey.of(0, 11, 0));
            TunnelPose.CellOk ok = ok(w, known);
            float yaw = rnd.nextInt(8) * 45f;
            TunnelPose.Pose p = TunnelPose.place(0.5, 11.62, 0.5, yaw, 4 + rnd.nextInt(20), 4 + rnd.nextInt(5), 48, ok);
            assertTrue(TunnelPose.boxOk(p.x(), p.y(), p.z(), ok), "round " + round + ": " + p);
            TunnelPose.Pose q = TunnelPose.follow(p, TunnelPose.place(0.5, 11.62, 0.5, yaw, 10, 6, 48, ok), 0.15, 0.5, 11.62, 0.5, ok);
            assertTrue(TunnelPose.boxOk(q.x(), q.y(), q.z(), ok), "smoothing too: " + q);
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
    void followJumpsOnATeleport() {
        TunnelPose.CellOk any = (x, y, z) -> true;
        TunnelPose.Pose a = new TunnelPose.Pose(0, 0, 0, 0, 0, "above");
        TunnelPose.Pose b = new TunnelPose.Pose(100, 0, 0, 0, 0, "above");
        assertEquals(100, TunnelPose.follow(a, b, 0.15, 100, -5, 0, any).x(), 1e-9);
        TunnelPose.Pose c = new TunnelPose.Pose(10, 0, 0, 0, 0, "above");
        assertEquals(1.5, TunnelPose.follow(a, c, 0.15, 10, -5, 0, any).x(), 1e-9);
        assertSame(c, TunnelPose.follow(null, c, 0.15, 0, 0, 0, any));
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

    @org.junit.jupiter.api.Test
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
