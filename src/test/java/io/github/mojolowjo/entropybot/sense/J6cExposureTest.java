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
        while (ticks < 400) {
            ticks++;
            s.caveTouched(D, 5, 30, 5);
            s.caveFeed(D, idx, CaveRecord.BUDGET);                               // the tick: the feed, then the exposure's share
            ex = s.caveExposure(D, 5, 30, 5);                                    // a read only
            long t = s.caveTouched(D, 5, 30, 5);
            assertTrue(t <= CaveRecord.BUDGET / 2 + 5, "tick " + ticks + ": the exposure touched " + t + " (its half of the budget, past it by one cell's faces at most)");
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
     * J6c review of 98889d5f: ONE per-tick budget for every cave - 10 known caves with their air-exposed counts pending,
     * every cell any of them touches in a tick (the feeds, the counts, a stand and a regroup of the walked one) at most
     * CaveRecord.BUDGET, and each count still completes (round-robin: each tick another cave first). Each cave: one chunk,
     * y 10..60 (13,056 cells, ~78k face tests).
     */
    @Test
    void tenCavesPending_everyTickUnderOneBudget_eachCompletes() {
        CaveIndex idx = new CaveIndex();
        int[] surface = new int[256];
        java.util.Arrays.fill(surface, 200);
        Sightings s = new Sightings();
        int[][] entry = new int[10][];
        for (int i = 0; i < 10; i++) {
            int cx = 10 + 2 * i;                                                 // a chunk apart: ten volumes
            int[][][] runs = new int[256][][];
            for (int c = 0; c < 256; c++) runs[c] = new int[][]{{10, 60}};
            idx.put(new CaveIndex.ChunkRuns(cx, 0, surface, runs));
            entry[i] = new int[]{cx * 16 + 5, 30, 5};
            assertNotNull(s.knowCave(D, new CaveIndex.Known(entry[i][0], 30, 5, CaveIndex.Size.MEDIUM, CaveIndex.Depth.STONE, java.util.List.of(Sightings.cell(entry[i][0], 30, 5))), 1));
        }
        int ticks = 0;
        boolean all = false;
        while (ticks < 400 && !all) {
            ticks++;
            for (int[] e : entry) s.caveTouched(D, e[0], e[1], e[2]);
            int fed = s.caveFeed(D, idx, CaveRecord.BUDGET);                     // the tick's start: the feeds, the counts
            long afterFeed = 0;
            for (int[] e : entry) afterFeed += s.caveTouched(D, e[0], e[1], e[2]);
            s.caveStood(D, entry[0][0], 30, 5, entry[0][0], 30, 5, ticks);       // the bot walking in the first one
            s.cavePassageList(D, entry[0][0], 30, 5);                            // D15 pass two: its passages
            long sum = afterFeed;
            all = true;
            for (int[] e : entry) {
                sum += s.caveTouched(D, e[0], e[1], e[2]);
                all &= s.caveExposure(D, e[0], e[1], e[2]).done();
            }
            assertTrue(sum <= CaveRecord.BUDGET + 256 + 51 + 5, "tick " + ticks + ": all caves together touched " + sum + " (one budget; one run or one chunk's runs and one cell's faces over at most; the feeds " + fed + ", feeds and counts " + afterFeed + ")");
        }
        assertTrue(all, "every cave's count completed in " + ticks + " ticks");
        for (int[] e : entry) assertEquals(2 * 256, s.caveExposure(D, e[0], e[1], e[2]).blocks(), "each cave's floor and roof");
    }
}