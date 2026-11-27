package io.github.mojolowjo.entropybot.ghost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickTimesTest {
    @Test
    void emptyIsNotLoadedNotZero() {
        TickTimes t = new TickTimes();
        assertTrue(Double.isNaN(t.avgMs()));
        assertTrue(Double.isNaN(t.worstMs()));
        assertEquals("no ticks timed", t.toString());
    }

    @Test
    void averageAndWorst() {
        TickTimes t = new TickTimes();
        t.add(1_000_000);
        t.add(3_000_000);
        t.add(2_000_000);
        assertEquals(3, t.count());
        assertEquals(2.0, t.avgMs(), 1e-9);
        assertEquals(3.0, t.worstMs(), 1e-9);
        assertEquals("3 ticks, avg 2.000 ms, worst 3.000 ms", t.toString());
        assertThrows(IllegalArgumentException.class, () -> t.add(-1));
    }
}
