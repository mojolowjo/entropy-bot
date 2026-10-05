package io.github.mojolowjo.entropybot.watchview;

import io.github.mojolowjo.entropybot.engine.WatchCamera;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 0.16.1 watch tunnel cut: the capsule maths the fragment shader mirrors, the bot never hidden, the words. */
class CutawayTest {
    @Test
    void pointToSegment() {
        // A = origin, B = (10, 0, 0)
        assertEquals(0.5, Cutaway.along(5, 3, 0, 0, 0, 0, 10, 0, 0), 1e-12);
        assertEquals(3, Cutaway.toSegment(5, 3, 0, 0, 0, 0, 10, 0, 0), 1e-12, "beside the middle: the perpendicular");
        assertEquals(5, Cutaway.toSegment(-3, 4, 0, 0, 0, 0, 10, 0, 0), 1e-12, "before A: the distance to A");
        assertEquals(Math.sqrt(4 + 1), Cutaway.toSegment(12, 1, 0, 0, 0, 0, 10, 0, 0), 1e-12, "past B: the distance to B");
        assertEquals(Math.sqrt(2), Cutaway.toSegment(1, 1, 0, 0, 0, 0, 0, 0, 0), 1e-12, "A == B: the distance to the point");
        assertEquals(0, Cutaway.along(1, 1, 1, 2, 2, 2, 2, 2, 2), 1e-12);
    }

    @Test
    void cutsOnlyBetweenTheCameraAndTheBot() {
        double r = 1.5;
        // camera 4 up and 4 back (north) of the bot's middle at 0.5 1.9 0.5
        double ax = 0.5, ay = 5.9, az = -3.5, bx = 0.5, by = 1.9, bz = 0.5;
        assertTrue(Cutaway.cuts(0.5, 3.9, -1.5, ax, ay, az, bx, by, bz, r), "on the line half way: cut");
        assertTrue(Cutaway.cuts(1.5, 3.9, -1.5, ax, ay, az, bx, by, bz, r), "1 block beside it: cut");
        assertFalse(Cutaway.cuts(2.5, 3.9, -1.5, ax, ay, az, bx, by, bz, r), "2 blocks beside it: kept");
        assertFalse(Cutaway.cuts(0.5, 1.0, 1.5, ax, ay, az, bx, by, bz, r), "the floor beyond the bot: kept");
        assertFalse(Cutaway.cuts(0.5, 1.0, 0.5, ax, ay, az, bx, by, bz, r), "the floor right under the bot: kept (beyond its middle)");
        assertFalse(Cutaway.cuts(0.5, 6.5, -4.5, ax, ay, az, bx, by, bz, r), "behind the camera: kept");
        assertFalse(Cutaway.cuts(0.5, 3.9, -1.5, ax, ay, az, bx, by, bz, 0), "radius 0: nothing is cut");
    }

    /**
     * Every face fragment in front of the bot (on a line of sight from the camera to any point of its box) is cut, with
     * the smallest radius allowed, for random camera places (the tunnel camera: 1-40 up, 1-8 back) and bot boxes.
     */
    @Test
    void theBotIsNeverHidden() {
        Random rnd = new Random(11);
        double hw = 0.3, h = 1.8;                                // a player's box
        for (int n = 0; n < 2000; n++) {
            double fx = rnd.nextDouble() * 100 - 50, fy = rnd.nextDouble() * 100 - 50, fz = rnd.nextDouble() * 100 - 50;   // feet
            double yaw = rnd.nextDouble() * Math.PI * 2, up = 1 + rnd.nextDouble() * 39, back = 1 + rnd.nextDouble() * 7;
            double ax = fx + 0.0 - Math.sin(yaw) * back, ay = fy + 1.62 + up, az = fz - Math.cos(yaw) * back;
            double bx = fx, by = fy + h / 2, bz = fz;
            for (int k = 0; k < 40; k++) {
                double qx = fx + (rnd.nextDouble() * 2 - 1) * hw, qy = fy + rnd.nextDouble() * h, qz = fz + (rnd.nextDouble() * 2 - 1) * hw;
                for (double s = 0.02; s < 0.999; s += 0.037) {
                    double px = ax + (qx - ax) * s, py = ay + (qy - ay) * s, pz = az + (qz - az) * s;
                    if (Cutaway.cuts(px, py, pz, ax, ay, az, bx, by, bz, Cutaway.MIN_RADIUS)) continue;
                    // not cut: it must lie beyond the bot's middle (t > 1), i.e. inside the bot's own box depth, not in front of it
                    double t = Cutaway.along(px, py, pz, ax, ay, az, bx, by, bz);
                    assertTrue(t > 1, "a fragment in front of the bot was kept: t " + t);
                    double dx = px - bx, dy = py - by, dz = pz - bz;
                    assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) <= Math.sqrt(hw * hw * 2 + (h / 2) * (h / 2)) + 1e-9,
                            "kept only within the bot's own box");
                }
            }
        }
    }

    @Test
    void words() {
        assertEquals(Boolean.FALSE, Cutaway.parseCut("cut", true), "a bare word toggles");
        assertEquals(Boolean.TRUE, Cutaway.parseCut("cut", false));
        assertEquals(Boolean.TRUE, Cutaway.parseCut("cut on", false));
        assertEquals(Boolean.FALSE, Cutaway.parseCut("cut off", true));
        assertNull(Cutaway.parseCut("cut radius 2", true));
        assertNull(Cutaway.parseCut("dollhouse", true));
        assertEquals(2, Cutaway.parseRadius(" 2"), 1e-12);
        assertEquals(1, Cutaway.parseRadius("1"), 1e-12);
        assertEquals(6, Cutaway.parseRadius("6"), 1e-12);
        assertEquals(-1, Cutaway.parseRadius("0.5"), 1e-12);
        assertEquals(-1, Cutaway.parseRadius("7"), 1e-12);
        assertEquals(-1, Cutaway.parseRadius("wide"), 1e-12);
        assertTrue(Cutaway.DEFAULT_RADIUS >= Cutaway.MIN_RADIUS && Cutaway.DEFAULT_RADIUS <= 2);
        assertNull(WatchCamera.parseDollhouse("cut", true), "the dollhouse words do not take the cut");
    }

    @Test
    void checkFinding() {
        assertNull(WatchChecks.cutProblem(false, "failed"), "cut off: nothing to report");
        assertNull(WatchChecks.cutProblem(true, null), "shader fine");
        String p = WatchChecks.cutProblem(true, "watch_cut_tex: did not link");
        assertNotNull(p);
        assertTrue(p.contains("did not link") && p.contains("draws every face"), p);
    }

    /** The shader files ship with the jar and carry the cut uniforms (a missing file would only show live). */
    @Test
    void shaderFilesAreThere() throws Exception {
        for (String name : new String[]{CutShaders.TEX, CutShaders.FLAT}) {
            String base = "/assets/entropybot/shaders/core/" + name;
            for (String ext : new String[]{".json", ".vsh", ".fsh"}) {
                try (var in = CutawayTest.class.getResourceAsStream(base + ext)) {
                    assertNotNull(in, base + ext);
                    String s = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if (ext.equals(".json")) {
                        for (String u : new String[]{"CutA", "CutB", "CutRadius", "ModelViewMat", "ProjMat", "ColorModulator"}) assertTrue(s.contains("\"" + u + "\""), base + ext + " " + u);
                        assertTrue(s.contains("\"entropybot:" + name + "\""), "names its own programs");
                    }
                    if (ext.equals(".fsh")) assertTrue(s.contains("discard") && s.contains("CutRadius"), base + ext);
                    if (!ext.equals(".json")) assertTrue(s.startsWith("#version 150"), base + ext);
                }
            }
        }
    }
}
