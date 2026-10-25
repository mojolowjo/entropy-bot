package io.github.mojolowjo.entropybot.aa.moves;

import baritone.api.process.Reserves;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z55 item 1 (run N 21:23:23-21:24:42; the same on 2026-10-23): "collect 8 cobblestone", then "craft furnace" walked to
 * the table 48 m away and placed cobblestone at -21 55..59 -11 to climb out of its hole ("rules: travel may place"),
 * then failed "none of cobblestone, blackstone, cobbled_deepslate left in the bag (ingredients ran out (cobblestone))",
 * re-planned "collect cobblestone 3", and failed again. The furnace's recipe takes one slot
 * {@code #minecraft:stone_crafting_materials} (the game's tag); StepNeeds.recipeInputs skipped tag slots, so the keep list
 * the job gave Baritone's run lease was empty. 0.39.z61 (D1 J2): rewritten to counts - the tag slot is one counted need
 * (8 of the tag), and the lease places a stone member only while the bag holds more than the reserves need (the fork's
 * Reserves math; run O's good behaviour at 8, run N's walk may pillar what is over). No game: the real StepNeeds of the
 * steps, the step machine on fake hands whose lease is the fork's own pure math.
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

    @Test
    void runNTheFurnacesTagSlotIsOneCountedNeed() {
        game();
        StepNeeds n = StepNeeds.of(new Step.Craft("minecraft:furnace", 1));
        assertEquals(Map.of(), n.consumes(), "no plain item");
        assertEquals(Map.of(TAG, 8), n.tagUses(), "8 of the tag, not every member whole");
        Reservations r = Reservations.build(List.of(new Step.Craft("minecraft:furnace", 1)), 0, List.of(n), List.of());
        assertEquals(1, r.list().size());
        assertEquals(List.of(BLACK, DEEP, COBBLE).stream().sorted().toList(), r.list().get(0).members.stream().sorted().toList());
        assertEquals(8, r.list().get(0).units);
    }

    @Test
    void severalRecipesTakeTheFirstTheBagCoversElseTheFirstGuessed() {
        TestData d = TestData.vanilla();
        d.recipe("z55_slab", 1, "stone", "stick").recipe("z55_slab", 1, "cobblestone", "stick");
        d.install();
        StepNeeds guessed = StepNeeds.of(new Step.Craft("minecraft:z55_slab", 1));
        assertEquals(Map.of("minecraft:stone", 1, "minecraft:stick", 1), guessed.consumes());
        assertNotNull(guessed.guess(), "no bag: the first recipe, said");
        Map<String, Integer> bag = Map.of(COBBLE, 1, "minecraft:stick", 1);
        StepNeeds.Bag b = new StepNeeds.Bag() {
            @Override public int bestTier(String kind) { return 0; }
            @Override public String bestTool(String kind) { return null; }
            @Override public int usesLeft(String id) { return 0; }
            @Override public int count(String id) { return bag.getOrDefault(id, 0); }
        };
        StepNeeds chosen = StepNeeds.of(new Step.Craft("minecraft:z55_slab", 1), b);
        assertEquals(Map.of(COBBLE, 1, "minecraft:stick", 1), chosen.consumes(), "the recipe the bag covers");
        assertNull(chosen.guess());
    }

    @Test
    void noRecipeDataIsSaidNotReadAsNoInputs() {
        TestData.clear();
        StepNeeds n = StepNeeds.of(new Step.Craft("minecraft:furnace", 1));
        assertTrue(n.text().contains("not loaded: no recipe data"), n.text());
        assertNotNull(n.countsUnknown());
        Reservations r = Reservations.build(List.of(new Step.Craft("minecraft:furnace", 1)), 0, List.of(n), List.of());
        assertTrue(r.unknown().contains("no recipe data"), r.unknown());
        assertTrue(r.unknown().contains("kept whole: nothing it names"), r.unknown());
    }

    /** Baritone's throwaway list and the fork's counted lease (Reserves.spare on the fake bag). */
    static final class FakeLease {
        final List<String> recorded = List.of("minecraft:dirt", COBBLE, "minecraft:netherrack", "minecraft:stone");
        List<Reserves.Reserve<String>> reserves = List.of();
        final Map<String, Integer> bag = new HashMap<>();

        List<String> mayPlace() {
            List<String> out = new ArrayList<>();
            for (String i : recorded) if (Reserves.spare(reserves, bag, i) >= 1) out.add(i);
            return out;
        }
    }

    /**
     * The run N chain on fake hands: collect 8 cobblestone, craft furnace (its walk to the table: cobblestone must not be
     * placeable with exactly 8 in the bag), the craft fails on the bag and re-plans "collect 3 cobblestone, craft furnace";
     * the run's end reserves nothing.
     */
    @Test
    void runNCobblestoneIsReservedThroughTheCollectTheWalkAndTheReplan() {
        game();
        FakeLease lease = new FakeLease();
        lease.bag.put("minecraft:dirt", 5);
        Step collect8 = new Step.Collect(COBBLE, 8), craft = new Step.Craft("minecraft:furnace", 1), collect3 = new Step.Collect(COBBLE, 3);
        List<String> walkPlace = new ArrayList<>(), log = new ArrayList<>();
        int[] craftTries = {0};
        Step[] running = {null};
        Steps.Hands hands = new Steps.Hands() {
            @Override public String start(Step s) { running[0] = s; if (s instanceof Step.Craft) craftTries[0]++; return null; }

            @Override public Car.Status status(Step s) {
                log.add(s.getClass().getSimpleName() + " may place " + lease.mayPlace());
                if (s instanceof Step.Collect k) { lease.bag.merge(COBBLE, k.n(), Integer::sum); return Car.Status.done("have the cobblestone"); }
                walkPlace.addAll(lease.mayPlace());          // the craft's walk to the table, 48 m, with a climb
                return craftTries[0] == 1 ? Car.Status.failed("could not craft furnace - ingredients ran out") : Car.Status.done("made 1 furnace");
            }

            @Override public void cancel(String why) {}

            @Override public FactChange.Fact lastFact() {
                return running[0] instanceof Step.Craft && craftTries[0] == 1
                        ? new FactChange.Fact("bag cobblestone", "none of cobblestone, blackstone, cobbled_deepslate left in the bag") : null;
            }

            @Override public void reserve(List<Reserves.Reserve<String>> r) { lease.reserves = r; }
        };
        Steps run = new Steps(hands);
        Steps.Replanner replan = (failed, fact, why) -> {
            lease.bag.put(COBBLE, 5);                        // the walk's climb used 3 (the re-plan collects them again)
            return new Steps.Routine(List.of(collect3, craft), null);
        };
        run.start("torch", List.of(collect8, craft), 0, replan);
        assertEquals(8, lease.reserves.get(0).units, "the collect runs with the furnace's 8 reserved: " + lease.reserves);
        for (int t = 1; t < 12 && run.phase() == Steps.Phase.RUNNING; t++) run.tick(t);
        assertEquals(Steps.Phase.DONE, run.phase(), String.join(" / ", log));
        assertEquals(2, craftTries[0], "the craft re-planned once and ran again");
        assertFalse(walkPlace.isEmpty());
        assertFalse(walkPlace.contains(COBBLE), "8 in the bag, 8 reserved: no walk may place cobblestone: " + String.join(" / ", log));
        assertTrue(walkPlace.contains("minecraft:dirt"), "the walk keeps Baritone's other throwaway blocks");
        assertEquals(List.of(), lease.reserves, "the run's end reserves nothing");
    }
}
