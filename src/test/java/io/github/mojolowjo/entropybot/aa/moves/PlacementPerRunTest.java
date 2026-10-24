package io.github.mojolowjo.entropybot.aa.moves;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.39.z38 "placement per run": the keep list from the remaining steps' needs, and the walk's placement price. */
class PlacementPerRunTest {

    // ---- the throwaway keep list ----

    @Test void keepIsEveryItemTheRemainingStepsUseUpOnce() {
        StepNeeds craft = new StepNeeds(null, 0, Map.of("minecraft:cobblestone", 8), null);
        StepNeeds place = new StepNeeds(null, 0, Map.of("minecraft:furnace", 1), null);
        StepNeeds mine = new StepNeeds(StepNeeds.TIER_TOOL, 1, Map.of(), null);
        assertEquals(List.of("minecraft:cobblestone", "minecraft:furnace"), StepNeeds.keepOf(List.of(StepNeeds.NONE, craft, mine, place, craft)));
        assertEquals(List.of(), StepNeeds.keepOf(List.of(mine)), "a tool is a role and tier, no item: nothing to keep");
        assertEquals(List.of(), StepNeeds.keepOf(null));
    }

    /** A job whose next craft needs 8 cobblestone keeps cobblestone out of the throwaway list during its walk. */
    @Test void aRunKeepsItsNextCraftsIngredientsDuringItsWalksAndLetsGoAtTheEnd() {
        List<List<String>> pushed = new ArrayList<>();
        Step walk = new Step.Walk(4, 64, 4, 2);
        Step craft = new Step.Craft("furnace", 1);
        Car.Status[] st = {Car.Status.running("walking")};
        Steps.Hands hands = new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) { return st[0]; }
            @Override public void cancel(String why) {}
            @Override public FactChange.Fact lastFact() { return null; }
            @Override public StepNeeds needs(Step s) {
                return s == craft ? new StepNeeds(null, 0, Map.of("minecraft:cobblestone", 8), null) : StepNeeds.NONE;
            }
            @Override public void keep(List<String> items) { pushed.add(items); }
        };
        Steps run = new Steps(hands);
        run.start("furnace", List.of(walk, craft), 0);
        assertEquals(List.of("minecraft:cobblestone"), pushed.get(pushed.size() - 1), "the walk runs with the craft's cobblestone kept");
        st[0] = Car.Status.done("arrived");
        run.tick(1);
        assertEquals(List.of("minecraft:cobblestone"), pushed.get(pushed.size() - 1), "the craft itself keeps it too");
        run.tick(2);
        assertEquals(Steps.Phase.DONE, run.phase());
        assertEquals(List.of(), pushed.get(pushed.size() - 1), "the run's end keeps nothing");
    }

    @Test void aFailedOrCancelledRunKeepsNothing() {
        List<List<String>> pushed = new ArrayList<>();
        Step craft = new Step.Craft("furnace", 1);
        Steps.Hands hands = new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) { return Car.Status.failed("ingredients ran out"); }
            @Override public void cancel(String why) {}
            @Override public FactChange.Fact lastFact() { return null; }
            @Override public StepNeeds needs(Step s) { return new StepNeeds(null, 0, Map.of("minecraft:cobblestone", 8), null); }
            @Override public void keep(List<String> items) { pushed.add(items); }
        };
        Steps run = new Steps(hands);
        run.start("furnace", List.of(craft), 0);
        run.tick(1);
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertEquals(List.of(), pushed.get(pushed.size() - 1));
        run.start("furnace", List.of(craft), 2);
        run.cancel("owner stop");
        assertEquals(List.of(), pushed.get(pushed.size() - 1));
    }

    // ---- the walk's placement price (0.39.z39) ----

    @Test void aWalkPricesPlacingAtAboutThreeTimesBaritonesShippedPenalty() {
        double shipped = 20;     // Baritone Settings.blockPlacementPenalty as shipped
        assertEquals(3 * shipped, io.github.mojolowjo.entropybot.aa.brain.Tuning.get("walk.placePenalty"), 1e-9);
    }
}
