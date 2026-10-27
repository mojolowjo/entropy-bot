package io.github.mojolowjo.entropybot.aa.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D10: the nearby cache's renewals per client tick (run DS: 101 sets renewed in one tick, 80 ms). */
class RenewBudgetTest {
    @Test void aBurstIsSpreadOverTicks() {
        RenewBudget b = new RenewBudget();
        int now = 0, later = 0;
        for (int i = 0; i < 20; i++) if (b.allow(100, true)) now++;
        assertEquals(RenewBudget.PER_TICK, now, "this tick: the budget");
        assertEquals(20 - RenewBudget.PER_TICK, b.deferred(), "the rest wait");
        for (int i = 0; i < 20 - RenewBudget.PER_TICK; i++) if (b.allow(101, true)) later++;
        assertEquals(RenewBudget.PER_TICK, later, "the next tick: the budget again");
        assertEquals(4, b.deferred() - (20 - RenewBudget.PER_TICK), "4 wait for a third tick");
        assertEquals(2 * RenewBudget.PER_TICK, b.renewed());
    }

    @Test void aSetWithNoAnswerIsNeverDeferred() {
        RenewBudget b = new RenewBudget();
        for (int i = 0; i < RenewBudget.PER_TICK; i++) assertTrue(b.allow(7, true));
        assertFalse(b.allow(7, true), "the budget is spent");
        assertTrue(b.allow(7, false), "no answer to keep: scanned anyway (rule 15)");
        assertTrue(b.allow(7, false));
        assertEquals(1, b.deferred());
        assertEquals(RenewBudget.PER_TICK + 2, b.renewed());
    }

    @Test void aNewTickResetsTheCount() {
        RenewBudget b = new RenewBudget();
        for (int i = 0; i < RenewBudget.PER_TICK; i++) b.allow(1, true);
        assertEquals(RenewBudget.PER_TICK, b.usedThisTick());
        assertFalse(b.allow(1, true));
        assertTrue(b.allow(2, true));
        assertEquals(1, b.usedThisTick());
    }
}
