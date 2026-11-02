package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.commands.ProbeLines;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 round 15 (runs GZ 21:01:39-21:02:06, HB 21:35:49-21:36:49): the feet still 30-60 s with a branch goal set,
 * Baritone's search "calc FAILURE, stop TIME, best = the feet" every 2 s, the step re-setting the goal each time, the 30 s
 * "no new best" rule ending it. The site probe says what surrounds the feet, Baritone's break setting and tool strengths,
 * and per goal part its stands and the blocks on the eye's line to it - once 5 s into a hold, then every 10 s, behind
 * debug.stepProbe (to-do/cave-walk-probe.md).
 */
class J6bStallSiteTest {
    /** The ray's block names as runs. */
    @Test
    void theLineIsSaidAsRuns() {
        assertEquals("", ProbeLines.runs(List.of()));
        assertEquals("air", ProbeLines.runs(List.of("air")));
        assertEquals("air x2, stone x3, lava, deepslate x2", ProbeLines.runs(List.of("air", "air", "stone", "stone", "stone", "lava", "deepslate", "deepslate")));
        assertEquals("stone, air, stone", ProbeLines.runs(List.of("stone", "air", "stone")), "runs, never a count of the whole");
    }

    /** The site lines fire from the hold's re-set only, 5 s into a hold, behind the step probe; the goto's set resets the hold's start. */
    @Test
    void theSiteProbeIsWiredIntoTheHold_behindTheProbe() throws IOException {
        String src = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        int from = src.indexOf("private Car.Status caveStatus("), to = src.indexOf("private Car.Status caveEnd(");
        assertTrue(from > 0 && to > from);
        String cave = src.substring(from, to);
        assertEquals(1, count(cave, "probe cave site:"), "one place says the site: the idle re-set");
        assertTrue(cave.contains("t - cv.holdSince >= SITE_AFTER_TICKS && (cv.siteAt < 0 || t - cv.siteAt >= SITE_EVERY_TICKS)"), "5 s into the hold, then every 10 s");
        assertTrue(cave.contains("cv.holdSince = cv.goalAt;") && cave.contains("cv.siteAt = -1;"), "a new goto starts the hold's clock");
        int probe = cave.indexOf("probe cave site:"), gate = cave.lastIndexOf("if (StepProbe.on()) {", probe);
        assertTrue(gate > 0 && probe - gate < 600, "the site lines sit inside the step probe's gate");
        assertTrue(src.contains("private static final int SITE_AFTER_TICKS = 100, SITE_EVERY_TICKS = 200, SITE_STANDS = 4;"));
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }
}
