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

    /**
     * job-planner-stick (worlds run 2, w060: the open mountainside indexed as one cave of 100,000 to 496,000 cells): one
     * higher side is a cliff, not a ravine - its foot stays open ground (was: "within 8 of the wall: the rim").
     */
    @Test
    void aCliffsFoot_oneWallOnly_staysGround() {
        int[] g = new int[256];
        for (int c = 0; c < 256; c++) g[c] = (c & 15) == 0 ? 64 : 40;            // a wall at x 0 only
        assertArrayEquals(g, CaveIndex.rimmed(g, 10, 8), "no column has a wall on both sides");
    }

    /** w060's hillside: a slope steeper than the drop within r (2 up a block: 16 in 8) is open surface on every column. */
    @Test
    void aSteepSlope_isOpenSurface_neverCaveAir() {
        int[] slope = new int[256];
        for (int c = 0; c < 256; c++) slope[c] = 60 + 2 * (c & 15) + (c >> 4);   // rises east, and a little south
        assertArrayEquals(slope, CaveIndex.rimmed(slope, 10, 8));
        CaveIndex idx = new CaveIndex();
        idx.put(CaveIndex.runs(0, 0, CaveIndex.rimmed(slope, 10, 8), -64, (x, y, z) -> y >= 60 + 2 * x + z));
        assertEquals(71, idx.groundAt(3, 5), "the slope's own ground (was the top within 8 blocks, 95)");
        assertTrue(idx.volumeAt(3, 80, 5) < 0, "the air over the slope is in no volume: nothing for a cave record to grow from");
    }

    /** A ravine that runs across the chunk at a slant has its two walls on the lines through its floor; the lower wall is its rim. */
    @Test
    void aSlantedRavine_andUnevenWalls_takeTheLowerWall() {
        int[] g = new int[256];
        for (int c = 0; c < 256; c++) g[c] = Math.abs((c & 15) - (c >> 4)) <= 1 ? 40 : 64;
        assertEquals(64, CaveIndex.rimmed(g, 10, 8)[7 + 16 * 7], "walls north-west and south-east of the slanted floor");
        int[] uneven = new int[256];
        for (int c = 0; c < 256; c++) uneven[c] = (c & 15) < 6 ? 70 : (c & 15) <= 8 ? 40 : 58;   // a 70 wall west, a 58 wall east
        assertEquals(58, CaveIndex.rimmed(uneven, 10, 8)[7 + 16 * 5], "the air under the lower wall is in the ravine, the air over it is open");
    }

    /**
     * Review round 2: a north-south ravine with its floor at x 0..2 of this chunk, its east wall from x 3 and its west
     * wall in the neighbour chunk (x -2 and on). With the neighbour's ground the floor takes the rim; with the neighbour
     * not loaded the west side is not known and the floor stays open ground (never a wall made up).
     */
    @Test
    void aRavineAcrossTheChunkBorder_readsItsOtherWallFromTheNeighbour() {
        int[] g = new int[256];
        for (int c = 0; c < 256; c++) g[c] = (c & 15) <= 2 ? 40 : 64;
        CaveIndex.GroundAround west = (lx, lz) -> lx < -1 ? 64 : lx < 0 ? 40 : 64;       // the floor goes on one column west, then the wall
        int[] r = CaveIndex.rimmed(g, 10, 8, west);
        for (int z = 0; z < 16; z++) for (int x = 0; x <= 2; x++) assertEquals(64, r[x + 16 * z], "the floor at " + x + " " + z + ": the rim");
        assertEquals(64, r[9 + 16 * 5], "the plain: unchanged");
        assertEquals(40, CaveIndex.rimmed(g, 10, 8)[1 + 16 * 5], "the neighbour not loaded: not known, the floor stays open ground");
        // a cliff whose top lies across the border is still one wall: its foot stays ground
        int[] foot = new int[256];
        java.util.Arrays.fill(foot, 40);
        assertArrayEquals(foot, CaveIndex.rimmed(foot, 10, 8, (lx, lz) -> lx < 0 ? 64 : 40), "a wall west only, plain ground on the other three sides");
    }

    /** Review round 2: a slanted ravine too wide for the x and z lines (walls 5 away, r 4) has its walls on the diagonal across it. */
    @Test
    void aSlantedRavine_widerThanTheStraightLinesReach_isFoundOnTheDiagonal() {
        int[] g = new int[256];
        for (int c = 0; c < 256; c++) g[c] = Math.abs((c & 15) - (c >> 4)) <= 4 ? 40 : 64;
        int[] r = CaveIndex.rimmed(g, 10, 4);
        assertEquals(64, r[7 + 16 * 7], "walls at 10 4 and 4 10, three steps along the diagonal across the ravine");
        int[] straight = new int[256];
        for (int c = 0; c < 256; c++) straight[c] = Math.abs((c & 15) - 7) <= 4 ? 40 : 64;   // the same width north-south: walls 5 away on the x line
        assertEquals(40, CaveIndex.rimmed(straight, 10, 4)[7 + 16 * 7], "out of reach of every line: the diagonals reach 4 x steps too");
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
