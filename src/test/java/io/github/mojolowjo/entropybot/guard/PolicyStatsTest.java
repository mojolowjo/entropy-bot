package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PolicyStatsTest {
    @Test
    void countsAndRateLimitsTheDifferLines() {
        PolicyStats s = new PolicyStats();
        for (int i = 0; i < 5; i++) assertTrue(s.differ("click", "policy allows", 1000L * i), "the first 5 in full");
        assertFalse(s.differ("click", "policy allows", 4001), "then rate-limited");
        assertTrue(s.differ("astar-boxes", "policy denies: x", 4000 + PolicyStats.LOG_EVERY_MS), "one per 10 s");
        assertEquals(7, s.differs());
        s.ask("click");
        s.ask("click");
        s.deny("click", "built block");
        s.error("boom");
        String sum = s.summary();
        assertTrue(sum.startsWith("asks 2 (click 2)"), sum);
        assertTrue(sum.contains("denies: click: built block 1"), sum);
        assertTrue(sum.contains("would differ: 7 (click: policy allows 6; astar-boxes: policy denies: x 1)"), sum);
        assertTrue(sum.endsWith("errors 1 (last: boom)"), sum);
    }

    @Test
    void sameAnswerCountsNothing() {
        PolicyStats s = new PolicyStats();
        assertEquals(0, s.differs());
        assertEquals("asks 0 | denies: none | would differ: none", s.summary());
    }
}
