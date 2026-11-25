package io.github.mojolowjo.entropybot.craft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** job-review-leftovers (item 5): CraftPlanner.withFuel over several smelts, a storage plan's counts, and the fuel table not loaded. */
class WithFuelLeftoversTest {
    static final Map<String, Integer> FUELS = Map.of("minecraft:coal", 1600, "minecraft:oak_planks", 300, "minecraft:charcoal", 1600);

    @Test
    void twoSmeltsNeverCountTheSameFreeFuel() {
        // two smelts of 8 each, both planned on planks; the bag holds 1 coal: only the first gets it
        Crafter.Plan p = new Crafter.Plan(List.of(
                new Crafter.Smelt(null, "minecraft:iron_ingot", 8, "minecraft:raw_iron", 8, "minecraft:oak_planks", 6),
                new Crafter.Smelt(null, "minecraft:glass", 8, "minecraft:sand", 8, "minecraft:oak_planks", 6)), null);
        List<String> said = new ArrayList<>();
        Crafter.Plan out = CraftPlanner.withFuel(p, "coal", Map.of("minecraft:coal", 1, "minecraft:oak_planks", 12), FUELS, 200, said::add);
        assertEquals("minecraft:coal", ((Crafter.Smelt) out.steps().get(0)).fuel());
        assertEquals("minecraft:oak_planks", ((Crafter.Smelt) out.steps().get(1)).fuel(), "the one coal went to the first smelt");
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).contains("0 of 1"), said.get(0));
    }

    @Test
    void aSwapMovesTheStoragePlansCountsSoNoTripFetchesTheOldFuel() {
        Crafter.Plan p = new Crafter.Plan(List.of(new Crafter.Smelt(null, "minecraft:iron_ingot", 8, "minecraft:raw_iron", 8, "minecraft:oak_planks", 6)), null);
        Map<String, Integer> left = new HashMap<>(Map.of("minecraft:oak_planks", 0, "minecraft:coal", 2));
        CraftPlanner.withFuel(p, "coal", Map.of("minecraft:coal", 2), FUELS, 200, s -> {}, left);
        assertEquals(6, left.get("minecraft:oak_planks"), "the planks are no longer taken");
        assertEquals(1, left.get("minecraft:coal"));
    }

    /** review sent back again (8): a Craft step's inputs come off the running free bag (FurnacePlan.uses over the recipes). */
    @Test
    void aCraftsInputIsNotCountedAsFreeFuelForALaterSmelt() {
        // 2 oak planks -> sticks x4 (times 2 = 4 planks used), then a smelt of 8 on coal swapped to planks: the bag holds 6
        // planks, 4 go to the sticks, so 2 are free and the 6 needed for 8 smelts are not: the smelt keeps its coal
        RecipeData sticks = RecipeData.shapeless("minecraft:stick", "minecraft:stick", 4,
                List.of(List.of("minecraft:oak_planks"), List.of("minecraft:oak_planks")));
        Crafter.Plan p = new Crafter.Plan(List.of(
                new Crafter.Craft("minecraft:stick", "minecraft:stick", 8, 2, false),
                new Crafter.Smelt(null, "minecraft:iron_ingot", 8, "minecraft:raw_iron", 8, "minecraft:coal", 1)), null);
        List<String> said = new ArrayList<>();
        Map<String, Integer> bag = Map.of("minecraft:oak_planks", 6, "minecraft:coal", 1);
        Crafter.Plan out = CraftPlanner.withFuel(p, "oak_planks", bag, FUELS, 200, said::add, null, id -> id.equals("minecraft:stick") ? sticks : null);
        assertEquals("minecraft:coal", ((Crafter.Smelt) out.steps().get(1)).fuel(), "the sticks' planks are not free: " + said);
        assertTrue(said.get(0).contains("2 of 6") && said.get(0).contains("steps left out"), said.toString());
        // no recipes: the craft's planks are counted free (said so), and the swap goes through on 6
        List<String> said2 = new ArrayList<>();
        Crafter.Plan blind = CraftPlanner.withFuel(p, "oak_planks", bag, FUELS, 200, said2::add, null, null);
        assertEquals("minecraft:oak_planks", ((Crafter.Smelt) blind.steps().get(1)).fuel());
    }

    @Test
    void noFuelTableLoadedIsSaidSo() {
        Crafter.Plan p = new Crafter.Plan(List.of(new Crafter.Smelt(null, "minecraft:iron_ingot", 8, "minecraft:raw_iron", 8, "minecraft:oak_planks", 6)), null);
        List<String> said = new ArrayList<>();
        CraftPlanner.withFuel(p, "coal", Map.of("minecraft:coal", 2), Map.of(), 200, said::add);
        assertTrue(said.get(0).contains("no fuel table loaded"), said.toString());
    }
}
