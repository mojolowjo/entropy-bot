package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** job-start: a running walk's price change under pathCost.repriceMarginS / Frac does not drop a planner result. */
class StartRepriceTest {
    @BeforeEach void reset() { Tuning.reset(); }

    @Test void computingWordsDoNotDrop() {
        assertTrue(PlannerPort.samePrice(PathPrice.Answer.computing(null), PathPrice.Answer.computing("queued: pathCost.maxPending 4 searches in flight")));
    }

    @Test void aPricedWalkIsComparedByValue() {
        var a = PathPrice.Answer.priced(new PathPrice.Walk(30));
        assertTrue(PlannerPort.samePrice(a, PathPrice.Answer.priced(new PathPrice.Walk(30))));
        assertFalse(PlannerPort.samePrice(a, PathPrice.Answer.priced(new PathPrice.Walk(31.5))));    // D6: never a stale price
    }

    @Test void kindOrDigsChangeDrops() {
        assertFalse(PlannerPort.samePrice(PathPrice.Answer.computing(null), PathPrice.Answer.priced(new PathPrice.Walk(30))));
        assertFalse(PlannerPort.samePrice(PathPrice.Answer.priced(new PathPrice.Walk(30, 0, 1)), PathPrice.Answer.priced(new PathPrice.Walk(30, 0, 2))));
    }
}
