package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.17.1: the surface from a chunk scan: the sky rule, the face rule, the corner shading, the store and its queue. */
class SkyScanTest {
    /** Stone below y 64, air from 64 up, unless set; the game's neighbour test always says yes. */
    static class World implements SkyScan.World {
        final Map<Long, Integer> set = new HashMap<>();
        int ground = 64;

        World put(int x, int y, int z, int k) {
            set.put(CellKey.of(x, y, z), k);
            return this;
        }

        World box(int x0, int y0, int z0, int x1, int y1, int z1, int k) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) put(x, y, z, k);
            return this;
        }

        @Override
        public int kind(int x, int y, int z) {
            Integer k = set.get(CellKey.of(x, y, z));
            return k != null ? k : y < ground ? SkyScan.SOLID : SkyScan.AIR;
        }

        @Override
        public boolean faceShows(int x, int y, int z, int side) { return true; }
    }

    /** Scans the columns x0..x1, z0..z1 (top 80): the faces as "x y z side" strings. */
    static Set<String> scan(World w, int x0, int z0, int x1, int z1, boolean fancy) {
        Set<String> out = new HashSet<>();
        List<String> dup = new ArrayList<>();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                SkyScan.column(w, x, z, 80, 0, fancy, (fx, fy, fz, s) -> {
                    if (!out.add(fx + " " + fy + " " + fz + " " + s)) dup.add(fx + " " + fy + " " + fz + " " + s);
                });
        assertTrue(dup.isEmpty(), "a face reported twice: " + dup);
        return out;
    }

    @Test
    void aMeadowIsAllTops() {
        World w = new World();
        Set<String> f = scan(w, 0, 0, 9, 9, false);
        assertEquals(100, f.size(), "one top face per column, nothing else");
        for (int x = 0; x <= 9; x++) for (int z = 0; z <= 9; z++) assertTrue(f.contains(x + " 63 " + z + " 1"));
        assertEquals(63, SkyScan.floor(w, 3, 3, 80, 0, SkyScan.MAX_WATER));
    }

    @Test
    void stepsHaveTheirWallsAndNoGaps() {
        World w = new World().box(5, 64, 0, 9, 64, 9, SkyScan.SOLID);      // the east half one block higher
        Set<String> f = scan(w, 0, 0, 9, 9, false);
        for (int z = 0; z <= 9; z++) {
            assertTrue(f.contains("5 64 " + z + " 4"), "the step's west wall at z " + z);
            assertTrue(f.contains("4 63 " + z + " 1") && f.contains("5 64 " + z + " 1"), "both levels' tops");
            assertFalse(f.contains("5 63 " + z + " 4"), "nothing under the step (it touches the ground block)");
        }
        assertEquals(100 + 10, f.size());
    }

    @Test
    void noXrayUnderARoofOrInACave() {
        World w = new World();
        w.box(0, 60, 0, 4, 62, 4, SkyScan.AIR);              // a cave under the meadow
        w.put(2, 61, 2, SkyScan.SOLID);                       // with a pillar (an "ore") in it
        w.box(10, 64, 10, 14, 66, 14, SkyScan.SOLID);         // a hill
        w.box(11, 64, 11, 13, 65, 13, SkyScan.AIR);           // a room inside it with a roof
        Set<String> f = scan(w, -2, -2, 16, 16, false);
        for (String s : f) {
            String[] p = s.split(" ");
            int y = Integer.parseInt(p[1]);
            assertFalse(y >= 59 && y <= 62, "nothing of the cave: " + s);
            int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[2]);
            assertFalse(x >= 11 && x <= 13 && z >= 11 && z <= 13 && y >= 63 && y <= 65, "nothing inside the roofed room: " + s);
        }
        assertTrue(f.contains("12 66 12 1"), "the roof's top is drawn");
        assertFalse(f.contains("12 63 12 1"), "the room's floor is not");
        assertEquals(66, SkyScan.floor(w, 12, 12, 80, 0, SkyScan.MAX_WATER), "the roof is the floor of the column");
    }

    @Test
    void aShaftOpenToTheSkyIsSeenACaveMouthIsNot() {
        World w = new World();
        w.box(3, 55, 3, 3, 63, 3, SkyScan.AIR);              // a 1x1 shaft 9 deep
        w.box(4, 55, 3, 8, 56, 3, SkyScan.AIR);              // a tunnel leading off its bottom
        Set<String> f = scan(w, 0, 0, 9, 9, false);
        assertTrue(f.contains("3 54 3 1"), "the shaft's bottom (open sky straight up)");
        assertTrue(f.contains("2 58 3 5"), "its walls");
        assertTrue(f.contains("4 57 3 4"), "the shaft's wall above the tunnel's mouth");
        assertFalse(f.contains("6 54 3 1"), "the tunnel's floor (rock above it) is not a sky face");
        assertFalse(f.contains("6 57 3 0"), "nor its ceiling");
        assertFalse(f.contains("4 57 3 0"), "nor the ceiling right at the mouth: it faces the tunnel, not the shaft");
    }

    @Test
    void groundUnderATreeCrownIsSurface() {
        World w = new World();
        w.box(5, 64, 5, 5, 68, 5, SkyScan.SOLID);            // a trunk
        w.box(3, 67, 3, 7, 69, 7, SkyScan.LEAVES);           // a crown round it
        w.box(5, 67, 5, 5, 68, 5, SkyScan.SOLID);
        Set<String> fast = scan(w, 0, 0, 10, 10, false), fancy = scan(w, 0, 0, 10, 10, true);
        assertTrue(fast.contains("4 63 4 1"), "the ground under the crown is drawn (leaves keep the column open)");
        assertTrue(fast.contains("5 64 4 2") || fast.contains("5 64 5 2"), "the trunk's side under the crown");
        assertTrue(fast.contains("4 69 4 1"), "the crown's top");
        assertTrue(fast.contains("4 67 4 0"), "the crown's underside, seen from the ground");
        assertFalse(fast.contains("4 68 4 5"), "Fast: no faces between leaves");
        assertTrue(fancy.contains("4 68 4 5"), "Fancy: the faces between leaves are drawn (they show through the gaps)");
        assertFalse(fast.contains("5 67 5 2"), "Fast: the trunk inside the crown is hidden");
        assertTrue(fancy.contains("5 67 5 2"), "Fancy: it shows through the leaves");
        assertTrue(fancy.size() > fast.size());
        assertFalse(fast.contains("5 68 5 1"), "trunk top under leaves: Fast hides it");
        assertTrue(fancy.contains("5 68 5 1"));
    }

    @Test
    void shallowWaterShowsItsBedDeepWaterDoesNot() {
        World w = new World();
        w.box(0, 61, 0, 0, 63, 0, SkyScan.WATER);            // 3 deep
        w.box(5, 54, 5, 5, 63, 5, SkyScan.WATER);            // 10 deep
        Set<String> f = scan(w, 0, 0, 0, 0, false), g = scan(w, 5, 5, 5, 5, false);
        assertTrue(f.contains("0 63 0 1"), "the water's surface");
        assertTrue(f.contains("0 60 0 1"), "the bed under 3 water");
        assertFalse(f.contains("0 62 0 1"), "no face between water and water");
        assertTrue(g.contains("5 63 5 1"));
        assertFalse(g.contains("5 53 5 1"), "the bed under 10 water stays out");
    }

    @Test
    void glassAndPlants() {
        World w = new World();
        w.put(2, 64, 2, SkyScan.PLANT);                       // a flower
        w.box(5, 64, 5, 6, 64, 5, SkyScan.GLASS);             // two glass blocks side by side
        Set<String> f = scan(w, 0, 0, 9, 9, false);
        assertTrue(f.contains("2 63 2 1"), "the ground under a flower");
        assertTrue(f.contains("5 63 5 1"), "the ground under glass, seen through it");
        assertTrue(f.contains("5 64 5 1") && f.contains("5 64 5 4"), "glass faces towards air");
        assertFalse(f.contains("5 64 5 5"), "no face between glass and glass");
        assertFalse(f.stream().anyMatch(s -> s.startsWith("2 64 2 ")), "plants are not drawn");
    }

    @Test
    void theRules() {
        assertTrue(SkyScan.shows(SkyScan.SOLID, SkyScan.AIR, false));
        assertTrue(SkyScan.shows(SkyScan.LEAVES, SkyScan.AIR, false));
        assertFalse(SkyScan.shows(SkyScan.LEAVES, SkyScan.LEAVES, false));
        assertTrue(SkyScan.shows(SkyScan.LEAVES, SkyScan.LEAVES, true));
        assertFalse(SkyScan.shows(SkyScan.SOLID, SkyScan.LEAVES, false), "Fast: behind a leaf is hidden");
        assertTrue(SkyScan.shows(SkyScan.SOLID, SkyScan.LEAVES, true));
        assertFalse(SkyScan.shows(SkyScan.WATER, SkyScan.WATER, true));
        assertFalse(SkyScan.shows(SkyScan.AIR, SkyScan.AIR, true));
        assertFalse(SkyScan.shows(SkyScan.PLANT, SkyScan.AIR, true));
        assertFalse(SkyScan.shows(SkyScan.SOLID, SkyScan.SOLID, true));
        for (int k : new int[]{SkyScan.SOLID, SkyScan.SHAPE, SkyScan.PARTIAL, SkyScan.UNLOADED}) assertTrue(SkyScan.blocksSky(k));
        for (int k : new int[]{SkyScan.AIR, SkyScan.PLANT, SkyScan.WATER, SkyScan.GLASS, SkyScan.LEAVES}) assertFalse(SkyScan.blocksSky(k));
    }

    @Test
    void theGameMayStillSayNo() {
        World w = new World() {
            @Override
            public boolean faceShows(int x, int y, int z, int side) { return !(x == 1 && z == 1); }
        };
        Set<String> f = scan(w, 0, 0, 2, 2, false);
        assertEquals(8, f.size());
        assertFalse(f.contains("1 63 1 1"));
    }

    @Test
    void cornerShadingDarkensCornersNextToBlocks() {
        World w = new World().put(3, 64, 2, SkyScan.SOLID);   // a block on the ground north of 3 63 3
        float[][] c = FaceGeometry.corners(3, 63, 3, 1, 0, 0, 0, 0);
        int[] lv = SkyScan.cornerShade(3, 63, 3, 1, c, new double[]{3.5, 64, 3.5}, (x, y, z) -> SkyScan.shades(w.kind(x, y, z)));
        for (int i = 0; i < 4; i++) {
            boolean north = c[i][2] < 3.5;
            assertEquals(north ? 2 : 3, lv[i], "corner " + i + " (north " + north + ")");
        }
        World pit = new World().box(2, 64, 2, 4, 64, 4, SkyScan.SOLID).put(3, 64, 3, SkyScan.AIR);   // a 1x1 pit
        int[] p = SkyScan.cornerShade(3, 63, 3, 1, c, new double[]{3.5, 64, 3.5}, (x, y, z) -> SkyScan.shades(pit.kind(x, y, z)));
        for (int v : p) assertEquals(0, v, "a pit's floor: every corner between two walls");
        assertEquals(1f, SkyScan.shadeFactor(3));
        assertTrue(SkyScan.shadeFactor(0) < SkyScan.shadeFactor(1) && SkyScan.shadeFactor(1) < SkyScan.shadeFactor(2) && SkyScan.shadeFactor(2) < 1f);
    }

    @Test
    void theShadingSplitKeepsTheWinding() {
        assertEquals(0, FaceGeometry.aoStart(new float[]{0.5f, 1f, 0.5f, 1f}), "dark diagonal 0-2: keep it");
        assertEquals(1, FaceGeometry.aoStart(new float[]{1f, 0.5f, 1f, 0.5f}), "bright diagonal 0-2: split on 1-3");
        for (int side = 0; side < 6; side++) {
            float[][] c = FaceGeometry.corners(2, 70, -9, side, TunnelMesh.INSET, 0, 0, 0);
            float[][] r = {c[1], c[2], c[3], c[0]};
            assertArrayEquals(FaceGeometry.frontNormal(c), FaceGeometry.frontNormal(r), "a cyclic start keeps the front face, side " + side);
        }
    }

    // ---- the store and the queue ----------------------------------------------------------------------------------

    static SkyStore.Chunk chunk(SkyStore s, int cx, int cz, int faces, int floor) {
        long[] f = new long[faces];
        for (int i = 0; i < faces; i++) f[i] = SkyStore.faceKey((cx << 4) + (i & 15), floor, (cz << 4) + ((i >> 4) & 15), 1);
        java.util.Arrays.sort(f);
        int[] floors = new int[256];
        java.util.Arrays.fill(floors, floor);
        return new SkyStore.Chunk(cx, cz, f, new byte[faces], floors, s.newChunkVersion());
    }

    @Test
    void theQueueGivesTheNearestFirst() {
        SkyStore s = new SkyStore();
        s.enqueue(5, 5);
        s.enqueue(1, 0);
        s.enqueue(-3, 0);
        s.enqueue(1, 0);                                       // twice: once
        assertEquals(3, s.queueSize());
        assertArrayEquals(new long[]{1, 0}, s.next(0, 0));
        assertArrayEquals(new long[]{-3, 0}, s.next(0, 0));
        assertArrayEquals(new long[]{5, 5}, s.next(4, 4), "the eye moved: nearest to it");
        assertNull(s.next(0, 0));
    }

    @Test
    void storeLooksUpFacesFloorsAndSkyCells() {
        SkyStore s = new SkyStore();
        s.put(chunk(s, 0, 0, 256, 63), 0, 0);
        assertTrue(s.has(0, 0));
        assertEquals(256, s.faces());
        assertTrue(s.contains(3, 63, 4, 1), "a top face, its air cell in this chunk");
        assertFalse(s.contains(3, 63, 4, 0));
        assertEquals(63, s.floorAt(3, 4));
        assertEquals(Integer.MIN_VALUE, s.floorAt(40, 4), "not scanned");
        assertTrue(s.skyCell(3, 64, 4));
        assertFalse(s.skyCell(3, 63, 4));
        assertFalse(s.skyCell(40, 70, 4));
        assertTrue(s.covers(15, 15));
        assertFalse(s.covers(16, 0));
        // the main mesh's skip rule
        assertTrue(s.drawsInstead(3, 63, 4, 1, false), "a saved face the scan has");
        assertFalse(s.drawsInstead(3, 62, 4, 1, false), "a saved face it hasn't (underground)");
        assertTrue(s.drawsInstead(3, 62, 4, 1, true), "a ray surface face in a scanned chunk: the scan is the authority");
        assertFalse(s.drawsInstead(40, 63, 4, 1, true), "not scanned there: the rays fill in");
        // the face of a block in chunk 1 whose sky cell is in chunk 0 belongs to chunk 0
        assertFalse(s.contains(16, 64, 3, 4));
        long v = s.version();
        s.put(chunk(s, 0, 0, 10, 70), 0, 0);
        assertEquals(10, s.faces(), "replaced, not added");
        assertTrue(s.version() > v);
    }

    @Test
    void pruningAndTheCap() {
        SkyStore s = new SkyStore(1000);
        s.put(chunk(s, 0, 0, 300, 63), 0, 0);
        s.put(chunk(s, 2, 0, 300, 63), 0, 0);
        s.put(chunk(s, 9, 0, 300, 63), 0, 0);
        s.enqueue(10, 0);
        s.enqueue(1, 1);
        assertEquals(1, s.pruneOutside(0, 0, 5), "chunk 9 is out of the render distance");
        assertFalse(s.has(9, 0));
        assertEquals(1, s.queueSize(), "and so is the queued chunk 10");
        assertEquals(600, s.faces());
        assertEquals(1, s.pruned());
        // the cap: the farthest go first
        assertTrue(s.put(chunk(s, 1, 0, 300, 63), 0, 0));
        assertFalse(s.put(chunk(s, 4, 0, 300, 63), 0, 0), "the newest is the farthest: it is the one dropped");
        assertTrue(s.faces() <= 1000);
        assertTrue(s.has(0, 0) && s.has(1, 0) && s.has(2, 0));
        s.enqueue(4, 0);
        assertFalse(s.queued(4, 0), "left out at the cap until the eye moves");
        s.eyeMoved();
        s.enqueue(4, 0);
        assertTrue(s.queued(4, 0));
        assertEquals(1, s.cappedDrops());
        assertTrue(SkyStore.MAX_FACES >= 400_000, "room for a Fancy forest at render distance 5");
        s.clear();
        assertEquals(0, s.faces());
        assertEquals(0, s.queueSize());
    }

    @Test
    void stalledScanCheck() {
        assertFalse(WatchChecks.scanStalled(false, true, 60_000, -1, 5000), "off");
        assertFalse(WatchChecks.scanStalled(true, false, 60_000, 60_000, 5000), "nothing waiting");
        assertFalse(WatchChecks.scanStalled(true, true, 3000, -1, 5000), "just started");
        assertTrue(WatchChecks.scanStalled(true, true, 60_000, -1, 5000), "never scanned");
        assertTrue(WatchChecks.scanStalled(true, true, 60_000, 6000, 5000));
        assertFalse(WatchChecks.scanStalled(true, true, 60_000, 100, 5000));
    }
}
