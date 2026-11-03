package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * surface (owner 2026-11-02): a ravine is a cave mouth - {@link CaveIndex#rimmed} lifts a ravine floor's ground to the
 * rim, so its air is indexed and its floor counts as under ground; {@link CaveIndex#groundAt} answers the guard's depth.
 */
class SurfaceRavineTest {

    /** Flat ground at 64, a ravine 3 wide (x 6..8, every z) with its floor at 40. */
    private static int[] ravine() {
        int[] g = new int[256];
        for (int c = 0; c < 256; c++) g[c] = (c & 15) >= 6 && (c & 15) <= 8 ? 40 : 64;
        return g;
    }

    @Test
    void aRavineFloorTakesTheRimAsItsGround_aShallowDipDoesNot() {
        int[] g = ravine();
        int[] r = CaveIndex.rimmed(g, 10, 8);
        assertEquals(64, r[7 + 16 * 5], "the floor's column: the rim");
        assertEquals(64, r[2 + 16 * 5], "the plain: unchanged");
        assertEquals(40, g[7 + 16 * 5], "a new array; the input stays");
        int[] dip = new int[256];
        for (int c = 0; c < 256; c++) dip[c] = (c & 15) == 7 ? 58 : 64;         // 6 down: a dip, not a ravine
        assertArrayEquals(dip, CaveIndex.rimmed(dip, 10, 8));
        assertArrayEquals(g, CaveIndex.rimmed(g, 0, 8), "drop 0: off");
    }

    @Test
    void aWideCanyonFloorFarFromBothWallsStaysGround() {
        int[] g = new int[256];
        for (int c = 0; c < 256; c++) g[c] = (c & 15) == 0 ? 64 : 40;            // a wall at x 0 only
        int[] r = CaveIndex.rimmed(g, 10, 8);
        assertEquals(64, r[8], "within 8 of the wall: the rim");
        assertEquals(40, r[9], "9 away: ground (rule 14, rough)");
    }

    @Test
    void theIndexedGroundIsTheRim_andTheRavineAirIsCaveAir() {
        CaveIndex idx = new CaveIndex();
        int[] r = CaveIndex.rimmed(ravine(), 10, 8);
        idx.put(CaveIndex.runs(0, 0, r, -64, (x, y, z) -> (x >= 6 && x <= 8 && y >= 40) || y >= 64));
        assertEquals(64, idx.groundAt(7, 5), "the ravine floor's column: its ground is the rim");
        assertEquals(64, idx.groundAt(2, 5));
        assertEquals(Integer.MAX_VALUE, idx.groundAt(100, 100), "a chunk not indexed: not known");
        assertEquals(Boolean.TRUE, idx.airAt(7, 50, 5), "the ravine's air is indexed");
        assertTrue(idx.volumeAt(7, 50, 5) >= 0, "and a volume (a cave mouth)");
        // the guard's depth: ground - y (a block of the ravine floor at 39 lies 25 under the rim: under ground)
        assertEquals(25, idx.groundAt(7, 5) - 39);
    }
}
