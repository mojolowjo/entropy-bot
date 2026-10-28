package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.WorldFacts;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.commands.PricedAnswers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6c run EU (docs/runs/runEU-z78j6q; to-do/j6c-cave-yield-from-gen.md "## Run EU"), 10:39:58-10:40:37: the
 * pickaxe's chain was the 7 m cave's share with the rest waiting on two lip walks "chunks not loaded" (0 walks computing);
 * the bot stood 30 s, the walks dropped "no estimate", the full re-plan ended in an explore but the step machine got the
 * planner worker's "thinking" stand, counted it as the third stand at -92 79 -218 and stopped the order; the stand holds
 * went to all four armour orders as world facts.
 */
class J6cEuTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** (1a) A walk into chunks not loaded is ranked at once by Baritone's estimate: "estimate, chunks not loaded", never a price. */
    @Test
    void aChunksWalk_isRankedByTheEstimate_neverAPrice() {
        var chunks = PathPrice.Answer.chunks("chunks not loaded");
        var e = PricedAnswers.chunksEstimate(chunks, 42.5);
        assertNotNull(e);
        assertEquals(PathPrice.Kind.ESTIMATED, e.kind(), "ranked, not priced (rule 18 as amended)");
        assertEquals(42.5, e.walk().seconds(), 1e-9);
        assertEquals(PricedAnswers.CHUNKS_ESTIMATE, e.reason());
        assertTrue(e.reason().startsWith("estimate, chunks not loaded"));
        assertNull(PricedAnswers.chunksEstimate(chunks, Double.NaN), "no estimate: the wait as before");
        assertNull(PricedAnswers.chunksEstimate(PathPrice.Answer.computing("x"), 42.5), "only a CHUNKS answer");
        // the chain says it as an estimate, never as a price
        NeedChain.Walked wk = NeedChain.walked(e);
        assertTrue(wk.fail() == null && wk.kind() == PathPrice.Kind.ESTIMATED, String.valueOf(wk.fail()));
    }

    /** Hands whose path stands end with their price fact (StandWait's), the planner answering {@code next} in turn. */
    static Steps standing(List<List<Step>> next, List<String> asked) {
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) {
                if (s instanceof Step.AwaitPaths) { FactChange.repriceAfterStand(io.github.mojolowjo.entropybot.aa.moves.StandWait.overWords(30)); return Car.Status.failed("stood 30 s"); }
                return Car.Status.running("on it");
            }
            @Override public void cancel(String why) {}
            @Override public int[] feet() { return new int[]{-92, 79, -218}; }
            @Override public io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag bag() {
                return new io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag() {
                    @Override public int bestTier(String kind) { return 0; }
                    @Override public String bestTool(String kind) { return null; }
                    @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                    @Override public int count(String id) { return 0; }
                };
            }
        });
        int[] i = {0};
        run.start("goal.iron_pickaxe", List.of(new Step.AwaitPaths(30)), 0, (failed, fact, why) -> {
            asked.add(fact.key());
            List<Step> s = next.get(Math.min(i[0]++, next.size() - 1));
            return new Steps.Routine(s, null);
        });
        return run;
    }

    /** (1b) EU's order of events: two path stands, then the worker's "thinking" stand - never a third stand, no park. */
    @Test
    void thePlannersThinkingStand_isNeverCountedAsAStand() {
        List<String> asked = new ArrayList<>();
        Steps run = standing(List.of(List.of(new Step.AwaitPaths(30)), List.of(new Step.AwaitPaths(30, true)), List.of(new Step.Walk(-87, 83, -220, 2))), asked);
        for (int t = 1; t <= 6 && run.running(); t++) run.tick(t * 20L);
        assertTrue(run.running(), "no park on the thinking stand: " + run.why());
        assertTrue(asked.stream().noneMatch(k -> k.contains(NeedChain.STOOD_AT)), "no 'stood' drop: " + asked);
        // control: a third PATH stand at the same cell is still bounded (rule 22)
        List<String> asked2 = new ArrayList<>();
        Steps bounded = standing(List.of(List.of(new Step.AwaitPaths(30))), asked2);
        for (int t = 1; t <= 6 && bounded.running(); t++) bounded.tick(t * 20L);
        assertFalse(bounded.running());
        assertTrue(bounded.why().contains("no third stand there"), bounded.why());
    }

    /**
     * (2) EU 10:45:13: the helmet's smelt at the bot's OWN furnace 8 61 3 (buried: "no stand within reach: no standable
     * cell within 4.5 m of furnace at 8 61 3 sees a face of it"). An own station's use digs to it (the fork's digTo, rule
     * 16); a reached one ends done and leaves no fact; "no stand within reach" is never an own station's hold; a dig that
     * found no way is the next plan's station fact, with the fork's words (rule 7).
     */
    @Test
    void theHelmetsSmelt_atItsOwnBuriedFurnace_digsToIt() {
        int[] furnace = {8, 61, 3};
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.UseEnds.digTo(true), "own: the use digs to it");
        assertFalse(io.github.mojolowjo.entropybot.aa.moves.UseEnds.digTo(false), "not mine: the use as before");
        assertNull(io.github.mojolowjo.entropybot.aa.moves.UseEnds.fact(furnace, true, "dug to and opened furnace at 8 61 3"), "reached: the smelt runs, no fact");
        assertNull(io.github.mojolowjo.entropybot.aa.moves.UseEnds.fact(furnace, true, "no stand within reach: no standable cell within 4.5 m of furnace at 8 61 3 sees a face of it (from 8 62 3)"),
                "never held for 'no stand within reach'");
        FactChange.Fact f = io.github.mojolowjo.entropybot.aa.moves.UseEnds.fact(furnace, true, "no path digging to furnace at 8 61 3 (breaking off)");
        assertNotNull(f);
        assertEquals("source " + NeedChain.stationKey(8, 61, 3), f.key(), "the station's own fact (the one station store, StationHolds)");
        assertTrue(f.text().equals("my station at 8 61 3: no path digging to furnace at 8 61 3 (breaking off)"), f.text());
        assertNull(io.github.mojolowjo.entropybot.aa.moves.UseEnds.fact(furnace, false, "no path digging to furnace at 8 61 3 (breaking off)"), "not mine: never asked to dig");
        // job-vein e4b81987: a dig-to that stopped on its budget says "path not known (<stop>)" - never a fact or a hold (rule 15):
        // the step ends with the fork's words and the order is re-planned
        assertNull(io.github.mojolowjo.entropybot.aa.moves.UseEnds.fact(furnace, true, "path not known (TIME) digging to furnace at 8 61 3"), "a budget stop: no station fact");
        assertNull(io.github.mojolowjo.entropybot.aa.moves.UseEnds.fact(furnace, true, "path not known (NODES)"), "nor any other not-known end");
    }

    /** (1c) A stand's hold is the order's own: never a world fact; a real source fact still is. */
    @Test
    void standHolds_areNeverWorldFacts() {
        WorldFacts w = new WorldFacts();
        Inputs in = new Inputs();
        in.pos = new int[]{-92, 79, -218};
        in.bag = Map.of();
        assertFalse(w.learned(NeedChain.stoodKey(in.pos), "stood 2 times at -92 79 -218 ...", "iron_pickaxe", in));
        assertFalse(w.learned("step " + new Step.AwaitPaths(30).text(), "stuck 40 s at stand while paths compute (up to 30 s)", "iron_pickaxe", in));
        assertTrue(w.heldAt(in, null).isEmpty(), "no other order holds them: " + w.heldAt(in, null));
        assertTrue(w.learned(NeedChain.CLIMB_KEY, "the climb up failed", "iron_pickaxe", in), "a world outcome still is shared");
    }
}
