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

    /** Run HR (the walk probe): the top break refusal reasons of the A* cost site, biggest first, the site prefix off; other sites left out. */
    @Test
    void topDeniesOfOneSite() {
        PolicyStats s = new PolicyStats();
        for (int i = 0; i < 3; i++) s.deny(PolicyStats.SITE_ASTAR_COST, "travel break cap 24 reached on this walk");
        s.deny(PolicyStats.SITE_ASTAR_COST, "a falling block above");
        s.deny(PolicyStats.SITE_ASTAR_PLACE, "no place here");
        s.deny(PolicyStats.SITE_CLICK, "built block");
        assertEquals(java.util.List.of("travel break cap 24 reached on this walk 3", "a falling block above 1"), s.topDenies(PolicyStats.SITE_ASTAR_COST, 4));
        assertEquals(java.util.List.of("travel break cap 24 reached on this walk 3"), s.topDenies(PolicyStats.SITE_ASTAR_COST, 1));
        assertTrue(new PolicyStats().topDenies(PolicyStats.SITE_ASTAR_COST, 4).isEmpty());
        String line = io.github.mojolowjo.entropybot.commands.ProbeLines.unreachable(new int[]{11, 35, 9}, new int[]{38, 35, 9}, "no progress", "stop TIME", true, 9324, 6316,
                s.topDenies(PolicyStats.SITE_ASTAR_COST, 4));
        assertTrue(line.endsWith("break 9324, place 6316 | break refusals by reason since start: travel break cap 24 reached on this walk 3; a falling block above 1"), line);
        assertTrue(io.github.mojolowjo.entropybot.commands.ProbeLines.unreachable(new int[]{0, 0, 0}, new int[]{1, 1, 1}, "r", null, true, 0, 0).endsWith("break 0, place 0"), "the 7-arg form says nothing of reasons");
    }
}