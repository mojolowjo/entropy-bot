package io.github.mojolowjo.entropybot.aa.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** job-tick2: the scans past a tick's milliseconds run on the tick after. */
class ScanBudgetTest {
    @Test
    void scansPastTheBudgetAreDeferredToTheNextTick() {
        ScanBudget b = new ScanBudget();
        assertTrue(b.allow(1, 8));
        b.spend(1, 5_000_000);
        assertTrue(b.allow(1, 8));
        b.spend(1, 5_000_000);
        assertFalse(b.allow(1, 8), "10 ms spent of 8");
        assertFalse(b.allow(1, 8));
        assertEquals(2, b.deferredThisTick());
        assertTrue(b.allow(2, 8), "the next tick scans again");
        assertEquals(0, b.deferredThisTick());
        assertEquals(2, b.deferred());
    }

    @Test
    void oneScanAlwaysRuns() {
        ScanBudget b = new ScanBudget();
        b.spend(1, 50_000_000);
        assertTrue(b.allow(1, 8), "the first scan of a tick is never starved");
        assertFalse(b.allow(1, 8));
    }
}
