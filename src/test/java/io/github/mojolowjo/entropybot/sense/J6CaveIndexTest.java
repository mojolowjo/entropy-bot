package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6 (to-do/j6-caves-by-rays.md; the owner's ruling at the top of docs/CAVE_DETECTION.md; the review of
 * 3317a342): the cave index's scenarios - two volumes under one surface, three rays THROUGH the opening into one make it a
 * known cave with an entry a ray saw, a size bucket and a depth band; three rays stopped by a one-block wall credit
 * nothing; two rays do not make it known; the other volume stays untouched; a reload keeps it known; the index across a
 * chunk edge, an unload drops the unknown volumes and keeps the known marks; the memory a chunk takes.
 */
class J6CaveIndexTest {
    static final int SURFACE = 64;

    /**
     * Chunk 0 0, ground to y 63, the surface at 64 everywhere but a dip: columns x 0..1 at z 0..15 have their surface at
     * y 50 (a ravine-like cut, open to the sky). Volume A: a tunnel at y 50..52, x 2..9, z 4..5 - its x 2 end opens on
     * the cut at x 1 (surface 50). Volume B: a closed room at y 20..23, x 10..13, z 10..13, no opening.
     */
    static CaveIndex.ChunkRuns chunk() {
        return CaveIndex.runs(0, 0, cutSurface(), -64, (x, y, z) -> inA(x, y, z) || inB(x, y, z));
    }

    static int[] cutSurface() {
        int[] surface = new int[256];
        for (int c = 0; c < 256; c++) surface[c] = (c & 15) <= 1 ? 50 : SURFACE;
        return surface;
    }

    static boolean inA(int x, int y, int z) { return x >= 2 && x <= 9 && z >= 4 && z <= 5 && y >= 50 && y <= 52; }

    static boolean inB(int x, int y, int z) { return x >= 10 && x <= 13 && z >= 10 && z <= 13 && y >= 20 && y <= 23; }

    static CaveIndex index() {
        CaveIndex i = new CaveIndex();
        i.put(chunk());
        return i;
    }

    /** The eyes standing in the cut, at A's mouth. */
    static final double EX = 0.5, EY = 51.5, EZ = 4.5;

    /** A ray from the eyes in the cut that reaches the cell x y z (nothing in the way: the clip's MISS). */
    static CaveIndex.Result seen(CaveIndex i, int x, int y, int z) {
        return i.creditRay(CaveIndex.path(EX, EY, EZ, x + 0.5, y + 0.5, z + 0.5));
    }

    @Test
    void twoVolumes_threeRaysThroughTheOpening_aKnownCave_itsEntryASeenCell_aSizeBucketAndADepthBand() {
        CaveIndex i = index();
        int a = i.volumeAt(5, 51, 4), b = i.volumeAt(11, 21, 11);
        assertTrue(a >= 0 && b >= 0 && a != b, "two volumes");
        assertEquals(8 * 2 * 3, i.volume(a).cells(), "A: 8 x 2 x 3 cells");
        assertEquals(4 * 4 * 4, i.volume(b).cells());
        assertEquals(CaveIndex.Credit.CREDITED, seen(i, 9, 51, 5).what());
        assertEquals(CaveIndex.Credit.CREDITED, seen(i, 6, 52, 4).what());
        CaveIndex.Result r = seen(i, 5, 51, 5);
        assertEquals(CaveIndex.Credit.KNOWN, r.what());
        CaveIndex.Known k = r.known();
        assertEquals(3, k.hits().size());
        assertTrue(k.hits().contains(Sightings.cell(k.x(), k.y(), k.z())), "the entry is a cell a ray saw: " + k);
        assertEquals(List.of(6, 52, 4), List.of(k.x(), k.y(), k.z()), "of the seen cells the one nearest its column's surface");
        assertEquals(CaveIndex.Size.SMALL, k.size(), "48 cells: well under a minute of walking");
        assertEquals(CaveIndex.Depth.STONE, k.depth(), "its lowest cell y 50: between y 0 and sea level");
        assertEquals(CaveIndex.Credit.ALREADY_KNOWN, seen(i, 7, 51, 4).what(), "known stays known");
    }

    /**
     * The review of 3317a342 (rule 17): a tunnel C behind a one-block wall (the column x 2 is rock) - three rays from the
     * cut toward C stop on the wall and credit nothing (the old "within one block of a cave cell" credited C from rock);
     * with the wall gone the same three rays pass through C's air and make it known.
     */
    @Test
    void threeRaysStoppedByAOneBlockWall_creditNothing() {
        CaveIndex walled = new CaveIndex();
        walled.put(CaveIndex.runs(0, 0, cutSurface(), -64, (x, y, z) -> x >= 3 && x <= 9 && z == 8 && y >= 51 && y <= 53));
        double ex = 0.5, ey = 52.5, ez = 8.5;
        int[][] targets = {{5, 51, 8}, {6, 52, 8}, {7, 53, 8}};
        for (int[] t : targets) {
            double tx = t[0] + 0.5, ty = t[1] + 0.5, tz = t[2] + 0.5, f = (2.0 - ex) / (tx - ex);   // the clip stops on the wall's face x 2
            double hx = 2.0, hy = ey + f * (ty - ey), hz = ez + f * (tz - ez);
            List<int[]> path = new ArrayList<>(CaveIndex.path(ex, ey, ez, hx, hy, hz));
            int by = (int) Math.floor(hy);
            path.removeIf(q -> q[0] == 2 && q[1] == by && q[2] == 8);        // as CaveRays: the block that stopped it was not seen through
            assertEquals(CaveIndex.Credit.NO_VOLUME, walled.creditRay(path).what(), "stopped by the wall at y " + by);
        }
        assertTrue(walled.volume(walled.volumeAt(5, 52, 8)).credits().isEmpty(), "nothing credited");
        assertTrue(walled.credited().isEmpty());
        assertEquals(CaveIndex.Credit.NO_VOLUME, walled.credit(2, 52, 8).what(), "the wall's own cell is no cave cell (no credit beside air)");

        CaveIndex open = new CaveIndex();
        open.put(CaveIndex.runs(0, 0, cutSurface(), -64, (x, y, z) -> x >= 2 && x <= 9 && z == 8 && y >= 51 && y <= 53));
        CaveIndex.Result last = null;
        for (int[] t : targets) last = open.creditRay(CaveIndex.path(ex, ey, ez, t[0] + 0.5, t[1] + 0.5, t[2] + 0.5));
        assertEquals(CaveIndex.Credit.KNOWN, last.what(), "the wall gone: through the opening, three rays make it known");
    }

    @Test
    void twoRays_notKnown_andTheOtherVolumeUntouched() {
        CaveIndex i = index();
        i.credit(9, 51, 5);
        i.credit(6, 52, 4);
        int a = i.volumeAt(5, 51, 4), b = i.volumeAt(11, 21, 11);
        assertFalse(i.isKnown(a), "two rays: not known");
        assertEquals(2, i.volume(a).credits().size());
        assertTrue(i.volume(b).credits().isEmpty(), "B untouched");
        assertFalse(i.isKnown(b));
        assertEquals(CaveIndex.Credit.CREDITED, i.credit(9, 51, 5).what(), "the same cell again is no new ray");
        assertEquals(2, i.volume(a).credits().size());
    }

    @Test
    void aRayIntoNoVolume_isNoVolume() {
        CaveIndex i = index();
        assertEquals(CaveIndex.Credit.NO_VOLUME, i.credit(40, 51, 40).what(), "a chunk not read (an edge)");
        assertEquals(CaveIndex.Credit.NO_VOLUME, i.credit(5, 30, 8).what(), "solid rock");
        assertEquals(CaveIndex.Credit.NO_VOLUME, i.credit(5, 53, 5).what(), "the roof over A: rock beside air credits nothing");
        assertEquals(CaveIndex.Credit.NO_VOLUME, i.creditRay(CaveIndex.path(0.5, 60.5, 8.5, 1.5, 60.5, 8.5)).what(), "only open sky on the path");
    }

    /** A reload keeps it known: the index is built again; the known cave of Sightings marks its volume known. */
    @Test
    void aReloadKeepsItKnown() {
        CaveIndex i = index();
        seen(i, 9, 51, 5);
        seen(i, 6, 52, 4);
        CaveIndex.Known k = seen(i, 5, 51, 5).known();
        Sightings s = new Sightings();
        assertNotNull(s.knowCave("minecraft:overworld", k, 1000));
        Sightings back = Sightings.fromJson(s.toJson(), new int[1]);
        List<Sightings.KnownCave> kc = back.allKnownCaves("minecraft:overworld");
        assertEquals(1, kc.size());
        assertEquals(k.x(), kc.get(0).x());
        assertEquals(CaveIndex.Size.SMALL, kc.get(0).size());
        assertEquals(CaveIndex.Depth.STONE, kc.get(0).depth());
        assertEquals(3, kc.get(0).hits().size());
        CaveIndex fresh = index();                           // the next session: the index again, no credits
        for (var c : kc) fresh.markKnown(c.x(), c.y(), c.z());
        assertTrue(fresh.isKnown(fresh.volumeAt(5, 51, 4)), "known stays known");
        assertNull(back.knowCave("minecraft:overworld", k, 2000), "never twice");
    }

    /** Two chunks: a tunnel across the edge x 15 / x 16 is one volume; the next chunk unloaded, its part goes. */
    @Test
    void aVolumeAcrossAChunkEdgeIsOne_anUnloadTakesItsPart() {
        int[] surface = new int[256];
        java.util.Arrays.fill(surface, SURFACE);
        CaveIndex.CellTest tunnel = (x, y, z) -> z == 8 && y == 30 && x >= 12 && x <= 19;
        CaveIndex i = new CaveIndex();
        i.put(CaveIndex.runs(0, 0, surface, -64, tunnel));
        assertNotEquals(i.volumeAt(12, 30, 8), i.volumeAt(20, 30, 8), "the next chunk not read yet");
        i.put(CaveIndex.runs(1, 0, surface, -64, tunnel));
        assertEquals(i.volumeAt(12, 30, 8), i.volumeAt(19, 30, 8));
        assertEquals(8, i.volume(i.volumeAt(12, 30, 8)).cells());
        i.remove(1, 0);
        assertEquals(-1, i.volumeAt(19, 30, 8));
        assertEquals(4, i.volume(i.volumeAt(12, 30, 8)).cells(), "the loaded part only");
    }

    /**
     * Memory (the review): the index keeps the loaded chunks' volumes plus the known marks; an unload drops an unknown
     * volume with its credits, a known one is known again when its chunk comes back.
     */
    @Test
    void anUnloadDropsTheUnknownVolumes_theKnownStayKnown() {
        CaveIndex i = index();
        i.credit(9, 51, 5);                                  // A: one credit, unknown
        i.markKnown(11, 21, 11);                             // B: a known cave of Sightings
        assertEquals(1, i.credited().size());
        i.remove(0, 0);
        assertEquals(0, i.chunks());
        assertEquals(-1, i.volumeAt(5, 51, 4));
        assertTrue(i.credited().isEmpty(), "A's credit went with its chunk");
        i.put(chunk());
        assertTrue(i.volume(i.volumeAt(5, 51, 4)).credits().isEmpty(), "A again: no credit carried over");
        assertTrue(i.isKnown(i.volumeAt(11, 21, 11)), "B again: known");
    }

    /**
     * The measured size a chunk takes ({@link CaveIndex#approxBytes}): a chunk with no cave air shares its run arrays
     * (1072 bytes: its surface heights); the A + B chunk with its 32 runs 2548 bytes (+ 12 bytes a run, + the 257 column
     * starts); the union-find 20 bytes a node.
     */
    @Test
    void theBytesAChunkTakes() {
        CaveIndex none = new CaveIndex();
        long base = none.approxBytes();
        none.put(CaveIndex.runs(5, 5, cutSurface(), -64, (x, y, z) -> false));
        assertEquals(1072, none.approxBytes() - base, "no cave air: the surface heights only, the run arrays shared");
        CaveIndex ab = new CaveIndex();
        ab.put(chunk());
        assertEquals(2548, ab.approxBytes() - base, "32 runs");
    }

    /**
     * The openings the explore walks toward (the manager's ruling on J6 (a)): an opening no ray reached is no target (x-ray
     * by proxy); one credit (a ray saw the hole or its rim) makes A's hole in the cut (x 2 at y 50) a target; three make A
     * a known cave (no more an opening). The closed room B never has an opening.
     */
    @Test
    void anOpeningWithNoCreditIsNotAimedAt_oneCreditIs_threeIsAKnownCave() {
        CaveIndex i = index();
        assertTrue(i.openings(0, 64, 0, 64).isEmpty(), "no ray yet: not a target, the explore explores plainly");
        i.credit(9, 51, 5);
        List<int[]> o = i.openings(0, 64, 0, 64);
        assertEquals(CaveIndex.TARGET_RAYS, i.volume(i.volumeAt(5, 51, 4)).credits().size());
        assertEquals(1, o.size(), "one credit: A's opening is a target (B is closed: no opening)");
        assertEquals(2, o.get(0)[0]);
        assertEquals(50, o.get(0)[1]);
        i.credit(11, 21, 11);
        assertEquals(1, i.openings(0, 64, 0, 64).size(), "B credited but closed: still no opening");
        i.credit(6, 52, 4);
        assertEquals(CaveIndex.Credit.KNOWN, i.credit(5, 51, 4).what(), "three: a known cave");
        assertTrue(i.openings(0, 64, 0, 64).isEmpty(), "known: no more an opening to explore toward");
    }

    /** aa cave list: the known caves nearest first with their size, depth and rays, then the volumes credited so far. */
    @Test
    void theCaveListSaysTheKnownAndTheCredited() {
        CaveIndex i = index();
        i.credit(11, 21, 11);                                // one ray into B
        seen(i, 9, 51, 5);
        seen(i, 6, 52, 4);
        CaveIndex.Known k = seen(i, 5, 51, 5).known();
        Sightings s = new Sightings();
        s.knowCave("d", k, 1);
        String t = CaveList.text(s.allKnownCaves("d"), i.credited(), "rays: test", 0, 64, 0);
        assertTrue(t.startsWith("known caves: 1\n  " + k.x() + " " + k.y() + " " + k.z() + ": "), t);
        assertTrue(t.contains("size small, depth stone, 3 rays"), t);
        assertTrue(t.contains("credited, not known yet: 1") && t.contains(": 1 of 3 rays (first at 11 21 11)"), t);
        assertTrue(CaveList.text(List.of(), java.util.Map.of(), "r", 0, 0, 0).startsWith("known caves: none yet"));
    }

    /** The size bucket's edges: one minute of walking, and the caving way's cap (cave.exploreMinutes). */
    @Test
    void theSizeBucketsEdges() {
        double walk = 4.317, cap = 10;                       // the walk's speed (GameTimes), the cap's default
        assertEquals(CaveIndex.Size.SMALL, CaveIndex.Size.of(1000, walk, cap), "1000 cells: 0.97 min");
        assertEquals(CaveIndex.Size.MEDIUM, CaveIndex.Size.of(1100, walk, cap), "1100 cells: 1.06 min");
        assertEquals(CaveIndex.Size.LARGE, CaveIndex.Size.of(10400, walk, cap), "10400 cells: 10.04 min");
        assertEquals(1, CaveIndex.Size.SMALL.holdsMinutes(cap));
        assertEquals(5.5, CaveIndex.Size.MEDIUM.holdsMinutes(cap));
        assertEquals(cap, CaveIndex.Size.LARGE.holdsMinutes(cap));
        assertEquals(CaveIndex.Depth.DEEPSLATE, CaveIndex.Depth.of(-1));
        assertEquals(CaveIndex.Depth.STONE, CaveIndex.Depth.of(0));
        assertEquals(CaveIndex.Depth.STONE, CaveIndex.Depth.of(62));
        assertEquals(CaveIndex.Depth.HIGH, CaveIndex.Depth.of(63));
    }

    /** The rays' targets: one per run near the eyes, nearest first, the known left out. */
    @Test
    void targetsAreTheRunsNearTheEyes() {
        CaveIndex i = index();
        List<int[]> t = i.targets(5, 51, 5, 32, 10, null);
        assertFalse(t.isEmpty());
        assertTrue(i.volumeAt(t.get(0)[0], t.get(0)[1], t.get(0)[2]) == i.volumeAt(5, 51, 4), "A's cells first (nearest)");
        i.credit(9, 51, 5);
        i.credit(6, 52, 4);
        i.credit(5, 51, 4);
        for (int[] c : i.targets(5, 51, 5, 32, 100, null)) assertNotEquals(i.volumeAt(5, 51, 4), i.volumeAt(c[0], c[1], c[2]), "a known volume is no target");
    }
}
