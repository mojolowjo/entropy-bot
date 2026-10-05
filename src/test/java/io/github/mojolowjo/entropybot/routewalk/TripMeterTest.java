package io.github.mojolowjo.entropybot.routewalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripMeterTest {
    @Test
    void twoSegmentsWithAStopBetween() {
        TripMeter m = new TripMeter(100);
        m.event(101, "path", "CALC_STARTED");
        m.event(102, "log", "[Baritone] Took 12ms, 65000 movements considered");
        m.event(102, "log", "[Baritone] PathNode map size: 3000");
        m.event(110, "path", "CALC_FINISHED_NOW_EXECUTING");
        m.event(300, "path", "PATH_FINISHED_NEXT_STILL_CALCULATING");
        m.event(301, "path", "NEXT_SEGMENT_CALC_STARTED");
        m.event(305, "log", "Took 20ms, 167000 movements considered");
        m.event(305, "log", "PathNode map size: 7600");
        m.event(340, "path", "CALC_FINISHED_NOW_EXECUTING");
        m.event(600, "path", "AT_GOAL");
        assertEquals(2, m.searches());
        assertEquals(2, m.segments());
        assertEquals(40 / 20.0, m.stoppedSeconds(), 1e-9);
        assertEquals(10 / 20.0, m.firstStepSeconds(), 1e-9);
        assertEquals(10600, m.nodes());
        assertEquals(232000, m.movements());
        assertEquals(32, m.searchMs());
        assertEquals(4, m.debugLines());
        assertEquals(0, m.failures());
        assertFalse(m.lost());
    }

    @Test
    void splicedSegmentsDoNotStop() {
        TripMeter m = new TripMeter(0);
        m.event(0, "path", "CALC_STARTED");
        m.event(5, "path", "CALC_FINISHED_NOW_EXECUTING");
        m.event(50, "path", "NEXT_SEGMENT_CALC_STARTED");
        m.event(60, "path", "NEXT_SEGMENT_CALC_FINISHED");
        m.event(100, "path", "CONTINUING_ONTO_PLANNED_NEXT");
        m.event(200, "path", "SPLICING_ONTO_NEXT_EARLY");
        assertEquals(3, m.segments());
        assertEquals(0, m.stoppedSeconds(), 1e-9);
    }

    @Test
    void legsStopBetweenLegsIsCounted() {
        TripMeter m = new TripMeter(0);
        m.event(2, "path", "CALC_STARTED");
        m.event(4, "path", "CALC_FINISHED_NOW_EXECUTING");
        m.event(200, "path", "AT_GOAL");
        m.event(204, "path", "CALC_STARTED");
        m.event(210, "path", "CALC_FINISHED_NOW_EXECUTING");
        assertEquals(10 / 20.0, m.stoppedSeconds(), 1e-9);
    }

    @Test
    void failuresAndAnOpenStopAtTheEnd() {
        TripMeter m = new TripMeter(0);
        m.event(1, "path", "CALC_STARTED");
        m.event(3, "path", "CALC_FINISHED_NOW_EXECUTING");
        m.event(40, "path", "NEXT_CALC_FAILED");
        m.event(41, "path", "CALC_FAILED");
        assertEquals(2, m.failures());
        assertEquals(0, m.stoppedSeconds(), 1e-9, "a stop still open at the end is not counted");
    }

    @Test
    void nothingRanAndNoDebugLines() {
        TripMeter m = new TripMeter(0);
        m.event(1, "job", "something else");
        m.event(1, "log", "No path found =(");
        m.event(1, "path", null);
        assertEquals(-1, m.firstStepSeconds(), 1e-9);
        assertEquals(0, m.debugLines());
        m.markLost();
        assertTrue(m.lost());
    }
}
