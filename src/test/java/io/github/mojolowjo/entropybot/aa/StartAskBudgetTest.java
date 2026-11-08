package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** job-start: the ask budget by value - a way past pathCost.askFactor x the first asked way's estimate is ranked, not asked. */
class StartAskBudgetTest {
    @BeforeEach void reset() { Tuning.reset(); }

    @Test void pastTheFactorIsRanked() {
        assertFalse(NeedChain.pastFactor(19, 10));
        assertFalse(NeedChain.pastFactor(20, 10));
        assertTrue(NeedChain.pastFactor(21, 10));
    }

    @Test void notKnownNeverRanksOut() {
        assertFalse(NeedChain.pastFactor(Double.NaN, 10));
        assertFalse(NeedChain.pastFactor(500, Double.NaN));
    }

    @Test void aWayAtTheFeetCountsAsOneSecond() {
        assertFalse(NeedChain.pastFactor(2, 0));
        assertTrue(NeedChain.pastFactor(3, 0));
    }
}
