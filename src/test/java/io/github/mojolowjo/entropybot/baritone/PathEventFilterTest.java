package io.github.mojolowjo.entropybot.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.1: Baritone's idle CANCELED every tick: the first passes, repeats are dropped, one summary a minute. */
class PathEventFilterTest {

    @Test
    void repeatsAreDroppedWithOneSummaryAMinute() {
        PathEventFilter f = new PathEventFilter();
        long t = 1_000_000;
        assertEquals(PathEventFilter.Action.PASS, f.offer("CALC_STARTED", t));
        assertEquals(PathEventFilter.Action.PASS, f.offer("AT_GOAL", t));
        assertEquals(PathEventFilter.Action.PASS, f.offer("CANCELED", t), "the first CANCELED after a walk counts");
        assertEquals(PathEventFilter.Action.SUMMARY, f.offer("CANCELED", t + 50), "the first repeat: a summary line");
        assertTrue(f.summary().startsWith("CANCELED x1 "));
        int passed = 0;
        for (int i = 0; i < 1199; i++) if (f.offer("CANCELED", t + 100 + i * 50L) != PathEventFilter.Action.DROP) passed++;
        assertEquals(0, passed, "a minute of idle ticks: nothing more");
        assertEquals(PathEventFilter.Action.SUMMARY, f.offer("CANCELED", t + 60_100));
        assertEquals("CANCELED x1200 while idle (Baritone's idle tick; repeats dropped, one line a minute)", f.summary());
        // a new walk: its events pass, and its CANCELED again
        assertEquals(PathEventFilter.Action.PASS, f.offer("CALC_STARTED", t + 61_000));
        assertEquals(PathEventFilter.Action.PASS, f.offer("CANCELED", t + 62_000));
        assertEquals(1201, f.droppedTotal());
    }
}
