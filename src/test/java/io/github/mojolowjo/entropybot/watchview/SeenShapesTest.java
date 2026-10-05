package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.16.1: the first-hit walk with non-full blocks, glass, water surfaces and leaves; the near pass in slices. */
class SeenShapesTest {
    static final double FOV = 87, ASPECT = 16.0 / 9;

    /** A box shape inside cell (cx,cy,cz): fractions min x y z, max x y z. The side the ray enters it by, or -1 (slab method). */
    static SeenRays.ShapeHit box(int cx, int cy, int cz, double... f) {
        return (x, y, z, ex, ey, ez, dx, dy, dz, tIn, tOut) -> {
            if (x != cx || y != cy || z != cz) return -1;
            double[] e = {ex, ey, ez}, d = {dx, dy, dz};
            double[] lo = {cx + f[0], cy + f[1], cz + f[2]}, hi = {cx + f[3], cy + f[4], cz + f[5]};
            int[] lowSide = {4, 0, 2}, highSide = {5, 1, 3};
            double tmin = Double.NEGATIVE_INFINITY, tmax = Double.POSITIVE_INFINITY;
            int side = -1;
            for (int a = 0; a < 3; a++) {
                if (d[a] == 0) {
                    if (e[a] < lo[a] || e[a] > hi[a]) return -1;
                    continue;
                }
                double t1 = (lo[a] - e[a]) / d[a], t2 = (hi[a] - e[a]) / d[a];
                double near = Math.min(t1, t2);
                if (near > tmin) {
                    tmin = near;
                    side = d[a] > 0 ? lowSide[a] : highSide[a];
                }
                tmax = Math.min(tmax, Math.max(t1, t2));
            }
            if (tmax < tmin || tmax < tIn - 1e-9 || tmin > tOut + 1e-9) return -1;
            return side;
        };
    }

    static final class Faces implements SeenRays.Sink {
        final List<int[]> got = new ArrayList<>();

        @Override
        public void face(int x, int y, int z, int side, double dist) { got.add(new int[]{x, y, z, side}); }

        boolean has(int x, int y, int z, int s) { return got.stream().anyMatch(f -> f[0] == x && f[1] == y && f[2] == z && f[3] == s); }
    }

    @Test
    void leavesAreRecordedAndStopTheRay() {
        SeenRaysTest.Cells w = SeenRaysTest.corridor(21).put(0, 1, 5, SeenRays.SHAPE);
        Faces through = new Faces();
        SeenRays.Hit h = SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, w, 4, null, through);
        assertEquals(List.of(0, 1, 5, 2), List.of(h.x(), h.y(), h.z(), h.side()), "the leaves' north face");
        assertTrue(through.got.isEmpty());
        assertNull(SeenRays.cast(0.5, 1.5, 0.5, 0, 0, 1, 80, new SeenRaysTest.Cells().put(0, 1, 0, SeenRays.SHAPE), 4, null, null), "an eye inside leaves sees nothing");
    }

    @Test
    void glassIsRecordedAndSeenThrough() {
        SeenRaysTest.Cells w = SeenRaysTest.corridor(21).put(0, 1, 5, SeenRays.GLASS).put(0, 1, 6, SeenRays.GLASS);
        Faces through = new Faces();
        SeenRays.Hit h = SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, w, 4, null, through);
        assertEquals(List.of(0, 1, 21, 2), List.of(h.x(), h.y(), h.z(), h.side()), "the end wall, seen through the glass");
        assertEquals(1, through.got.size(), "two glass blocks in a row: one face (the game shows none between them)");
        assertTrue(through.has(0, 1, 5, 2));
        SeenRaysTest.Cells thick = SeenRaysTest.corridor(21).box(0, 1, 3, 0, 1, 7, SeenRays.GLASS);   // 5 deep
        Faces t2 = new Faces();
        assertNull(SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, thick, 4, null, t2), "more than " + SeenRays.MAX_GLASS + " glass: the ray ends");
        assertTrue(t2.has(0, 1, 3, 2), "but the glass face it met is still recorded");
        // a pane (thin, z 7/16..9/16) the ray passes beside, then a second pane it hits
        SeenRaysTest.Cells panes = SeenRaysTest.corridor(21).put(0, 1, 5, SeenRays.GLASS_PARTIAL);
        Faces t3 = new Faces();
        SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, panes, 4, box(0, 1, 5, 0, 0, 7 / 16.0, 1, 1, 9 / 16.0), t3);
        assertTrue(t3.has(0, 1, 5, 2), "the pane's north side");
        Faces t4 = new Faces();
        SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, panes, 4, box(0, 1, 5, 0, 0, 7 / 16.0, 0.2, 1, 9 / 16.0), t4);
        assertTrue(t4.got.isEmpty(), "a pane post the ray passes beside: nothing");
    }

    @Test
    void waterSurfaceIsRecordedOnce() {
        SeenRaysTest.Cells w = SeenRaysTest.corridor(21).box(0, 1, 3, 0, 1, 4, SeenRays.WATER);
        Faces through = new Faces();
        SeenRays.Hit h = SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, w, 4, null, through);
        assertNotNull(h);
        assertEquals(21, h.z());
        assertEquals(1, through.got.size());
        assertTrue(through.has(0, 1, 3, 2), "the face of the water the ray entered");
        Faces deep = new Faces();
        assertNull(SeenRays.cast(0.5, 1.62, 0.5, 0, 0, 1, 80, SeenRaysTest.corridor(21).box(0, 1, 3, 0, 1, 9, SeenRays.WATER), 4, null, deep));
        assertTrue(deep.has(0, 1, 3, 2), "too deep to see through, but its surface is seen");
    }

    /** A room x 0..10, y 0..3, z 0..10 with a bottom slab at (5,0,5). */
    static SeenRaysTest.Cells room() {
        return new SeenRaysTest.Cells().box(0, 0, 0, 10, 3, 10, SeenRays.PASS).put(5, 0, 5, SeenRays.PARTIAL);
    }

    @Test
    void aSlabIsHitOnlyWhereItsShapeIs() {
        SeenRays.ShapeHit slab = box(5, 0, 5, 0, 0, 0, 1, 0.5, 1);
        SeenRays.Hit over = SeenRays.cast(5.5, 0.8, 0.5, 0, 0, 1, 80, room(), 4, slab, null);
        assertEquals(List.of(5, 0, 11, 2), List.of(over.x(), over.y(), over.z(), over.side()), "a ray over the slab goes on to the wall");
        SeenRays.Hit low = SeenRays.cast(5.5, 0.3, 0.5, 0, 0, 1, 80, room(), 4, slab, null);
        assertEquals(List.of(5, 0, 5, 2), List.of(low.x(), low.y(), low.z(), low.side()), "a low ray meets the slab's side");
        SeenRays.Hit top = SeenRays.cast(5.5, 1.5, 3.5, 0, -1, 2, 80, room(), 4, slab, null);
        assertEquals(List.of(5, 0, 5, 1), List.of(top.x(), top.y(), top.z(), top.side()), "from above: its top, at half height");
        SeenRays.Hit noShapes = SeenRays.cast(5.5, 0.8, 0.5, 0, 0, 1, 80, room(), 4, null, null);
        assertEquals(5, noShapes.z(), "without a shape test a partial block counts as its whole cube");
    }

    /** Every view from the eye: hits plus through-faces. */
    static List<int[]> everything(SeenRaysTest.Cells w, double ex, double ey, double ez, SeenRays.ShapeHit shapes) {
        List<int[]> out = new ArrayList<>();
        Faces through = new Faces();
        Random r = new Random(5);
        double t = SeenRays.tanHalf(FOV);
        for (int yaw = 0; yaw < 360; yaw += 20)
            for (int pitch = -90; pitch <= 90; pitch += 20) {
                double[][] b = SeenRays.basis(yaw, pitch);
                int[] g = SeenRays.grid(480, ASPECT);
                for (int row = 0; row < g[1]; row++)
                    for (int col = 0; col < g[0]; col++) {
                        double[] sp = SeenRays.jittered(col, row, g[0], g[1], r.nextDouble(), r.nextDouble());
                        double[] d = SeenRays.direction(b, t, ASPECT, sp[0], sp[1]);
                        SeenRays.Hit h = SeenRays.cast(ex, ey, ez, d[0], d[1], d[2], 80, w, 4, shapes, through);
                        if (h != null) out.add(new int[]{h.x(), h.y(), h.z(), h.side()});
                    }
                SeenRays.nearSlice(ex, ey, ez, b, t, ASPECT, 5, 80, w, shapes, through, 0, null);
            }
        out.addAll(through.got);
        return out;
    }

    @Test
    void nothingBehindLeavesOrAWallIsEverRecorded() {
        // corridor x 0, y 0..1, z 0..10; a full block of leaves across it at z 5; an ore behind the east wall at (2,1,3)
        SeenRaysTest.Cells w = SeenRaysTest.corridor(11).put(0, 0, 5, SeenRays.SHAPE).put(0, 1, 5, SeenRays.SHAPE);
        List<int[]> hits = everything(w, 0.5, 1.62, 1.5, null);
        assertTrue(hits.size() > 1000);
        for (int[] f : hits) {
            assertTrue(f[2] <= 5, "nothing beyond the leaves: " + java.util.Arrays.toString(f));
            assertTrue(f[0] >= -1 && f[0] <= 1, "nothing behind the walls: " + java.util.Arrays.toString(f));
        }
        assertTrue(hits.stream().anyMatch(f -> f[0] == 0 && f[1] == 1 && f[2] == 5 && f[3] == 2), "the leaves are seen");
        // glass in the east wall at (1,1,3): the room behind it is seen through it, the rock round the window is not
        SeenRaysTest.Cells win = SeenRaysTest.corridor(11).put(1, 1, 3, SeenRays.GLASS).box(2, 0, 1, 4, 2, 5, SeenRays.PASS);
        List<int[]> seen = everything(win, 0.5, 1.62, 3.5, null);
        assertTrue(seen.stream().anyMatch(f -> f[0] == 1 && f[1] == 1 && f[2] == 3 && f[3] == 4), "the glass face");
        assertTrue(seen.stream().anyMatch(f -> f[0] == 5), "the room's far wall through the window");
        for (int[] f : seen) {
            int[] o = Shell.OFF[f[3]];
            int front = win.cell(f[0] + o[0], f[1] + o[1], f[2] + o[2]);
            assertTrue(front == SeenRays.PASS || front == SeenRays.GLASS || (f[0] == 1 && f[1] == 1 && f[2] == 3),
                    "every face looks into air or glass: " + java.util.Arrays.toString(f));
        }
    }

    @Test
    void theNearPassInSlicesFindsTheSameFaces() {
        SeenRaysTest.Cells w = room();
        double[][] b = SeenRays.basis(0, 30);
        double t = SeenRays.tanHalf(FOV);
        Set<String> whole = new HashSet<>();
        SeenRays.near(5.5, 1.62, 2.5, b, t, ASPECT, 5, 80, w, (x, y, z, s, d) -> whole.add(x + " " + y + " " + z + " " + s));
        assertFalse(whole.isEmpty());
        Set<String> sliced = new HashSet<>();
        int from = 0, slices = 0;
        do {
            from = SeenRays.nearSlice(5.5, 1.62, 2.5, b, t, ASPECT, 5, 80, w, null, (x, y, z, s, d) -> sliced.add(x + " " + y + " " + z + " " + s), from, () -> true);
            slices++;
            assertTrue(slices < 10_000);
        } while (from >= 0);
        assertEquals(whole, sliced);
        assertTrue(slices > 10, "a budget that is always over still makes progress, 16 cells a slice: " + slices);
        assertEquals(11 * 11 * 11, SeenRays.nearCells(5));
    }

    @Test
    void whatStillShowsAtMeshTime() {
        assertTrue(SeenRays.stillShows(SeenRays.HIT, SeenRays.PASS));
        assertTrue(SeenRays.stillShows(SeenRays.HIT, SeenRays.WATER), "a wall under water");
        assertTrue(SeenRays.stillShows(SeenRays.HIT, SeenRays.GLASS), "a wall behind glass");
        assertTrue(SeenRays.stillShows(SeenRays.HIT, SeenRays.PARTIAL), "a wall above a slab");
        assertFalse(SeenRays.stillShows(SeenRays.HIT, SeenRays.HIT), "covered since");
        assertFalse(SeenRays.stillShows(SeenRays.HIT, SeenRays.SHAPE), "leaves in front since");
        assertFalse(SeenRays.stillShows(SeenRays.HIT, SeenRays.STOP), "not loaded");
        assertFalse(SeenRays.stillShows(SeenRays.PASS, SeenRays.PASS), "mined since");
        assertFalse(SeenRays.stillShows(SeenRays.STOP, SeenRays.PASS));
        assertTrue(SeenRays.stillShows(SeenRays.SHAPE, SeenRays.PASS), "leaves");
        assertTrue(SeenRays.stillShows(SeenRays.PARTIAL, SeenRays.PASS), "a slab");
        assertTrue(SeenRays.stillShows(SeenRays.WATER, SeenRays.PASS), "a water surface");
        assertFalse(SeenRays.stillShows(SeenRays.WATER, SeenRays.WATER), "water next to water: no face");
        assertTrue(SeenRays.stillShows(SeenRays.GLASS, SeenRays.PASS));
        assertFalse(SeenRays.stillShows(SeenRays.GLASS, SeenRays.GLASS_PARTIAL), "glass next to glass: no face");
    }
}
