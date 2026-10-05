package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.16.1: faces under open sky go to the in-memory surface store, kept within the render distance; drawn with the rest. */
class SurfaceFacesTest {
    static final String OW = "minecraft:overworld", NETHER = "minecraft:the_nether";

    @Test
    void skyFacesGoToTheSurfaceStoreNeverToTheFile() throws Exception {
        assertEquals(SeenRule.SURFACE, SeenRule.where(true, 15, 60));
        assertEquals(SeenRule.SAVED, SeenRule.where(false, 12, 60));
        assertEquals(SeenRule.NONE, SeenRule.where(true, 0, 9), "the dark rule holds on the surface too");
        assertEquals(SeenRule.SURFACE, SeenRule.where(true, 0, 8));
        assertFalse(SeenRule.record(true, 15, 2), "the saved store's rule is unchanged");
        // the sampler's routing: a sky face into the surface store; the saved store's file stays empty of it
        SeenFaces saved = new SeenFaces(), surface = new SeenFaces(SeenSampler.SURFACE_MAX, SeenSampler.SURFACE_MAX);
        int[][] faces = {{0, 64, 0, 1, 15, 1}, {3, 40, 3, 4, 9, 0}};         // x y z side light sky
        for (int[] f : faces) {
            switch (SeenRule.where(f[5] == 1, f[4], 5)) {
                case SeenRule.SURFACE -> surface.add(OW, f[0], f[1], f[2], f[3], f[4]);
                case SeenRule.SAVED -> saved.add(OW, f[0], f[1], f[2], f[3], f[4]);
                default -> fail();
            }
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        saved.write(file);
        SeenFaces back = new SeenFaces();
        assertEquals(1, back.read(new ByteArrayInputStream(file.toByteArray())));
        assertNull(back.get(OW, 0, 64, 0), "the sky face is not in the file");
        assertNotNull(back.get(OW, 3, 40, 3));
        assertNotNull(surface.get(OW, 0, 64, 0));
    }

    @Test
    void chunksWithinTheRenderDistance() {
        assertTrue(SeenFaces.chunkWithin(0, 0, 0, 0, 5));
        assertTrue(SeenFaces.chunkWithin(5, 0, 0, 0, 5), "one chunk of slack, like vanilla's outer ring");
        assertFalse(SeenFaces.chunkWithin(7, 0, 0, 0, 5));
        assertTrue(SeenFaces.chunkWithin(4, 4, 0, 0, 5), "3^2 + 3^2 < 25");
        assertFalse(SeenFaces.chunkWithin(5, 5, 0, 0, 5), "4^2 + 4^2 >= 25: round, not square");
        assertTrue(SeenFaces.chunkWithin(-105, 30, -100, 33, 5), "negative chunks");
    }

    @Test
    void pruningKeepsOnlyWhatIsWithinRange() {
        SeenFaces s = new SeenFaces(1000, 1000);
        s.add(OW, 0, 64, 0, 1, 15);                  // chunk 0 0
        s.add(OW, 70, 64, 0, 1, 15);                 // chunk 4 0
        s.add(OW, 200, 64, 0, 1, 15);                // chunk 12 0: out of range
        s.add(OW, -300, 64, 900, 1, 15);             // another region, far off
        s.add(NETHER, 0, 64, 0, 1, 15);              // another dimension
        long v = s.version();
        assertEquals(3, s.pruneOutside(OW, 0, 0, 5));
        assertEquals(2, s.size());
        assertEquals(2, s.faces());
        assertNotNull(s.get(OW, 70, 64, 0));
        assertNull(s.get(OW, 200, 64, 0));
        assertNull(s.get(NETHER, 0, 64, 0));
        assertEquals(3, s.pruned());
        assertEquals(0, s.evicted(), "pruned is not evicted");
        assertTrue(s.version() > v, "the mesh sees the change");
        long v2 = s.version();
        assertEquals(0, s.pruneOutside(OW, 0, 0, 5), "nothing more to drop");
        assertEquals(v2, s.version());
        // the bot walks east 8 chunks: the old ground goes, chunk 4 is still in range
        assertEquals(1, s.pruneOutside(OW, 8, 0, 5));
        assertNotNull(s.get(OW, 70, 64, 0));
        assertEquals(1, s.size());
    }

    @Test
    void theCapDropsTheOldestFirst() {
        SeenFaces s = new SeenFaces(5, 5);
        for (int i = 0; i < 8; i++) s.add(OW, i, 64, 0, 1, 15);
        assertEquals(5, s.size());
        assertNull(s.get(OW, 0, 64, 0));
        assertNotNull(s.get(OW, 7, 64, 0));
        assertEquals(3, s.evicted());
        assertTrue(SeenSampler.SURFACE_MAX >= 50_000, "room for the ground round an 80-block render distance");
    }

    /** The world for the union, as ray cells: rock (HIT) unless set; a surface: air above y 64, grass at 64. */
    static SeenRaysTest.Cells meadow() {
        SeenRaysTest.Cells w = new SeenRaysTest.Cells().box(-20, 65, -20, 20, 70, 20, SeenRays.PASS);
        w.put(3, 64, 3, SeenRays.PARTIAL);           // a path block
        w.put(5, 65, 5, SeenRays.SHAPE);             // a leaf block (a bush) on the ground
        return w;
    }

    @Test
    void theUnionDrawsTheSurfaceWithTheRest() {
        SeenRaysTest.Cells w = meadow();
        SeenFaces seen = new SeenFaces(), surface = new SeenFaces(100, 100);
        surface.add(OW, 0, 64, 0, 1, 15);            // grass top
        surface.add(OW, 3, 64, 3, 1, 15);            // the path's top
        surface.add(OW, 5, 65, 5, 1, 15);            // the bush's top
        surface.add(OW, 5, 65, 5, 2, 15);            // its north side
        surface.add(OW, 1, 63, 1, 1, 15);            // buried under the ground: not a face any more
        seen.add(OW, 0, 64, 0, 1, 14);               // also seen once from under a tree: drawn once, from the saved store
        SeenMesh.Result r = SeenMesh.union(List.of(), false, seen.near(OW, 0, 64, 0, 64), surface.near(OW, 0, 64, 0, 64), w, 0, 65, 0, 1000);
        assertEquals(4, r.faces().size());
        assertEquals(1, r.seenFaces());
        assertEquals(3, r.surfaceFaces());
        assertEquals(0, r.shellFaces());
        assertEquals(1, r.faces().stream().filter(f -> f.x() == 0 && f.z() == 0).count(), "no duplicate");
        assertFalse(r.faces().stream().filter(f -> f.x() == 0 && f.z() == 0).findFirst().orElseThrow().surface(), "the saved store's copy wins");
        assertTrue(r.faces().stream().allMatch(SeenMesh.MeshFace::seen));
        // the bush is cut down since: its faces go
        w.put(5, 65, 5, SeenRays.PASS);
        assertEquals(2, SeenMesh.union(List.of(), false, seen.near(OW, 0, 64, 0, 64), surface.near(OW, 0, 64, 0, 64), w, 0, 65, 0, 1000).faces().size());
    }

    @Test
    void theCapKeepsTheNearestOfAllThree() {
        SeenRaysTest.Cells w = meadow();
        SeenFaces surface = new SeenFaces(1000, 1000);
        for (int x = -15; x <= 15; x++) surface.add(OW, x, 64, 0, 1, 15);
        SeenMesh.Result r = SeenMesh.union(List.of(), false, List.of(), surface.near(OW, 0, 64, 0, 64), w, 0, 65, 0, 11);
        assertTrue(r.capped());
        assertEquals(11, r.faces().size());
        for (SeenMesh.MeshFace f : r.faces()) assertTrue(Math.abs(f.x()) <= 5, "nearest to the bot first: " + f);
    }

    @Test
    void boxFacesKeepTheWindingAndShowTheRightPartOfTheTexture() {
        double[] slab = {0, 0, 0, 1, 0.5, 1};
        for (int side = 0; side < 6; side++) {
            float[][] c = FaceGeometry.corners(3, -4, 7, side, 0, 0, 0, 0, slab);
            assertArrayEquals(Shell.OFF[side], FaceGeometry.frontNormal(c), "side " + side + " still faces its air side");
            for (float[] v : c) assertTrue(v[1] >= -4 - 1e-6 && v[1] <= -3.5 + 1e-6, "inside the slab's half");
        }
        float[][] top = FaceGeometry.corners(3, -4, 7, 1, 0, 0, 0, 0, slab);
        for (float[] v : top) assertEquals(-3.5, v[1], 1e-6, "the top face at half height");
        float[][] north = FaceGeometry.corners(3, -4, 7, 2, 0, 0, 0, 0, slab);
        for (float[] v : north) assertTrue(v[4] >= 0.5f - 1e-6 && v[4] <= 1f + 1e-6, "a slab side shows the lower half of the texture");
        float[][] unit = FaceGeometry.corners(3, -4, 7, 2, 0.004, 1, 1, 1);
        assertArrayEquals(unit[2], FaceGeometry.corners(3, -4, 7, 2, 0.004, 1, 1, 1, FaceGeometry.UNIT)[2], 1e-6f, "the unit box is the old face");
        float[][] bad = FaceGeometry.corners(3, -4, 7, 2, 0.004, 1, 1, 1, new double[]{0, 0.5, 0, 1, 0.5, 1});
        assertArrayEquals(unit[0], bad[0], 1e-6f, "an empty box falls back to the whole cell");
    }
}
