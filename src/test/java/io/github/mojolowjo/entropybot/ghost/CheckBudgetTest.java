package io.github.mojolowjo.entropybot.ghost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckBudgetTest {
    @Test
    void stepsAndMillisecondsBothCap() {
        CheckBudget b = new CheckBudget(200, 2.0, 40);
        assertTrue(b.mayStep(0, 0));
        assertTrue(b.mayStep(199, 1.99));
        assertFalse(b.mayStep(200, 0.1), "the step cap");
        assertFalse(b.mayStep(10, 2.0), "the time cap");
    }

    @Test
    void lateAfterTheDeadline() {
        CheckBudget b = new CheckBudget(200, 2.0, 40);
        assertFalse(b.late(40));
        assertTrue(b.late(41));
    }
}
