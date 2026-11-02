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
        assertTrue(src.contains("private static final int SITE_AFTER_TICKS = 100, SITE_EVERY_TICKS = 200, SITE_STANDS = 4, SITE_DROP_MAX = 8;"));
    }

    /**
     * Run HB site1 (8 held walks of 30 s each): the fork's failed search is a "no way" when the graph was exhausted (run ET), or
     * when a budget stop left its best node at the start feet; a budget stop whose best moved, a CHUNKS stop, or no calc kept
     * is not known.
     */
    @Test
    void noWay_isAnExhaustedGraph_orABudgetStopWhoseBestStayedAtTheFeet() {
        // (exhausted, budget stop, best node = the start feet) as StepSignalsLive.noWay(IBaritone) reads them off the fork's WalkCalc
        assertTrue(io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.noWay(false, true, true), "HB 21:35:49: stop TIME, best = the feet");
        assertFalse(io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.noWay(false, true, false), "the best moved: not known (a far goal)");
        assertFalse(io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.noWay(false, false, true), "run ET: a CHUNKS stop is not known, best at the feet or not");
        assertTrue(io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.noWay(true, false, false), "the graph exhausted: no path, as before");
        assertFalse(io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.noWay(false, false, false), "GZ: the calc not this walk's (unknown stop, no best) - not known");
        String src;
        try { src = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepSignalsLive.java")); } catch (IOException e) { throw new AssertionError(e); }
        assertTrue(src.contains("return c == null ? null : io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.noWay(c.exhausted(), c.budget(), c.best() != null && c.best().equals(c.startFeet()));"), "no calc kept: null (not known); else the three facts off the WalkCalc");
    }

    /** The three holds (the explore target, the lip, the caving walk) read the one no-way fact; the stand test asks Baritone's own rule. */
    @Test
    void theHoldsReadNoWay_andTheStandTestAsksBaritone() throws IOException {
        String hands = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        String mining = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/Mining.java"));
        assertEquals(2, count(hands, "StepSignalsLive.noWay(b)"), "the lip walk and the caving walk");
        assertEquals(1, count(mining, "StepSignalsLive.noWay(b)"), "the explore target");
        assertFalse(hands.contains("StepSignalsLive.exhausted(b)") || mining.contains("StepSignalsLive.exhausted(b)"), "no hold reads the narrower flag");
        String lip = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/LipGoals.java"));
        int from = lip.indexOf("static boolean standable("), to = lip.indexOf("private static final org.slf4j.Logger LOG");
        assertTrue(from > 0 && to > from);
        String stand = lip.substring(from, to);
        assertTrue(stand.contains("MovementHelper.canWalkOn(ctx, u)"), "the block under: Baritone's canWalkOn (pointed dripstone: never)");
        assertEquals(2, count(stand, "MovementHelper.canWalkThrough(ctx, new baritone.api.utils.BetterBlockPos("), "the feet and the head: Baritone's canWalkThrough");
        assertTrue(stand.contains("LOG.warn("), "an older fork: said, not silent");
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }
}
