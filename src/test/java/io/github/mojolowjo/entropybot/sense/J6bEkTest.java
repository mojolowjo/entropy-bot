package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.cave.CaveWalk;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b run EK (02:30:20-02:46:19: every caving tick 7-70 s - cave 2's 209,647 cells flooded for every branch, several
 * times a tick and on every planner ask; 02:24:18: cave 1 "DONE, cells seen 0 -> 0" from the lip 3 x 2 from its entry):
 * the record fed in place by budgeted steps, its sums and frontier incremental, its facts cached; the record's done the one
 * done reader, the first target the unseen cell nearest the entry, the lip done only with a cave cell in reach.
 */
class J6bEkTest {
    static final String D = "minecraft:overworld";

    /** A volume of EK's size: x 0..63, z 0..63, y 10..60 - one run a column, 4 x 4 chunks, 208,896 cells, no sky over it. */
    static CaveIndex bigIndex() {
        CaveIndex idx = new CaveIndex();
        for (int cx = 0; cx < 4; cx++)
            for (int cz = 0; cz < 4; cz++) {
                int[] surface = new int[256];
                java.util.Arrays.fill(surface, 200);
                int[][][] runs = new int[256][][];
                for (int c = 0; c < 256; c++) runs[c] = new int[][]{{10, 60}};
                idx.put(new CaveIndex.ChunkRuns(cx, cz, surface, runs));
            }
        return idx;
    }

    static Sightings knownAt(int x, int y, int z) {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(x, y, z, CaveIndex.Size.LARGE, CaveIndex.Depth.STONE, List.of(Sightings.cell(x, y, z))), 1));
        return s;
    }

    /**
     * (1) EK's cave 2 at its size: the feed reads it in place in budgeted steps; each tick of the caving step (the feed,
     * the stand, the facts, the frontier, the first target) stays under the budget; the planner's 45 asks read the cache.
     */
    @Test
    void aVolumeOfEksSize_everyTickUnderTheBudget_theAsksReadTheCache() {
        CaveIndex idx = bigIndex();
        Sightings s = knownAt(5, 30, 5);
        int over = 256 + 51;                                                     // one chunk's run count, one run (the doc's bound)
        int ticks = 0;
        long most = 0;
        while (ticks < 100) {
            ticks++;
            int fed = s.caveFeed(D, idx, CaveRecord.BUDGET);
            assertTrue(fed <= CaveRecord.BUDGET + over, "tick " + ticks + ": the feed touched " + fed);
            s.caveTouched(D, 5, 30, 5);
            s.caveStood(D, 5, 30, 5, 5 + ticks % 20, 30, 5, ticks);               // the bot walking in it
            var f = s.caveFacts(D, 5, 30, 5);
            s.caveFrontier(D, 5, 30, 5);
            s.caveFirstUnseen(D, 5, 30, 5);
            long readers = s.caveTouched(D, 5, 30, 5);
            most = Math.max(most, readers);
            assertTrue(readers <= CaveRecord.BUDGET, "tick " + ticks + ": the step's reads touched " + readers);
            if (f.loaded() && fed == 0) break;
        }
        var f = s.caveFacts(D, 5, 30, 5);
        assertTrue(f.loaded(), "fed to its end in " + ticks + " ticks");
        assertTrue(ticks <= 20, "about 13 ticks for 208,896 cells: " + ticks);
        assertEquals(64 * 64 * 51, f.cellCount());
        assertTrue(f.unseen() < f.cellCount() && f.unseen() > f.cellCount() - 2000, "a walk's strip seen: " + f.unseen());
        assertFalse(f.done(), "seen a strip of it, branches left");
        assertTrue(f.groups() >= 1);
        s.caveTouched(D, 5, 30, 5);
        for (int i = 0; i < 45; i++) s.caveFacts(D, 5, 30, 5);                  // "cave 45 asks"
        assertEquals(0, s.caveTouched(D, 5, 30, 5), "the planner's asks read the cached facts: no cell touched");
        assertTrue(s.caveFrontier(D, 5, 30, 5).stream().allMatch(g -> g.behind() <= CaveRecord.BRANCH_MIN + 1), "behind capped at BRANCH_MIN + 1");
        assertEquals(0, s.caveFeed(D, idx, CaveRecord.BUDGET), "the index unchanged: nothing fed");
    }

    /**
     * 88b28290 review 1: a frontier of tens of thousands of cells (16 seen planes across EK's size of volume, 52,224 cells,
     * every one beside unseen air) is regrouped in steps - each tick's regroup and reads under the budget, done never
     * claimed while the groups lag, the 16 branches once it is through.
     */
    @Test
    void aFrontierOfThousands_isRegroupedUnderTheBudget() {
        CaveIndex idx = bigIndex();
        Sightings s = knownAt(5, 30, 5);
        for (int t = 0; t < 40 && !s.caveFacts(D, 5, 30, 5).loaded(); t++) s.caveFeed(D, idx, CaveRecord.BUDGET);
        List<Long> planes = new ArrayList<>();
        for (int x = 0; x < 64; x += 4) for (int z = 0; z < 64; z++) for (int y = 10; y <= 60; y++) planes.add(Sightings.cell(x, y, z));
        s.caveSaw(D, 5, 30, 5, planes);
        s.caveTouched(D, 5, 30, 5);
        int ticks = 0;
        boolean lagged = false;
        while (ticks < 50) {
            ticks++;
            s.caveFeed(D, idx, CaveRecord.BUDGET);                                // the tick's feed (nothing new: it only starts the tick)
            var f = s.caveFacts(D, 5, 30, 5);
            s.caveFrontier(D, 5, 30, 5);
            s.caveFacts(D, 5, 30, 5);
            long t = s.caveTouched(D, 5, 30, 5);
            assertTrue(t <= CaveRecord.BUDGET + 3264 + 64, "tick " + ticks + ": " + t + " cells touched (the budget, past it by one group's cells at most)");
            assertFalse(f.done(), "never done while the groups lag or with 16 branches open");
            if (f.groups() == 16) break;
            lagged = true;
        }
        assertTrue(lagged, "52,224 frontier cells took more than one tick");
        assertEquals(16, s.caveFacts(D, 5, 30, 5).groups(), "the 16 seen planes, each a branch (after " + ticks + " ticks)");
    }

    /** 88b28290 review 3: while the cells are read (filling), a saved done is never trusted - the step waits. */
    @Test
    void whileFilling_aSavedDoneIsNotTrusted() {
        var filling = new CaveRecord.Facts(5, 30, 5, 208896, 0, 1, 0, true, java.util.Map.of(), 3, 0, 0, false, "x", false, true);
        var next = CaveWalk.next(0, 1, 0, 10, filling, List.of(), List.of(), new int[]{5, 30, 5}, 4.5, null);
        assertTrue(next instanceof CaveWalk.Wait, "filling with a past session's done: wait - " + next);
    }

    /** The in-place sums and frontier agree with a whole count (a small cave, cells given whole, then seen). */
    @Test
    void theIncrementalSumsMatchAWholeCount() {
        Sightings s = knownAt(0, 40, 0);
        List<Long> cells = new ArrayList<>(J6bCaveRecordTest.cave());
        s.caveCells(D, 0, 40, 0, cells.subList(0, 100));                         // half the chunks first
        s.caveStood(D, 0, 40, 0, 0, 40, 0, 1);
        s.caveCells(D, 0, 40, 0, cells);                                         // the rest arrives later
        var f = s.caveFacts(D, 0, 40, 0);
        assertEquals(200, f.cellCount());
        assertEquals(200 - 1 - CaveRecord.SEE_STEPS, f.unseen());
        assertEquals(1, f.groups(), "the frontier grew with the cells that arrived");
        s.caveSaw(D, 0, 40, 0, cells);
        assertTrue(s.caveFacts(D, 0, 40, 0).done(), "every cell seen");
    }

    /**
     * (2) EK's lip stop: the feet 3 x 2 from the entry, outside the cave's air - a cave cell is in reach, so the lip walk is
     * done there; the first stand sees into the cave; the step is not done. From farther off nothing in reach: not done,
     * and the step's next is the unseen cell nearest the entry, never DONE on an empty frontier.
     */
    @Test
    void eksLipStop_doesNotEndTheStep() {
        Sightings s = knownAt(-59, 63, 71);
        List<Long> cells = new ArrayList<>();
        for (int x = -59; x >= -90; x--) for (int y = 60; y <= 63; y++) cells.add(Sightings.cell(x, y, 71));     // a tunnel west from the entry
        s.caveCells(D, -59, 63, 71, cells);
        int[] lip = {-56, 63, 73}, entry = {-59, 63, 71};
        assertTrue(ExploreRules.withinReach(lip, entry), "EK's lip: the entry in reach (run EL: the lip goal decides arrival, J6bCaveStepTest)");

        var before = s.caveFacts(D, -59, 63, 71);
        assertFalse(before.done(), "nothing seen yet: not done");
        var first = CaveWalk.next(0, 1, 0, 10, before, s.cavePassages(D, -59, 63, 71, lip, java.util.Set.of(), 3, 3, 4096).passages(), List.of(), new int[]{-50, 63, 75}, 4.5, s.caveFirstUnseen(D, -59, 63, 71));
        assertTrue(first instanceof CaveWalk.Goto g && java.util.Arrays.equals(g.cell(), entry), "the first target: the entry itself (unseen) - " + first);

        assertTrue(s.caveStood(D, -59, 63, 71, lip[0], lip[1], lip[2], 1) > 0, "the lip sees into the cave");
        var next = CaveWalk.next(0, 1, 0, 10, s.caveFacts(D, -59, 63, 71), s.cavePassages(D, -59, 63, 71, lip, java.util.Set.of(), 3, 3, 4096).passages(), List.of(), lip, 4.5, s.caveFirstUnseen(D, -59, 63, 71));
        assertTrue(next instanceof CaveWalk.Goto, "into the cave, never DONE: " + next);
        assertFalse(s.caveFacts(D, -59, 63, 71).done());
    }
}
