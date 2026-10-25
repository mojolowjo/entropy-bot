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
    private static final String LEFT = "cancelled: the bot left the search's start";

    @Test
    void queuedAndLeftStartAreNotKnownYetNeverNone() {
        PathPrice.Answer q = PricedAnswers.notYet(QUEUED, QUEUED, LEFT, 4);
        assertEquals(PathPrice.Kind.COMPUTING, q.kind());
        assertEquals("queued: pathCost.maxPending 4 searches in flight", q.reason());
        assertEquals(PathPrice.Kind.COMPUTING, PricedAnswers.notYet(LEFT, QUEUED, LEFT, 4).kind());
        assertNull(PricedAnswers.notYet("unreachable", QUEUED, LEFT, 4));     // a real answer goes through
        assertNull(PricedAnswers.notYet(null, QUEUED, LEFT, 4));              // a path answer has no reason
    }

    @Test
    void aMemoryAnswersLavaIsTheKeptPathsTail() {
        // a 5-cell path; lava ticks per cell (the start's is never charged): 0, 2, 0, 3, 1 -> whole path 6
        List<int[]> cells = List.of(new int[]{0, 64, 0}, new int[]{1, 64, 0}, new int[]{2, 64, 0}, new int[]{3, 64, 0}, new int[]{4, 64, 0});
        PricedAnswers.Lava lava = new PricedAnswers.Lava(PricedAnswers.cells(cells), new double[]{9, 2, 0, 3, 1});
        assertEquals(6, lava.total(), 1e-9);
        assertEquals(6, lava.suffix(PricedAnswers.cells(cells)), 1e-9);                 // exact
        assertEquals(4, lava.suffix(PricedAnswers.cells(cells.subList(1, 5))), 1e-9);   // from cell 1: cells 2..4
        assertEquals(1, lava.suffix(PricedAnswers.cells(cells.subList(3, 5))), 1e-9);
        assertEquals(0, lava.suffix(PricedAnswers.cells(cells.subList(4, 5))), 1e-9);
        // another path (not a tail): read fresh
        assertTrue(Double.isNaN(lava.suffix(PricedAnswers.cells(List.of(new int[]{1, 64, 1}, new int[]{4, 64, 0})))));
        assertNotEquals(PricedAnswers.cell(1, 64, 0), PricedAnswers.cell(0, 64, 1));
        assertNotEquals(PricedAnswers.cell(-1, 64, 0), PricedAnswers.cell(1, 64, 0));
    }

    @Test
    void theLoadLineCountsTheAsksAndAReuseLineSaysWhy() {
        // run P's healthy stretch in one second, by hand: 10 asks, 9 exact, 1 started
        String l = ProbeLines.asks(new long[]{12, 1, 9, 1, 0, 0, 1, 0, 0, 0});
        assertEquals("path asks +12: from memory exact +9 suffix +1 zero +1 no-path +0, joined in flight +0, searches started +1,"
                + " queued +0, cancelled +0, kept dropped +0", l);
        assertEquals("path asks: not counted by this fork jar", ProbeLines.asks(new long[]{-1}));
        assertEquals("path asks: not counted by this fork jar", ProbeLines.asks(null));
        assertEquals("dropped the kept answer to G: block changed at 3 64 0 (air -> crafting_table) (not known until the new search lands)",
                ProbeLines.reuse("to G: block changed at 3 64 0 (air -> crafting_table)"));
    }

    @Test
    void theSafetyAgeIsATuningEntryAndKeepSIsTheOldRouteOnly() {
        assertEquals(300, Tuning.get("pathCost.maxKeepS"), 1e-9);
        assertEquals(300, PathPrice.maxKeepS(), 1e-9);
        assertTrue(String.join("\n", Tuning.lines("pathCost.keepS")).contains("pricedPath.on 0 only"));
        assertTrue(String.join("\n", Tuning.lines("pricedPath.keepTicks")).contains("NO-PATH"));
    }
}
