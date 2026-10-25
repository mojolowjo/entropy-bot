package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z57 (path memory; owner 2026-10-25: "while doing stuff it should not forget the path that was already planned"; run P
 * 23:46-23:50: 406 priced paths to 93 goals, 180 exact repeats). The memory itself is the fork's (PathMemory, its JUnit
 * PathMemoryTest: 10 asks from one cell = 1 search, the walk along = 0 searches and the price falls by the walked moves,
 * a block placed = dropped with the cell, planks -> sticks keeps a walk that places nothing, the goal cell = 0, two asks =
 * one search); here the mod's half with hand-made fakes: a "queued" / "left the start" answer is "not known yet" (rule 15),
 * a memory answer's lava part is the kept path's tail, the probe lines, the Tuning entry.
 */
class Z57PathMemoryTest {

    private static final String QUEUED = "queued";

    @Test
    void queuedIsNotKnownYetNeverNone() {
        PathPrice.Answer q = PricedAnswers.notYet(QUEUED, QUEUED, 4);
        assertEquals(PathPrice.Kind.COMPUTING, q.kind());
        assertEquals("queued: pathCost.maxPending 4 searches in flight", q.reason());
        assertNull(PricedAnswers.notYet("unreachable", QUEUED, 4));     // a real answer goes through
        assertNull(PricedAnswers.notYet("chunks not loaded", QUEUED, 4)); // goes through as Kind.CHUNKS: "path not known"
        assertNull(PricedAnswers.notYet(null, QUEUED, 4));              // a path answer has no reason
    }

    /** A fake level's lava read: each cell's ticks from {@code ticks} by x, counted. */
    private int reads;

    private java.util.function.Supplier<double[]> reader(List<int[]> cells, double[] byX) {
        return () -> {
            reads++;
            double[] out = new double[cells.size()];
            for (int i = 0; i < out.length; i++) out[i] = byX[cells.get(i)[0]];
            return out;
        };
    }

    @Test
    void theLavaPartIsReadOncePerLandingAndSummedForItsTails() {
        // review 9: a 5-cell path; lava ticks per cell by x (the start's is never charged): 9, 2, 0, 3, 1 -> whole path 6
        List<int[]> cells = List.of(new int[]{0, 64, 0}, new int[]{1, 64, 0}, new int[]{2, 64, 0}, new int[]{3, 64, 0}, new int[]{4, 64, 0});
        double[] lavaByX = {9, 2, 0, 3, 1};
        PricedAnswers.LavaKept kept = new PricedAnswers.LavaKept(16);
        assertEquals(6, kept.lava("G", 1, PricedAnswers.cells(cells), reader(cells, lavaByX)), 1e-9);                  // landing 1: read
        assertEquals(6, kept.lava("G", 1, PricedAnswers.cells(cells), reader(cells, lavaByX)), 1e-9);                  // exact: kept
        List<int[]> from1 = cells.subList(1, 5), from3 = cells.subList(3, 5);
        assertEquals(4, kept.lava("G", 1, PricedAnswers.cells(from1), reader(from1, lavaByX)), 1e-9);                  // suffix: cells 2..4
        assertEquals(1, kept.lava("G", 1, PricedAnswers.cells(from3), reader(from3, lavaByX)), 1e-9);
        assertEquals(1, reads);
        // lava flowed in by cell 2 and the path was searched again (landing 2): read again, though the cells are the same
        double[] now = {9, 2, 5, 3, 1};
        assertEquals(11, kept.lava("G", 2, PricedAnswers.cells(cells), reader(cells, now)), 1e-9);
        assertEquals(2, reads);
        assertEquals(9, kept.lava("G", 2, PricedAnswers.cells(from1), reader(from1, now)), 1e-9);
        assertEquals(2, reads);
        // no landing (0 at the goal, an older fork): read, never kept
        assertEquals(1, kept.lava("G", -1, PricedAnswers.cells(from3), reader(from3, now)), 1e-9);
        assertEquals(3, reads);
        assertNotEquals(PricedAnswers.cell(1, 64, 0), PricedAnswers.cell(0, 64, 1));
        assertNotEquals(PricedAnswers.cell(-1, 64, 0), PricedAnswers.cell(1, 64, 0));
    }

    @Test
    void theLoadLineCountsTheAsksAndAReuseLineSaysWhy() {
        // run P's healthy stretch in one second, by hand: 10 asks, 9 exact, 1 started
        String l = ProbeLines.asks(new long[]{12, 1, 9, 1, 0, 0, 1, 0, 0, 0});
        assertEquals("path asks +12: from memory exact +9 suffix +1 zero +1 no-path +0, joined in flight +0, searches started +1,"
                + " queued +0, cancelled +0, kept dropped +0", l);
        assertTrue(ProbeLines.asks(new long[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}).endsWith(", not loaded +1"));
        assertTrue(ProbeLines.asks(new long[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 3}).endsWith(", not loaded +1, stamp builds +3"));
        assertEquals("path asks: not counted by this fork jar", ProbeLines.asks(new long[]{-1}));
        assertEquals("path asks: not counted by this fork jar", ProbeLines.asks(null));
        assertEquals("dropped the kept answer to G: block changed at 3 64 0 (air -> crafting_table) (not known until the new search lands)",
                ProbeLines.reuse("to G: block changed at 3 64 0 (air -> crafting_table)"));
    }

    @Test
    void theSafetyAgeIsATuningEntryAndKeepSIsTheOldRouteOnly() {
        assertEquals(300, Tuning.get("pathCost.maxKeepS"), 1e-9);
        assertEquals(300, PathPrice.maxKeepS(), 1e-9);
        assertTrue(Tuning.lines("pathCost.restartBlocks").isEmpty());   // review 2 item 4: removed (nothing is cancelled for a move)
        assertTrue(String.join("\n", Tuning.lines("pathCost.keepS")).contains("pricedPath.on 0 only"));
        assertTrue(String.join("\n", Tuning.lines("pricedPath.keepTicks")).contains("NO-PATH"));
    }
}
