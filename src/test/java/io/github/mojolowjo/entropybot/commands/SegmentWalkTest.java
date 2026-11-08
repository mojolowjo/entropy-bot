package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.StandWait;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** job-segmod: the stand walks the cheapest way's running first segment while its price computes (rule 22). */
class SegmentWalkTest {
    @AfterEach void reset() { Tuning.reset(); }

    @Test void standEndsOnARunningSegmentWithEnoughCells() {
        int min = StandWait.segmentMinCells();
        assertEquals(4, min);
        assertTrue(StandWait.walksSegment(StandWait.segmentWalkOn(), true, 9, min));
        assertFalse(StandWait.walksSegment(true, true, 3, min), "a short segment: the stand stands");
        assertFalse(StandWait.walksSegment(true, false, 9, min), "a landed price is walked as a price, not a segment");
        assertEquals("walking the first segment (9 cells) of the walk to 1 2 3 while its price computes", StandWait.segmentWords(9, "the walk to 1 2 3"));
    }

    @Test void gateOffKeepsTheStand() {
        Tuning.set("pathCost.firstSegmentWalk", 0);
        assertFalse(StandWait.segmentWalkOn());
        assertFalse(StandWait.walksSegment(StandWait.segmentWalkOn(), true, 20, 4));
    }

    @Test void noPathLandingEndsTheWalkAndStandsAgain() {
        StandWait w = new StandWait();
        w.begin(0);
        assertNull(w.abandon("unreachable"), "nothing walked: nothing to end");
        w.walking("the walk to 1 2 3");
        assertEquals("the walk to 1 2 3", w.walkingWay());
        String l = w.abandon("unreachable");
        assertTrue(l.contains("the walk to 1 2 3") && l.contains("unreachable") && l.contains("held as no path"), l);
        assertNull(w.walkingWay());
        assertTrue(w.standing(), "the stand stands again");
    }

    @Test void probeLineSaysSegmentCounts() {
        long[] none = {-1};
        String l = new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, none, none, none, new long[]{5, 3, 2, 1}).line(1);
        assertTrue(l.contains("segments offered +5 walked +3 replaced +2 abandoned +1"), l);
        assertTrue(new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, none, none, none, none).line(1)
                .contains("segment counts not readable in this fork jar"));
        assertNull(ProbeLines.segmentCounts(new Object()));
    }
}
