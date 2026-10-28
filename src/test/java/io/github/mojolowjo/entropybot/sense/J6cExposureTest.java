package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6c (the owner's rulings 1 and 2, 2026-10-28): a known cave's air-exposed blocks through the live path - the
 * index's air answer (CaveIndex.airAt) under Nearby.exposed, counted in budgeted steps after the walk's own reads, the
 * volume OPEN where it meets a chunk the index does not hold. EK's volume (J6bEkTest.bigIndex: x 0..63, z 0..63, y 10..60,
 * 4 x 4 chunks): its air-exposed blocks are its floor (y 9) and roof (y 61), 64 x 64 each, and nothing at its sides - the
 * chunks round it are not indexed, so its 4 x 51 x 64 side faces are pending: open, a lower bound.
 */
class J6cExposureTest {
    static final String D = J6bEkTest.D;

    @Test
    void theIndexsAir_answers() {
        CaveIndex idx = J6bEkTest.bigIndex();
        assertEquals(Boolean.TRUE, idx.airAt(5, 30, 5), "an air run");
        assertEquals(Boolean.FALSE, idx.airAt(5, 9, 5), "under the run: not air");
        assertEquals(Boolean.TRUE, idx.airAt(5, 200, 5), "at the column's ground: open to the sky");
        assertNull(idx.airAt(64, 30, 5), "a chunk the index does not hold: not known");
    }

    @Test
    void eksVolume_itsFloorAndRoof_counted_inBudgetedSteps_openAtItsSides() {
        CaveIndex idx = J6bEkTest.bigIndex();
        Sightings s = J6bEkTest.knownAt(5, 30, 5);
        for (int t = 0; t < 40 && !s.caveFacts(D, 5, 30, 5).loaded(); t++) s.caveFeed(D, idx, CaveRecord.BUDGET);
        int ticks = 0;
        CaveRecord.Exposure ex = null;
        while (ticks < 200) {
            ticks++;
            s.caveFeed(D, idx, CaveRecord.BUDGET);                               // the tick's start
            s.caveTouched(D, 5, 30, 5);
            ex = s.caveExposure(D, 5, 30, 5);
            long t = s.caveTouched(D, 5, 30, 5);
            assertTrue(t <= CaveRecord.BUDGET + 5, "tick " + ticks + ": the exposure step touched " + t + " (the budget, past it by one cell's faces at most)");
            if (ex.done()) break;
        }
        assertTrue(ex.done(), "every cell looked round in " + ticks + " ticks");
        assertTrue(ticks > 1, "208,896 cells x 6 faces take more than one tick");
        assertEquals(2 * 64 * 64, ex.blocks(), "the floor and the roof");
        assertEquals(64 * 64, ex.byY().get(9));
        assertEquals(64 * 64, ex.byY().get(61));
        assertTrue(ex.open(), "its sides meet chunks not indexed: a lower bound");
    }

    /**
     * J6c review (1ce5ce19): a FULLY FED volume's exposure completes over the following ticks while another cave's feed takes
     * the whole budget (the feeder's loop never reaches it): every record's tick starts in Sightings.caveFeed, so its
     * spend starts again each tick (was: only in its own feed step - "left" went to 0 and the count starved until the other
     * feed ended). B: one chunk, y 10..60 (13,056 cells, ~78k face tests: several ticks); A: EK's volume, 13 ticks of feed.
     */
    @Test
    void aFullyFedVolume_itsExposureCompletes_whileAnotherCavesFeedTakesTheBudget() {
        CaveIndex idx = new CaveIndex();
        int[] surface = new int[256];
        java.util.Arrays.fill(surface, 200);
        int[][][] runs = new int[256][][];
        for (int c = 0; c < 256; c++) runs[c] = new int[][]{{10, 60}};
        idx.put(new CaveIndex.ChunkRuns(10, 0, surface, runs));               // B: x 160..175, z 0..15
        Sightings s = J6bEkTest.knownAt(5, 30, 5);                            // A first: the feeder's loop reaches it first
        assertNotNull(s.knowCave(D, new CaveIndex.Known(165, 30, 5, CaveIndex.Size.MEDIUM, CaveIndex.Depth.STONE, java.util.List.of(Sightings.cell(165, 30, 5))), 1));
        for (int t = 0; t < 10 && !s.caveFacts(D, 165, 30, 5).loaded(); t++) s.caveFeed(D, idx, CaveRecord.BUDGET);
        assertTrue(s.caveFacts(D, 165, 30, 5).loaded(), "B fed whole (A has no chunks yet)");
        CaveIndex a = J6bEkTest.bigIndex();                                    // A's chunks load: its feed takes every tick's budget
        for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) {
            int[][][] ar = new int[256][][];
            for (int c = 0; c < 256; c++) ar[c] = new int[][]{{10, 60}};
            idx.put(new CaveIndex.ChunkRuns(cx, cz, surface, ar));
        }
        assertNotNull(a);
        int ticks = 0;
        CaveRecord.Exposure ex = null;
        while (ticks < 8) {
            ticks++;
            assertTrue(s.caveFeed(D, idx, CaveRecord.BUDGET) >= CaveRecord.BUDGET, "A's feed takes the budget: the loop never reaches B");
            ex = s.caveExposure(D, 165, 30, 5);
            if (ex.done()) break;
        }
        assertTrue(ex.done(), "B's exposure completed in " + ticks + " ticks while A feeds");
        assertTrue(ticks > 1, "several ticks of it");
        assertFalse(s.caveFacts(D, 5, 30, 5).loaded(), "A still feeding then");
        assertEquals(2 * 256, ex.blocks(), "B's floor and roof");
    }
}
