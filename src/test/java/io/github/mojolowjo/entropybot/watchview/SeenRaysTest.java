package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SeenRaysTest {
    /** Solid (HIT) everywhere except the cells set here. */
    static final class Cells implements SeenRays.Cells {
        final Map<Long, Integer> set = new HashMap<>();

        Cells put(int x, int y, int z, int kind) {
            set.put(CellKey.of(x, y, z), kind);
            return this;
        }

        Cells box(int x0, int y0, int z0, int x1, int y1, int z1, int kind) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) put(x, y, z, kind);
            return this;
        }

        @Override
        public int cell(int x, int y, int z) {
            return set.getOrDefault(CellKey.of(x, y, z), SeenRays.HIT);
        }
    }

    static final double FOV = 87, ASPECT = 16.0 / 9;

    private static double angle(double[] a, double[] b) {
        double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, d))));
    }

    @Test
    void lookVectorsAndBasis() {
        assertArrayEquals(new double[]{0, 0, 1}, SeenRays.forward(0, 0), 1e-9);          // yaw 0 = south
        assertArrayEquals(new double[]{-1, 0, 0}, SeenRays.forward(90, 0), 1e-9);        // yaw 90 = west
        assertArrayEquals(new double[]{0, -1, 0}, SeenRays.forward(0, 90), 1e-9);        // pitch 90 = down
        double[][] b = SeenRays.basis(0, 0);
        assertArrayEquals(new double[]{-1, 0, 0}, b[1], 1e-9, "facing south, the right hand points west");
        assertArrayEquals(new double[]{0, 1, 0}, b[2], 1e-9);
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            double[][] q = SeenRays.basis(r.nextFloat() * 720 - 360, r.nextFloat() * 180 - 90);
            for (int a = 0; a < 3; a++) {
                assertEquals(1, Math.sqrt(q[a][0] * q[a][0] + q[a][1] * q[a][1] + q[a][2] * q[a][2]), 1e-9);
                for (int c = a + 1; c < 3; c++) assertEquals(0, q[a][0] * q[c][0] + q[a][1] * q[c][1] + q[a][2] * q[c][2], 1e-9);
            }
        }
    }

    @Test
    void rayDirectionsSpanTheFieldOfView() {
        double[][] b = SeenRays.basis(30, 20);
        double t = SeenRays.tanHalf(FOV);
        assertArrayEquals(b[0], SeenRays.direction(b, t, ASPECT, 0, 0), 1e-9, "the centre ray is the look vector");
        assertEquals(FOV / 2, angle(b[0], SeenRays.direction(b, t, ASPECT, 0, 1)), 1e-6, "top edge: half the vertical FOV");
        assertEquals(FOV / 2, angle(b[0], SeenRays.direction(b, t, ASPECT, 0, -1)), 1e-6);
        double hHalf = Math.toDegrees(Math.atan(ASPECT * t));
        assertEquals(hHalf, angle(b[0], SeenRays.direction(b, t, ASPECT, 1, 0)), 1e-6, "right edge: the horizontal half-FOV");
        double[] right = SeenRays.direction(b, t, ASPECT, 1, 0);
        assertTrue(right[0] * b[1][0] + right[1] * b[1][1] + right[2] * b[1][2] > 0, "+sx is to the right");
        // the jittered grid stays inside the view
        int[] g = SeenRays.grid(480, ASPECT);
        assertTrue(Math.abs(g[0] * g[1] - 480) < 40, "about 480 rays: " + g[0] + "x" + g[1]);
        assertEquals(ASPECT, (double) g[0] / g[1], 0.2);
        Random r = new Random(7);
        for (int row = 0; row < g[1]; row++)
            for (int col = 0; col < g[0]; col++) {
                double[] sp = SeenRays.jittered(col, row, g[0], g[1], r.nextDouble(), r.nextDouble());
                assertTrue(sp[0] >= -1 && sp[0] <= 1 && sp[1] >= -1 && sp[1] <= 1);
                double[] d = SeenRays.direction(b, t, ASPECT, sp[0], sp[1]);
                assertTrue(SeenRays.inView(b, t, ASPECT, 0, 0, 0, d[0] * 10, d[1] * 10, d[2] * 10));
            }
        assertFalse(SeenRays.inView(b, t, ASPECT, 0, 0, 0, -b[0][0], -b[0][1], -b[0][2]), "behind the eye");
        // the budget order visits every cell once, and its first half covers top and bottom rows alike
        for (int n : new int[]{1, 2, 97, 480, 476, 9409, 500}) {
            int s = SeenRays.stride(n);
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int i = 0; i < n; i++) seen.add((int) ((13 + (long) i * s) % n));
            assertEquals(n, seen.size(), "n " + n);
        }
        int n = g[0] * g[1], s = SeenRays.stride(n), lastRowHits = 0;
        for (int i = 0; i < n / 2; i++) if (((int) (((long) i * s) % n)) / g[0] == g[1] - 1) lastRowHits++;
        assertTrue(lastRowHits > g[0] / 4, "half a tick still reaches the bottom row: " + lastRowHits);
    }

    /** A corridor along +z: x 0, y 0..1, z 0..len-1 open; rock round it. */
    static Cells corridor(int len) {
        return new Cells().box(0, 0, 0, 0, 1, len - 1, SeenRays.PASS);
    }

    @Test
    void firstHitIsTheRightBlockAndSide() {
        Cells w = corridor(21);
        SeenRays.Hit h = SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, w, SeenRays.MAX_WATER);
        assertNotNull(h);
        assertEquals(List.of(0, 1, 21, 2), List.of(h.x(), h.y(), h.z(), h.side()), "the end wall's north face");
        assertEquals(20.5, h.dist(), 1e-9);
        assertEquals(20, h.airZ());
        h = SeenRays.cast(0.5, 1.62, 0.5, 0, -1, 0, 80, w, SeenRays.MAX_WATER);
        assertEquals(List.of(0, -1, 0, 1), List.of(h.x(), h.y(), h.z(), h.side()), "the floor's top face");
        h = SeenRays.cast(0.5, 1.62, 0.5, 1, 0, 0, 80, w, SeenRays.MAX_WATER);
        assertEquals(List.of(1, 1, 0, 4), List.of(h.x(), h.y(), h.z(), h.side()), "the east wall's west face");
        h = SeenRays.cast(0.5, 1.62, 0.5, 0, 1, 0, 80, w, SeenRays.MAX_WATER);
        assertEquals(List.of(0, 2, 0, 0), List.of(h.x(), h.y(), h.z(), h.side()), "the ceiling's bottom face");
        assertNull(SeenRays.cast(0.5, 0.5, 0.5, 0, 0, 1, 80, new Cells(), 4), "an eye inside rock sees nothing");
    }

    @Test
    void rangeStopsAtTheRenderDistance() {
        Cells w = corridor(21);
        assertNull(SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 20.4, w, 4), "the wall is 20.5 away");
        assertNotNull(SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 20.6, w, 4));
        // render distance 5 chunks = 80 blocks: a wall 90 blocks off is never reached, one 70 off is
        double rd5 = 5 * 16;
        assertNull(SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, rd5, corridor(90), 4));
        assertNotNull(SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, rd5, corridor(70), 4));
        assertNotNull(SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 8 * 16, corridor(90), 4), "render distance 8 reaches it");
    }

    @Test
    void glassStopsWaterPassesFourBlocks() {
        Cells w = corridor(21).put(0, 1, 5, SeenRays.STOP);
        assertNull(SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, w, 4), "glass ends the ray and records nothing");
        Cells water4 = corridor(21).box(0, 1, 1, 0, 1, 4, SeenRays.WATER);
        assertNotNull(SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, water4, 4), "4 water cells: seen through");
        Cells water5 = corridor(21).box(0, 1, 1, 0, 1, 5, SeenRays.WATER);
        assertNull(SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, water5, 4), "5 water cells: too deep");
    }

    @Test
    void neverSlipsDiagonallyBetweenTwoBlocks() {
        Cells w = new Cells().put(0, 0, 0, SeenRays.PASS).box(1, 0, 1, 5, 0, 5, SeenRays.PASS);
        // (1,0,0) and (0,0,1) are rock; the ray passes exactly through their shared edge
        SeenRays.Hit h = SeenRays.cast(0.5, 0.5, 0.5, 1, 0, 1, 80, w, 4);
        assertNotNull(h);
        assertTrue((h.x() == 1 && h.z() == 0) || (h.x() == 0 && h.z() == 1), "stopped at the edge: " + h);
    }

    /** Every ray of every view from inside the corridor, plus the near pass: the hits. */
    static List<SeenRays.Hit> everything(Cells w, double ex, double ey, double ez) {
        List<SeenRays.Hit> out = new ArrayList<>();
        Random r = new Random(3);
        double t = SeenRays.tanHalf(FOV);
        for (int yaw = 0; yaw < 360; yaw += 15)
            for (int pitch = -90; pitch <= 90; pitch += 15) {
                double[][] b = SeenRays.basis(yaw, pitch);
                int[] g = SeenRays.grid(480, ASPECT);
                for (int row = 0; row < g[1]; row++)
                    for (int col = 0; col < g[0]; col++) {
                        double[] sp = SeenRays.jittered(col, row, g[0], g[1], r.nextDouble(), r.nextDouble());
                        double[] d = SeenRays.direction(b, t, ASPECT, sp[0], sp[1]);
                        SeenRays.Hit h = SeenRays.cast(ex, ey, ez, d[0], d[1], d[2], 80, w, 4);
                        if (h != null) out.add(h);
                    }
                SeenRays.near(ex, ey, ez, b, t, ASPECT, 5, 80, w, (x, y, z, s, dist) -> out.add(new SeenRays.Hit(x, y, z, s, dist)));
            }
        return out;
    }

    @Test
    void anOreBehindATunnelWallIsNeverSeen() {
        Cells w = corridor(11);                                   // the ore at (2,1,5) sits behind the east wall (1,1,5)
        List<SeenRays.Hit> hits = everything(w, 0.5, 1.62, 5.5);
        assertTrue(hits.size() > 1000);
        for (SeenRays.Hit h : hits) {
            assertTrue(h.x() >= -1 && h.x() <= 1, "only the corridor's own walls: " + h);
            assertEquals(SeenRays.PASS, w.cell(h.airX(), h.airY(), h.airZ()), "every face looks into open air: " + h);
        }
        assertTrue(hits.stream().anyMatch(h -> h.x() == 1 && h.y() == 1 && h.z() == 5 && h.side() == 4), "the wall in front of the ore is seen");
    }

    @Test
    void aFaceSeenThroughAOneBlockHoleIsRecorded() {
        Cells w = corridor(11).put(1, 1, 5, SeenRays.PASS).box(2, 0, 3, 4, 3, 7, SeenRays.PASS);   // a hole into a room
        SeenRays.Hit h = SeenRays.cast(0.5, 1.5, 5.5, 1, 0, 0, 80, w, 4);
        assertEquals(List.of(5, 1, 5, 4), List.of(h.x(), h.y(), h.z(), h.side()), "the room's far wall through the hole");
        List<SeenRays.Hit> hits = everything(w, 0.5, 1.62, 5.5);
        assertTrue(hits.stream().anyMatch(x -> x.x() == 5), "the far wall of the room is seen through the hole");
        double[][] b = SeenRays.basis(270, 0);                   // facing east (+x)
        List<int[]> near = new ArrayList<>();
        SeenRays.near(0.5, 1.5, 5.5, b, SeenRays.tanHalf(FOV), ASPECT, 5, 80, w, (x, y, z, s, d) -> near.add(new int[]{x, y, z, s}));
        assertTrue(near.stream().anyMatch(f -> f[0] == 5 && f[1] == 1 && f[2] == 5 && f[3] == 4), "the near pass finds it too");
        for (int[] f : near) assertTrue(FaceGeometry.facesCamera(f[0], f[1], f[2], f[3], 0.5, 1.5, 5.5), "only faces turned to the eye");
    }

    @Test
    void nearPassOnlyFacesInView() {
        Cells w = corridor(11);
        double[][] b = SeenRays.basis(0, 0);                     // facing south (+z)
        List<int[]> near = new ArrayList<>();
        SeenRays.near(0.5, 1.62, 5.5, b, SeenRays.tanHalf(FOV), ASPECT, 5, 80, w, (x, y, z, s, d) -> near.add(new int[]{x, y, z, s}));
        assertFalse(near.isEmpty());
        for (int[] f : near) assertTrue(f[2] >= 5, "nothing behind the bot: " + f[0] + " " + f[1] + " " + f[2]);
    }
}
