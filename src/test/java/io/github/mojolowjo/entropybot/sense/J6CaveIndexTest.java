package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6 (to-do/j6-caves-by-rays.md; the owner's ruling at the top of docs/CAVE_DETECTION.md): the cave index's
 * scenarios - two volumes under one surface, three rays into one make it a known cave with the right entry and size, two
 * rays do not, the other volume stays untouched, a reload keeps it known; and the index across a chunk edge.
 */
class J6CaveIndexTest {
    static final int SURFACE = 64;

    /**
     * Chunk 0 0, ground to y 63, the surface at 64 everywhere but a dip: columns x 0..1 at z 0..15 have their surface at
     * y 50 (a ravine-like cut, open to the sky). Volume A: a tunnel at y 50..52, x 2..9, z 4..5 - its x 2 end touches the
     * cut at x 1 (surface 50): its entry. Volume B: a closed room at y 20..23, x 10..13, z 10..13, no opening.
     */
    static CaveIndex.ChunkRuns chunk() {
        int[] surface = new int[256];
        for (int c = 0; c < 256; c++) surface[c] = (c & 15) <= 1 ? 50 : SURFACE;
        return CaveIndex.runs(0, 0, surface, -64, (x, y, z) -> inA(x, y, z) || inB(x, y, z));
    }

    static boolean inA(int x, int y, int z) { return x >= 2 && x <= 9 && z >= 4 && z <= 5 && y >= 50 && y <= 52; }

    static boolean inB(int x, int y, int z) { return x >= 10 && x <= 13 && z >= 10 && z <= 13 && y >= 20 && y <= 23; }

    static CaveIndex index() {
        CaveIndex i = new CaveIndex();
        i.put(chunk());
        return i;
    }

    @Test
    void twoVolumes_threeRaysIntoOne_aKnownCaveWithTheRightEntryAndSize() {
        CaveIndex i = index();
        int a = i.volumeAt(5, 51, 4), b = i.volumeAt(11, 21, 11);
        assertTrue(a >= 0 && b >= 0 && a != b, "two volumes");
        assertEquals(8 * 2 * 3, i.volume(a).cells(), "A: 8 x 2 x 3 cells");
        assertEquals(4 * 4 * 4, i.volume(b).cells());
        assertEquals(CaveIndex.Credit.CREDITED, i.credit(9, 51, 5).what());
        assertEquals(CaveIndex.Credit.CREDITED, i.credit(6, 52, 4).what());
        CaveIndex.Result r = i.credit(5, 53, 5);            // within one block (the roof over a cell of A)
        assertEquals(CaveIndex.Credit.KNOWN, r.what());
        CaveIndex.Known k = r.known();
        assertEquals(48, k.cells());
        assertEquals(50, k.minY());
        assertEquals(52, k.maxY());
        assertEquals(2, k.x(), "the entry: the cell that touches the open cut (x 1, surface 50)");
        assertEquals(50, k.y());
        assertEquals(3, k.hits().size());
        assertEquals(CaveIndex.Credit.ALREADY_KNOWN, i.credit(7, 51, 4).what(), "known stays known");
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
    }

    /** A reload keeps it known: the index is built again; the known cave of Sightings marks its volume known. */
    @Test
    void aReloadKeepsItKnown() {
        CaveIndex i = index();
        i.credit(9, 51, 5);
        i.credit(6, 52, 4);
        CaveIndex.Known k = i.credit(5, 51, 4).known();
        Sightings s = new Sightings();
        assertNotNull(s.knowCave("minecraft:overworld", k, 1000));
        Sightings back = Sightings.fromJson(s.toJson(), new int[1]);
        List<Sightings.KnownCave> kc = back.allKnownCaves("minecraft:overworld");
        assertEquals(1, kc.size());
        assertEquals(k.x(), kc.get(0).x());
        assertEquals(48, kc.get(0).cells());
        assertEquals(3, kc.get(0).hits().size());
        CaveIndex fresh = index();                           // the next session: the index again, no credits
        for (var c : kc) fresh.markKnown(c.x(), c.y(), c.z());
        assertTrue(fresh.isKnown(fresh.volumeAt(5, 51, 4)), "known stays known");
        assertNull(back.knowCave("minecraft:overworld", k, 2000), "never twice");
    }

    /** Two chunks: a tunnel across the edge x 15 / x 16 is one volume. */
    @Test
    void aVolumeAcrossAChunkEdgeIsOne() {
        int[] surface = new int[256];
        java.util.Arrays.fill(surface, SURFACE);
        CaveIndex.CellTest tunnel = (x, y, z) -> z == 8 && y == 30 && x >= 12 && x <= 19;
        CaveIndex i = new CaveIndex();
        i.put(CaveIndex.runs(0, 0, surface, -64, tunnel));
        assertNotEquals(i.volumeAt(12, 30, 8), i.volumeAt(20, 30, 8), "the next chunk not read yet");
        i.put(CaveIndex.runs(1, 0, surface, -64, tunnel));
        assertEquals(i.volumeAt(12, 30, 8), i.volumeAt(19, 30, 8));
        assertEquals(8, i.volume(i.volumeAt(12, 30, 8)).cells());
    }

    /** The openings the explore walks toward: A's hole in the cut (x 2 at y 50), not the closed room B; a known A none. */
    @Test
    void theOpeningsAreTheHolesOfVolumesNotKnown() {
        CaveIndex i = index();
        List<int[]> o = i.openings(0, 64, 0, 64);
        assertEquals(1, o.size(), "B is closed: no opening");
        assertEquals(2, o.get(0)[0]);
        assertEquals(50, o.get(0)[1]);
        i.credit(9, 51, 5);
        i.credit(6, 52, 4);
        i.credit(5, 51, 4);
        assertTrue(i.openings(0, 64, 0, 64).isEmpty(), "known: no more an opening to explore toward");
    }

    /** aa cave list: the known caves nearest first with their size, depth and rays, then the volumes credited so far. */
    @Test
    void theCaveListSaysTheKnownAndTheCredited() {
        CaveIndex i = index();
        i.credit(11, 21, 11);                                // one ray into B
        i.credit(9, 51, 5);
        i.credit(6, 52, 4);
        CaveIndex.Known k = i.credit(5, 51, 4).known();
        Sightings s = new Sightings();
        s.knowCave("d", k, 1);
        String t = CaveList.text(s.allKnownCaves("d"), i.credited(), "rays: test", 0, 64, 0);
        assertTrue(t.startsWith("known caves: 1\n  " + k.x() + " " + k.y() + " " + k.z() + ": "), t);
        assertTrue(t.contains("48 cells, y 50..52, 3 rays"), t);
        assertTrue(t.contains("credited, not known yet: 1") && t.contains(": 1 of 3 rays (first at 11 21 11)"), t);
        assertTrue(CaveList.text(List.of(), java.util.Map.of(), "r", 0, 0, 0).startsWith("known caves: none yet"));
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
