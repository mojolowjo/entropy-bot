package io.github.mojolowjo.entropybot.watchview;

import io.github.mojolowjo.entropybot.engine.WatchCamera;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** watch tunnel cut (0.17.1: the bot's silhouette, not a cylinder): the maths the fragment shaders mirror, the words. */
class CutawayTest {
    static final double HW = 0.3, H = 1.8;      // a player's box

    /** The bot's box with its feet at fx fy fz: min x y z, max x y z. */
    static double[] box(double fx, double fy, double fz) {
        return new double[]{fx - HW, fy, fz - HW, fx + HW, fy + H, fz + HW};
    }

    static boolean cuts(double[] p, double[] cam, double[] b, double margin) {
        return Cutaway.cuts(p[0], p[1], p[2], cam[0], cam[1], cam[2], b[0], b[1], b[2], b[3], b[4], b[5], margin);
    }

    /** The 0.16.1 rule (a cylinder of radius r from the camera to the bot's middle), for the regression comparison. */
    static boolean oldCylinder(double[] p, double[] a, double[] b, double r) {
        double abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
        double t = ((p[0] - a[0]) * abx + (p[1] - a[1]) * aby + (p[2] - a[2]) * abz) / (abx * abx + aby * aby + abz * abz);
        if (t < 0 || t > 1) return false;
        double qx = a[0] + abx * t - p[0], qy = a[1] + aby * t - p[1], qz = a[2] + abz * t - p[2];
        return Math.sqrt(qx * qx + qy * qy + qz * qz) < r;
    }

    @Test
    void rayEntersTheBox() {
        assertEquals(4, Cutaway.enter(0, 0.5, -5, 0, 0, 1, -1, 0, -1, 1, 1, 1), 1e-9, "straight in through the near side");
        assertEquals(-1, Cutaway.enter(0, 3, -5, 0, 0, 1, -1, 0, -1, 1, 1, 1), 1e-9, "passes above");
        assertEquals(-1, Cutaway.enter(0, 0.5, 5, 0, 0, 1, -1, 0, -1, 1, 1, 1), 1e-9, "the box is behind");
        assertEquals(-1, Cutaway.enter(0, 0.5, 0, 0, 0, 1, -1, 0, -1, 1, 1, 1), 1e-9, "the origin inside counts as a miss");
        assertEquals(Math.sqrt(2) * 4, Cutaway.enter(-5, 0.5, -5, Math.sqrt(0.5), 0, Math.sqrt(0.5), -1, 0, -1, 1, 1, 1), 1e-9, "diagonal: the corner");
        assertTrue(Cutaway.enter(0, 5, 0, 0, -1, 0, -1, 0, -1, 1, 1, 1) == 4, "straight down, an axis-parallel ray");
    }

    /**
     * Every face fragment in front of the bot (on a line of sight from the camera to a point of its box, before the box)
     * is cut, with no margin at all, for random tunnel cameras (0.5-40 up, 1-8 back, any yaw) and bot places.
     */
    @Test
    void theBotIsNeverHidden() {
        Random rnd = new Random(11);
        for (int n = 0; n < 2000; n++) {
            double fx = rnd.nextDouble() * 100 - 50, fy = rnd.nextDouble() * 100 - 50, fz = rnd.nextDouble() * 100 - 50;
            double yaw = rnd.nextDouble() * Math.PI * 2, up = 0.5 + rnd.nextDouble() * 39.5, back = 1 + rnd.nextDouble() * 7;
            double[] cam = {fx - Math.sin(yaw) * back, fy + 1.62 + up, fz - Math.cos(yaw) * back};
            double[] b = box(fx, fy, fz);
            for (int k = 0; k < 40; k++) {
                double[] q = {fx + (rnd.nextDouble() * 2 - 1) * HW, fy + Cutaway.FEET_LIFT + rnd.nextDouble() * (H - Cutaway.FEET_LIFT), fz + (rnd.nextDouble() * 2 - 1) * HW};
                double vx = q[0] - cam[0], vy = q[1] - cam[1], vz = q[2] - cam[2], len = Math.sqrt(vx * vx + vy * vy + vz * vz);
                double entry = Cutaway.enter(cam[0], cam[1], cam[2], vx / len, vy / len, vz / len, b[0], b[1] + Cutaway.FEET_LIFT, b[2], b[3], b[4], b[5]);
                assertTrue(entry > 0, "the ray to a point of the box enters it");
                for (double s = 0.01; s < entry - Cutaway.EPS - 1e-6; s += 0.05) {
                    double[] p = {cam[0] + vx / len * s, cam[1] + vy / len * s, cam[2] + vz / len * s};
                    assertTrue(cuts(p, cam, b, 0), "a fragment in front of the bot was kept, " + s + " of " + entry);
                    assertTrue(cuts(p, cam, b, Cutaway.DEFAULT_RADIUS), "with the default margin too");
                }
            }
        }
    }

    /**
     * The owner's crescents: the ground round the bot (block tops drawn 0.004 above the feet) is never cut, from a steep
     * and from a shallow camera, while the 0.16.1 cylinder cut a large part of it.
     */
    @Test
    void theGroundIsNeverBitten() {
        double fx = 0.5, fy = 64, fz = 0.5;
        double[] b = box(fx, fy, fz);
        double[][] cams = {{0.5, fy + 1.62 + 4, -3.5}, {0.5, fy + 1.62 + 0.5, -5.5}, {3.5, fy + 1.62 + 12, -2.5}, {0.5, fy + 1.62 + 1.5, -1.5}};
        for (double[] cam : cams) {
            int oldCut = 0;
            for (double x = -6; x <= 6; x += 0.1)
                for (double z = -6; z <= 6; z += 0.1) {
                    double[] p = {fx + x, fy + 0.004, fz + z};
                    assertFalse(cuts(p, cam, b, Cutaway.MAX_RADIUS), "ground at " + x + " " + z + " cut from " + cam[1]);
                    if (oldCylinder(p, cam, new double[]{fx, fy + H / 2, fz}, 1.5)) oldCut++;
                }
            if (cam[1] - fy < 4) assertTrue(oldCut > 10,"the old cylinder did bite the ground from a low camera: " + oldCut);
        }
    }

    @Test
    void onlyWhatCoversTheBot() {
        double[] b = box(0.5, 64, 0.5);
        double[] cam = {0.5, 64 + 1.62 + 4, -3.5};
        double[] mid = {0.5, 64.9, 0.5};
        double[] half = {(cam[0] + mid[0]) / 2, (cam[1] + mid[1]) / 2, (cam[2] + mid[2]) / 2};
        double m = Cutaway.DEFAULT_RADIUS;
        assertTrue(cuts(half, cam, b, m), "half way on the line to the bot's middle: cut");
        assertFalse(cuts(new double[]{half[0] + 1.5, half[1], half[2]}, cam, b, m), "1.5 blocks beside the line half way: kept (the cylinder cut it)");
        assertTrue(oldCylinder(new double[]{half[0] + 1.4, half[1], half[2]}, cam, mid, 1.5), "for comparison: the old cylinder cut it");
        assertFalse(cuts(new double[]{0.5, 64.9, 2.0}, cam, b, m), "behind the bot: kept");
        assertFalse(cuts(new double[]{0.5, 64 + 1.62 + 5, -4.5}, cam, b, m), "behind the camera: kept");
        assertFalse(cuts(half, cam, b, -1), "margin < 0 (cut off): nothing is cut");
        // a trunk 1 block from the camera, off to the side of the line: kept (the 0.16.1 bite out of a trunk)
        double[] trunk = {cam[0] + 1.2, cam[1] - 0.5, cam[2] + 0.5};
        assertFalse(cuts(trunk, cam, b, m));
        assertTrue(oldCylinder(trunk, cam, mid, 1.5), "the old cylinder bit it");
        // the margin: a point just outside the silhouette is cut with a margin, kept without
        double[] edge = {half[0] + 0.3 + 0.25 * 0.6, half[1], half[2]};   // ~0.25 beside the silhouette at the bot's distance, scaled to half way
        assertTrue(cuts(edge, cam, b, 0.6));
        assertFalse(cuts(edge, cam, b, 0));
    }

    @Test
    void ditherSpreadsTheRim() {
        Set<Double> seen = new HashSet<>();
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++) {
                double d = Cutaway.dither(x, y);
                assertTrue(d > 0 && d < 1);
                seen.add(d);
                assertEquals(d, Cutaway.dither(x + 8, y - 4), 1e-12, "repeats every 4 pixels, also left of 0");
                double m = Cutaway.marginAt(1.0, x, y);
                assertTrue(m >= Cutaway.INNER_SHARE && m <= 1.0);
            }
        assertEquals(16, seen.size(), "16 levels");
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
        assertEquals(0, Cutaway.parseRadius("0"), 1e-12, "0 = exactly the bot's outline");
        assertEquals(0.5, Cutaway.parseRadius("0.5"), 1e-12);
        assertEquals(3, Cutaway.parseRadius("3"), 1e-12);
        assertEquals(-1, Cutaway.parseRadius("3.5"), 1e-12);
        assertEquals(-1, Cutaway.parseRadius("-1"), 1e-12);
        assertEquals(-1, Cutaway.parseRadius("wide"), 1e-12);
        assertTrue(Cutaway.DEFAULT_RADIUS > Cutaway.MIN_RADIUS && Cutaway.DEFAULT_RADIUS <= 1);
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

    /** The shader files ship with the jar and carry the cut uniforms and the cut-out rule (a missing file would only show live). */
    @Test
    void shaderFilesAreThere() throws Exception {
        for (String name : new String[]{CutShaders.TEX, CutShaders.FLAT}) {
            String base = "/assets/entropybot/shaders/core/" + name;
            for (String ext : new String[]{".json", ".vsh", ".fsh"}) {
                try (var in = CutawayTest.class.getResourceAsStream(base + ext)) {
                    assertNotNull(in, base + ext);
                    String s = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if (ext.equals(".json")) {
                        for (String u : new String[]{"CutCam", "CutMin", "CutMax", "CutMargin", "ModelViewMat", "ProjMat", "ColorModulator"}) assertTrue(s.contains("\"" + u + "\""), base + ext + " " + u);
                        assertTrue(s.contains("\"entropybot:" + name + "\""), "names its own programs");
                        assertFalse(s.contains("CutRadius"), "the 0.16.1 uniforms are gone");
                    }
                    if (ext.equals(".fsh")) {
                        assertTrue(s.contains("discard") && s.contains("CutMargin") && s.contains("cutHere()") && s.contains("in vec3 meshPos"), base + ext);
                        assertTrue(s.contains("lo.y += 0.1"), "the feet lift matches Cutaway.FEET_LIFT");
                        assertEquals(0.1, Cutaway.FEET_LIFT, 1e-12);
                    }
                    if (ext.equals(".fsh") && name.equals(CutShaders.TEX)) assertTrue(s.contains("tex.a < 0.1") && s.contains("solid"), "the cut-out and solid-texture rule");
                    if (!ext.equals(".json")) assertTrue(s.startsWith("#version 150"), base + ext);
                }
            }
        }
        assertEquals(1, TunnelMesh.ALPHA_SOLID % 2, "solid faces carry an odd alpha");
        assertEquals(0, TunnelMesh.ALPHA % 2, "cut-out faces an even one");
    }
}
