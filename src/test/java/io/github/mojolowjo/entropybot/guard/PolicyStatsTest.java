package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PolicyStatsTest {
    @Test
    void countsAsksDeniesAndErrors() {
        PolicyStats s = new PolicyStats();
        s.ask("click");
        s.ask("click");
        s.deny("click", "built block");
        s.error("boom");
        String sum = s.summary();
        assertTrue(sum.startsWith("asks 2 (click 2)"), sum);
        assertTrue(sum.contains("denies: click: built block 1"), sum);
        assertTrue(sum.endsWith("errors 1 (last: boom)"), sum);
    }

    @Test
    void nothingAskedSaysNone() {
        assertEquals("asks 0 | denies: none", new PolicyStats().summary());
    }
}