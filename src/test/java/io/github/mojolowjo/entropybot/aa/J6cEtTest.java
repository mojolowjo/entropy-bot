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

    /**
     * (B) ET 09:50:41: six known caves; five small ones were dropped "3 ore to find take ~N min, past the ~M min its unseen
     * part holds" (J6c's prior: they hold 0.1-1.3 iron). Rule 23 (1): a cave that holds less than the need gives its share
     * (round(rate x the minutes it holds)) and the rest is priced from the next way; one that holds ~0 is still out, said.
     * ET's numbers: the 7 m cave 0.45 / min over 1.5 min (1 ore), the 351 m one 0.64 / min over 1062 min, -103 76 -278
     * 0.19 / min over 0.4 min (0 ore).
     */
    @Test
    void etsSmallCaves_giveTheirShare_theRestFromTheNextWay() {
        var over = new NeedChain.Surface(11, -77, 98, -236, 3, false);
        NeedChain.CaveWay small = new NeedChain.CaveWay(-77, 94, -236, 7, "minecraft:iron_ore", "iron", 0.45, false, io.github.mojolowjo.entropybot.sense.CaveIndex.Size.SMALL, 1.5, List.of(), List.of(new int[]{-77, 94, -236}));
        NeedChain.CaveWay none = new NeedChain.CaveWay(-103, 76, -278, 232, "minecraft:iron_ore", "iron", 0.19, false, io.github.mojolowjo.entropybot.sense.CaveIndex.Size.SMALL, 0.4, List.of(), List.of(new int[]{-103, 76, -278}));
        NeedChain.CaveWay big = new NeedChain.CaveWay(102, 15, -384, 351, "minecraft:iron_ore", "iron", 0.64, false, io.github.mojolowjo.entropybot.sense.CaveIndex.Size.LARGE, 1062.3, List.of(), List.of(new int[]{102, 15, -384}));
        assertEquals(1, NeedChain.caveShare(small));
        assertEquals(0, NeedChain.caveShare(none));
        // ET's small caves alone (no large one): 1 from the 7 m cave, the rest from the next caves (1 each)
        NeedChain.CaveWay c241 = new NeedChain.CaveWay(-93, 72, -244, 241, "minecraft:iron_ore", "iron", 0.94, false, io.github.mojolowjo.entropybot.sense.CaveIndex.Size.SMALL, 1.4, List.of(), List.of(new int[]{-93, 72, -244}));
        NeedChain.CaveWay c368 = new NeedChain.CaveWay(-213, 74, -194, 368, "minecraft:iron_ore", "iron", 1.02, false, io.github.mojolowjo.entropybot.sense.CaveIndex.Size.SMALL, 1.1, List.of(), List.of(new int[]{-213, 74, -194}));
        NeedChain.CaveWay smalls = small.withMore(List.of(none, c241, c368));
        NeedChain.Chain c = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, java.util.Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? smalls : null));
        assertTrue(c.ok(), c.why());
        List<Step.Cave> legs = c.steps().stream().filter(s -> s instanceof Step.Cave).map(s -> (Step.Cave) s).toList();
        assertEquals(3, legs.size(), "three caving steps, one ore each: " + c.steps());
        assertEquals(-77, legs.get(0).x(), "the 7 m cave first: " + c.steps());
        assertTrue(legs.stream().allMatch(l -> l.n() == 1), c.steps().toString());
        assertTrue(c.why().contains("1 of 3 ore from the cave, the other 2 from: in the cave: known cave 241 m"), c.why());
        String all = c.why() + " | " + c.dropped();
        assertTrue(all.contains("known cave 232 m at -103 76 -278") && all.contains("past the ~0 min its unseen part holds"), "the cave holding ~0 is out, said: " + all);
        // with the large 351 m cave beside them: the 7 m share is priced (its rest from the 351 m), and loses on price only
        NeedChain.CaveWay withBig = small.withMore(List.of(none, big));
        NeedChain.Chain b = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, java.util.Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? withBig : null));
        assertTrue(b.ok(), b.why());
        assertTrue(b.dropped().stream().anyMatch(d -> d.startsWith("known cave 7 m: ") && d.contains("dearer than known cave 351 m")), "priced, never held short: " + b.dropped());
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
