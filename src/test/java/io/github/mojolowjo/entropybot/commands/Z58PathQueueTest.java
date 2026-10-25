package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z58 (run Q on z57, 02:28-02:35: "in flight 4" all run long, "queued +16", then each second "searches started +1,
 * queued +8, kept dropped +1"; the crafting walks "path not known yet (computing)" while the bot explored, so "collect 4
 * torch" - the only priced way - won at 02:29:32 and failed at 02:33:13). The queue and the re-search wait are the fork's
 * (PathMemory; its JUnit PathQueueTest plays run Q's pattern); here the mod's half: the re-search wait is "computing" (rule
 * 15), the walk told to the fork is the running step's or the next one's, the load line's per-class part, the Tuning entries.
 */
class Z58PathQueueTest {

    private static final String QUEUED = baritone.api.pathing.calc.IPricedPath.QUEUED;
    private static final String WAIT = baritone.api.pathing.calc.IPricedPath.RESEARCH_WAIT;

    @Test
    void theReSearchWaitIsComputingWithItsReasonNeverAPriceOrNone() {
        PathPrice.Answer a = PricedAnswers.notYet(WAIT, QUEUED, WAIT, 4);
        assertEquals(PathPrice.Kind.COMPUTING, a.kind());
        assertEquals("re-search waiting (the bot moved off the last path) (pathCost.researchMinS)", a.reason());
        assertNull(a.walk());
    }

    @Test
    void theRunningWalkIsTheCurrentOrNextWalkStep() {
        List<Step> steps = List.of(new Step.Craft("minecraft:crafting_table", 1), new Step.Walk(3, 64, -9, 0),
                new Step.ToStrip(10, 12, 10, "iron"));
        assertArrayEquals(new int[]{3, 64, -9}, Steps.walkAhead(steps, 0));    // crafting now: the walk it does next
        assertArrayEquals(new int[]{3, 64, -9}, Steps.walkAhead(steps, 1));    // walking now
        assertArrayEquals(new int[]{10, 12, 10}, Steps.walkAhead(steps, 2));
        assertNull(Steps.walkAhead(steps, -1));                                // nothing runs
        assertNull(Steps.walkAhead(List.of(new Step.Collect("minecraft:torch", 4)), 0));   // run Q's pick: no priced walk
    }

    @Test
    void theLoadLineSaysTheQueueByClass() {
        long[] d = new long[24];
        d[0] = 9;
        d[12] = 1; d[13] = 2; d[14] = 3; d[15] = 0;
        d[16] = 0; d[17] = 1; d[18] = 4; d[19] = 0;
        d[20] = 1; d[21] = 0; d[22] = 5; d[23] = 2;
        assertTrue(ProbeLines.asks(d).endsWith(" | by class running step/first answer/re-search/rest: started +1/+2/+3/+0,"
                + " queued +0/+1/+4/+0, cancelled for priority re-search +1 rest +0, re-search waits +5, left the queue +2"));
        assertFalse(ProbeLines.asks(new long[12]).contains("by class"));         // a z57 fork: no queue part
    }

    @Test
    void theQueueNumbersAreTuningEntries() {
        assertEquals(1, Tuning.get("pathCost.reserveForRunning"), 1e-9);
        assertEquals(3, Tuning.get("pathCost.researchMinS"), 1e-9);
        assertEquals(30, Tuning.get("pathCost.queueKeepS"), 1e-9);
        assertEquals(4, Tuning.get("pathCost.maxPending"), 1e-9);              // still the total
        assertTrue(String.join("\n", Tuning.lines("pathCost.researchMinS")).contains("run Q"));
    }
}
