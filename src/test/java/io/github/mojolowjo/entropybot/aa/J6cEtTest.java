package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepSignals;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6c run ET (docs/runs/runET-z78j6p; to-do/j6c-cave-yield-from-gen.md "## Run ET").
 * (C) 09:45:35-37: the explore's no-break walk from -80 99 -233 to the cave entry -77 94 -236 failed three times with the
 * fork's "calc FAILURE, stop CHUNKS, nodes 28785" - neither exhausted nor a budget stop - and the step monitor's own count
 * of CALC_FAILED events froze the explore ("Baritone found no path 3 times from -80 99 -233"). Rule 23 (2), one reader
 * with item 14: the count is a no-path only when the fork's last calc exhausted the graph.
 */
class J6cEtTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final int[] ET = {-80, 99, -233};

    /** The ET signals: goal 156.7 never nearer, the feet still, a failed search every second; {@code exhausted} the fork's flag. */
    static StepSignals et(int s, Boolean exhausted) {
        return new StepSignals(true, null, ET, 156.7, "GoalNear{x=-77, y=94, z=-236, rangeSq=4}", false, false, false, s, -1, -1, null, 0, -1, null, exhausted);
    }

    static Z71StepMonitorTest.Planner run(Boolean exhausted, int to) {
        Z71StepMonitorTest.Hands h = new Z71StepMonitorTest.Hands();
        h.script = s -> et(s, exhausted);
        Z71StepMonitorTest.Planner p = new Z71StepMonitorTest.Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron_pickaxe", List.of(new Step.Walk(-77, 94, -236, 2)), 0, p);
        Z71StepMonitorTest.run(run, h, p, to);
        return p;
    }

    @Test
    void etsChunksStops_doNotFreeze_onlyAnExhaustedSearchIsANoPath() {
        var chunks = run(false, 20);
        assertTrue(chunks.facts.isEmpty(), "ET's CHUNKS stops, 20 failed searches: not known, no freeze - " + chunks.facts);
        var unkept = run(null, 20);
        assertTrue(unkept.facts.isEmpty(), "no calc kept: not known either");
        var bounded = run(false, 60);
        assertFalse(bounded.facts.isEmpty(), "the stall rule still bounds the step");
        assertFalse(bounded.facts.get(0).text().contains("found no path"), "never a no-path verdict: " + bounded.facts.get(0).text());
        var exhausted = run(true, 20);
        assertFalse(exhausted.facts.isEmpty(), "an exhausted search: the no-path verdict as before");
        assertTrue(exhausted.facts.get(0).text().contains("Baritone found no path"), exhausted.facts.get(0).text());
    }
}
