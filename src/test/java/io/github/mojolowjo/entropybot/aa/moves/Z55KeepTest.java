package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z55 item 1 (run N 21:23:23-21:24:42; the same on 2026-10-23): "collect 8 cobblestone", then "craft furnace" walked to
 * the table 48 m away and placed cobblestone at -21 55..59 -11 to climb out of its hole ("rules: travel may place"),
 * then failed "none of cobblestone, blackstone, cobbled_deepslate left in the bag (ingredients ran out (cobblestone))",
 * re-planned "collect cobblestone 3", and failed again. The furnace's recipe takes one slot
 * {@code #minecraft:stone_crafting_materials} (the game's tag); StepNeeds.recipeInputs skipped tag slots, so the keep list
 * the job gave Baritone's run lease was empty. No game: the real StepNeeds of the steps, the step machine on fake hands
 * whose run lease is a hand-made fake (Baritone's throwaway list minus the kept items while a run holds).
 */
class Z55KeepTest {
    static final String COBBLE = "minecraft:cobblestone", BLACK = "minecraft:blackstone", DEEP = "minecraft:cobbled_deepslate";
    static final String TAG = "#minecraft:stone_crafting_materials";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    /** The game's furnace recipe: 8 of the stone crafting tag (cobblestone, blackstone, cobbled_deepslate). */
    static TestData game() {
        TestData d = TestData.vanilla();
        d.tag("cobblestone", "minecraft:stone_crafting_materials").tag("blackstone", "minecraft:stone_crafting_materials")
                .tag("cobbled_deepslate", "minecraft:stone_crafting_materials");
        String[] in = new String[8];
        java.util.Arrays.fill(in, TAG);
        d.recipe("furnace", 1, in);
        return d.install();
    }

    /** Baritone's throwaway list as the fork's lease makes it: the recorded list without the kept items while a run holds. */
    static final class FakeLease {
        final List<String> recorded = List.of("minecraft:dirt", COBBLE, "minecraft:netherrack", "minecraft:stone");
        List<String> kept = List.of();
        boolean held;

        List<String> throwaway() {
            if (!held) return recorded;
            List<String> out = new ArrayList<>(recorded);
            out.removeAll(kept);
            return out;
        }
    }

    @Test
    void runNTheFurnacesTagSlotPutsEveryMemberInTheKeepList() {
        game();
        StepNeeds n = StepNeeds.of(new Step.Craft("minecraft:furnace", 1));
        assertEquals(Map.of(), n.consumes(), "a tag slot has no count of its own (the job's own check stays)");
        assertTrue(n.keeps().containsAll(List.of(COBBLE, BLACK, DEEP)), "every member of the slot: " + n.keeps());
        assertTrue(StepNeeds.keepOf(List.of(StepNeeds.of(new Step.Collect(COBBLE, 8)), n)).contains(COBBLE));
    }

    @Test
    void severalRecipesKeepEveryInputOfEach() {
        TestData d = TestData.vanilla();
        d.recipe("z55_slab", 1, "stone").recipe("z55_slab", 1, "cobblestone");
        d.install();
        StepNeeds n = StepNeeds.of(new Step.Craft("minecraft:z55_slab", 1));
        assertEquals(List.of("minecraft:stone", COBBLE), n.keeps());
        assertTrue(n.none(), "kept items are not a bag need");
    }

    @Test
    void noRecipeDataIsSaidNotReadAsNoInputs() {
        TestData.clear();
        StepNeeds n = StepNeeds.of(new Step.Craft("minecraft:furnace", 1));
        assertTrue(n.text().contains("not loaded: no recipe data"), n.text());
        assertEquals(List.of(), StepNeeds.keepOf(List.of(n)));
    }

    /**
     * The run N chain on fake hands: collect 8 cobblestone (Baritone's mine run holds the lease), craft furnace (its walk to
     * the table holds the walk lease; cobblestone must not be on the throwaway list while it walks), the craft fails on the
     * bag and re-plans "collect 3 cobblestone, craft furnace": cobblestone stays kept through the re-plan, and the run's end
     * keeps nothing.
     */
    @Test
    void runNCobblestoneIsKeptThroughTheCollectTheWalkAndTheReplan() {
        game();
        FakeLease lease = new FakeLease();
        Step collect8 = new Step.Collect(COBBLE, 8), craft = new Step.Craft("minecraft:furnace", 1), collect3 = new Step.Collect(COBBLE, 3);
        List<String> walkThrowaway = new ArrayList<>(), log = new ArrayList<>();
        int[] craftTries = {0};
        Step[] running = {null};
        Steps.Hands hands = new Steps.Hands() {
            @Override public String start(Step s) { running[0] = s; lease.held = true; if (s instanceof Step.Craft) craftTries[0]++; return null; }

            @Override public Car.Status status(Step s) {
                // every tick of a step a Baritone run holds the lease: what it may place is the lease's list now
                log.add(s.getClass().getSimpleName() + " may place " + lease.throwaway());
                if (s instanceof Step.Collect) return Car.Status.done("have the cobblestone");
                walkThrowaway.addAll(lease.throwaway());          // the craft's walk to the table, 48 m, with a climb
                return craftTries[0] == 1 ? Car.Status.failed("could not craft furnace - ingredients ran out") : Car.Status.done("made 1 furnace");
            }

            @Override public void cancel(String why) { lease.held = false; }

            @Override public FactChange.Fact lastFact() {
                return running[0] instanceof Step.Craft && craftTries[0] == 1
                        ? new FactChange.Fact("bag cobblestone", "none of cobblestone, blackstone, cobbled_deepslate left in the bag") : null;
            }

            @Override public void keep(List<String> items) { lease.kept = items; }
        };
        Steps run = new Steps(hands);
        Steps.Replanner replan = (failed, fact, why) -> new Steps.Routine(List.of(collect3, craft), null);
        run.start("torch", List.of(collect8, craft), 0, replan);
        assertTrue(lease.kept.contains(COBBLE), "the collect runs with the furnace's cobblestone kept: " + lease.kept);
        for (int t = 1; t < 12 && run.phase() == Steps.Phase.RUNNING; t++) run.tick(t);
        assertEquals(Steps.Phase.DONE, run.phase(), String.join(" / ", log));
        assertEquals(2, craftTries[0], "the craft re-planned once and ran again");
        assertFalse(walkThrowaway.isEmpty());
        assertFalse(walkThrowaway.contains(COBBLE), "no walk of the job may place cobblestone: " + String.join(" / ", log));
        assertTrue(walkThrowaway.contains("minecraft:dirt"), "the walk keeps Baritone's other throwaway blocks");
        for (String line : log) assertFalse(line.contains(COBBLE), line);
        assertEquals(List.of(), lease.kept, "the run's end keeps nothing");
    }
}
