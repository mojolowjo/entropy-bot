package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * sim (owner 2026-11-09, before pricing a cave on its count so far: "we should make sure it's counting the cells in the
 * cave properly"): a hand-built cave of known size through the LIVE path - blocks to the index's runs (CaveIndex.runs), the
 * index to the record in budgeted steps (Sightings.caveFeed), the record's air-exposed count (CaveRecord.Exposure) - with
 * every number worked by hand. Run JBP-T1's cave was 666,540 cells with 645,240 counted (97%) when the planner asked, and
 * "not done": so also what the count SO FAR is while it is read - never over the end's, never falling - and what a chunk
 * loading later does to "done".
 *
 * <p>The cave: a tunnel 2 wide (z 4..5) and 2 high (y 30..31) along x 2..29 - 28 long, across the edge of chunks 0 0 and
 * 1 0 - closed at both ends, the ground far above (y 200). By hand: 28 x 2 x 2 = 112 cells; its walls 8 a block of length
 * (2 under, 2 over, 2 a side) + the two 2 x 2 ends = 8 x 28 + 8 = 232 air-exposed blocks; by Y: 56 under (y 29), 56 + 4
 * each at y 30 and y 31 (the sides and the ends), 56 over (y 32).
 */
class CaveCountProofTest {
    static final String D = J6bEkTest.D;

    static boolean tunnel(int x, int y, int z) { return x >= 2 && x <= 29 && y >= 30 && y <= 31 && z >= 4 && z <= 5; }

    static CaveIndex.ChunkRuns chunk(int cx) {
        int[] surface = new int[256];
        java.util.Arrays.fill(surface, 200);
        return CaveIndex.runs(cx, 0, surface, -64, CaveCountProofTest::tunnel);
    }

    /** The record's count run to its end ({@code maxTicks} at most); the exposure after each tick. */
    static List<CaveRecord.Exposure> run(Sightings s, CaveIndex idx, int x, int y, int z, int maxTicks) {
        List<CaveRecord.Exposure> seen = new ArrayList<>();
        for (int t = 0; t < maxTicks; t++) {
            s.caveFeed(D, idx, CaveRecord.BUDGET);
            CaveRecord.Exposure ex = s.caveExposure(D, x, y, z);
            seen.add(ex);
            if (ex.done() && s.caveFacts(D, x, y, z).loaded()) break;
        }
        return seen;
    }

    @Test
    void theHandBuiltTunnel_isCountedExactly_acrossTheChunkEdge() {
        CaveIndex idx = new CaveIndex();
        idx.put(chunk(0));
        idx.put(chunk(1));
        assertEquals(112, idx.volume(idx.volumeAt(5, 30, 4)).cells(), "the index: one volume of 28 x 2 x 2 across the edge");
        Sightings s = J6bEkTest.knownAt(5, 30, 4);
        List<CaveRecord.Exposure> seen = run(s, idx, 5, 30, 4, 50);
        CaveRecord.Exposure ex = seen.get(seen.size() - 1);
        assertTrue(ex.done(), "counted to its end");
        assertFalse(ex.open(), "closed, inside the loaded chunks: not a lower bound");
        assertEquals(112, s.caveFacts(D, 5, 30, 4).cellCount(), "the record's cells");
        assertEquals(232, ex.blocks(), "8 a block of length and the two ends");
        assertEquals(java.util.Map.of(29, 56, 30, 60, 31, 60, 32, 56), ex.byY(), "by Y: under, the sides and ends twice, over");
    }

    /**
     * The half loaded (chunk 0 0 only): the tunnel's x 2..15 - 14 long, 56 cells, 8 x 14 + the one end's 4 = 116 blocks, and
     * OPEN (its east face meets a chunk the index does not hold). Then chunk 1 0 loads: the count is not done again until
     * the new cells are looked round, the numbers never fall, and the end is the whole tunnel's.
     */
    @Test
    void aChunkLoadingLater_reopensTheCount_andTheNumbersOnlyGrow() {
        CaveIndex idx = new CaveIndex();
        idx.put(chunk(0));
        Sightings s = J6bEkTest.knownAt(5, 30, 4);
        List<CaveRecord.Exposure> half = run(s, idx, 5, 30, 4, 50);
        CaveRecord.Exposure h = half.get(half.size() - 1);
        assertTrue(h.done() && h.open(), "the loaded half is counted, and open at the loaded edge");
        assertEquals(56, s.caveFacts(D, 5, 30, 4).cellCount());
        assertEquals(116, h.blocks(), "8 x 14 and the west end's 4; the east face is pending, not counted");
        idx.put(chunk(1));
        List<CaveRecord.Exposure> whole = run(s, idx, 5, 30, 4, 50);
        CaveRecord.Exposure w = whole.get(whole.size() - 1);
        assertTrue(w.done() && !w.open());
        assertEquals(112, s.caveFacts(D, 5, 30, 4).cellCount());
        assertEquals(232, w.blocks());
    }

    /**
     * Run EK's volume (J6bEkTest.bigIndex: 64 x 64 x 51 = 208,896 cells; its floor and roof 2 x 64 x 64 = 8,192 blocks) is
     * read over many ticks: at every tick the count SO FAR is at most the end's and never falls, by Y too - a floor that
     * only rises (what a price on the count so far would read).
     */
    @Test
    void theCountSoFar_isAFloorThatOnlyRises() {
        CaveIndex idx = J6bEkTest.bigIndex();
        Sightings s = J6bEkTest.knownAt(5, 30, 5);
        List<CaveRecord.Exposure> seen = run(s, idx, 5, 30, 5, 600);
        CaveRecord.Exposure end = seen.get(seen.size() - 1);
        assertTrue(end.done(), "counted to its end in " + seen.size() + " ticks");
        assertEquals(8192, end.blocks());
        assertEquals(208_896, s.caveFacts(D, 5, 30, 5).cellCount());
        assertTrue(seen.size() > 10, "read over many ticks: " + seen.size());
        long last = 0;
        int notDone = 0;
        for (CaveRecord.Exposure ex : seen) {
            assertTrue(ex.blocks() >= last && ex.blocks() <= end.blocks(), "the count so far " + ex.blocks() + " after " + last + ", the end " + end.blocks());
            for (var e : ex.byY().entrySet()) assertTrue(e.getValue() <= end.byY().getOrDefault(e.getKey(), 0), "y " + e.getKey() + ": " + e.getValue());
            last = ex.blocks();
            if (!ex.done()) notDone++;
        }
        assertTrue(notDone > 5, "and it is 'not done' for most of the read: " + notDone + " of " + seen.size() + " ticks");
    }
}
