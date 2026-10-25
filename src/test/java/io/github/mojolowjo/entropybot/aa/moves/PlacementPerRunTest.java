package io.github.mojolowjo.entropybot.aa.moves;

import baritone.api.process.Reserves;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z38 "placement per run": the run's reserves from the remaining steps' needs, and the walk's placement price. 0.39.z61
 * (D1 J2): the keep list became counted reserves ({@link Reservations}); these tests were rewritten to counts.
 */
class PlacementPerRunTest {

    // ---- the run's reserves ----

    static List<String> words(List<Reserves.Reserve<String>> rs) {
        List<String> out = new ArrayList<>();
        for (var r : rs) out.add(Reservations.text(r));
        return out;
    }

    @Test void theReservesAreWhatEachRemainingStepUsesUpWithItsCount() {
        StepNeeds craft = new StepNeeds(null, 0, Map.of("minecraft:cobblestone", 8), null);
        StepNeeds place = new StepNeeds(null, 0, Map.of("minecraft:furnace", 1), null);
        StepNeeds mine = new StepNeeds(StepNeeds.TIER_TOOL, 1, Map.of(), null);
        List<Step> steps = List.of(new Step.Walk(0, 64, 0, 0), new Step.Craft("furnace", 1), new Step.Mine("iron", 3, Step.Mine.How.KNOWN),
                new Step.PlaceNear("furnace"), new Step.Craft("furnace", 1));
        Reservations r = Reservations.build(steps, 0, List.of(StepNeeds.NONE, craft, mine, place, craft), List.of());
        assertEquals(List.of("cobblestone 8 (craft furnace 1)", "furnace 1 (" + steps.get(3).text() + ")", "cobblestone 8 (craft furnace 1)"),
                words(r.list()));
        assertEquals(List.of(), Reservations.build(steps.subList(2, 3), 0, List.of(mine), List.of()).list(), "a tool is a role and tier, no item: nothing to reserve");
        assertEquals(3, r.chainCount());
    }

    /** A job whose next craft needs 8 cobblestone reserves 8 during its walk and lets go at the end. */
    @Test void aRunReservesItsNextCraftsIngredientsDuringItsWalksAndLetsGoAtTheEnd() {
        List<List<Reserves.Reserve<String>>> pushed = new ArrayList<>();
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
            @Override public void reserve(List<Reserves.Reserve<String>> r) { pushed.add(r); }
        };
        Steps run = new Steps(hands);
        run.start("furnace", List.of(walk, craft), 0);
        assertEquals(List.of("cobblestone 8 (craft furnace 1)"), words(pushed.get(pushed.size() - 1)), "the walk runs with the craft's 8 reserved");
        st[0] = Car.Status.done("arrived");
        run.tick(1);
        assertEquals(List.of("cobblestone 8 (craft furnace 1)"), words(pushed.get(pushed.size() - 1)), "the craft itself reserves them too");
        run.tick(2);
        assertEquals(Steps.Phase.DONE, run.phase());
        assertEquals(List.of(), pushed.get(pushed.size() - 1), "the run's end reserves nothing");
    }

    @Test void aFailedOrCancelledRunReservesNothing() {
        List<List<Reserves.Reserve<String>>> pushed = new ArrayList<>();
        Step craft = new Step.Craft("furnace", 1);
        Steps.Hands hands = new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) { return Car.Status.failed("ingredients ran out"); }
            @Override public void cancel(String why) {}
            @Override public FactChange.Fact lastFact() { return null; }
            @Override public StepNeeds needs(Step s) { return new StepNeeds(null, 0, Map.of("minecraft:cobblestone", 8), null); }
            @Override public void reserve(List<Reserves.Reserve<String>> r) { pushed.add(r); }
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
