package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z51 item 4 (run L 16:38:25: "made 1 oak_boat" for a 3-iron smelt): a fuel option is priced by the wood it consumes
 * (logs / planks, from the recipe data) and its craft steps, against the game's burn times. A boat (5 planks, 1200 ticks)
 * loses to 2 planks (600 ticks) for 3 smelts by the formula alone - no boat case, no fuel names.
 */
class Z51FuelScenarioTest {
    @AfterEach
    void clear() { TestData.clear(); }

    static TestData boatWorld() {
        TestData d = ScenariosTest.world("wooden", "stone");
        d.recipe("oak_boat", 1, Collections.nCopies(5, "oak_planks").toArray(new String[0]));
        d.tableItems.add(TestData.id("oak_boat"));
        d.tag("oak_boat", "minecraft:boats").fuel("oak_boat", 1200);
        return d.install();
    }

    /** Loaded: a log in the bag, a furnace placed, 3 raw iron: planks are the burn, never a boat. */
    @Test
    void threeSmeltsBurnPlanksNotABoat() {
        boatWorld();
        Chain c = NeedChain.resolve("iron_ingot", 3, ScenariosTest.w(Map.of("minecraft:raw_iron", 3, "minecraft:oak_log", 2), true, true, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft k && k.item().contains("boat")), "no boat crafted: " + c.why());
    }

    /** Run L: no fuel in the bag, a tree seen 10 m (the burn is cut and crafted): planks, never a boat. */
    @Test
    void noFuelInTheBagTreeSeenBurnsPlanksNotABoat() {
        boatWorld();
        Chain c = NeedChain.resolve("iron_ingot", 3, ScenariosTest.w(Map.of("minecraft:raw_iron", 3), false, true,
                Map.of("oak_log", java.util.List.of(ScenariosTest.tree(10, 5)))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft k && k.item().contains("boat")), "no boat crafted: " + c.why());
        assertTrue(c.why().contains("burn "), c.why());
        assertTrue(c.dropped().stream().anyMatch(x -> x.startsWith("burn ") && x.contains("boat") && x.contains("uses 5.0 planks of wood vs 2.0")),
                "the boat is dropped by the wood it consumes, whatever its seconds: " + c.dropped());
        assertTrue(c.dropped().stream().noneMatch(x -> x.startsWith("burn #coals") && x.contains("planks of wood")), "coal needs no wood: " + c.dropped());
    }

    /** Planks in the bag already: they burn, never a boat crafted from them. */
    @Test
    void planksInTheBagBurnAsTheyAre() {
        boatWorld();
        Chain c = NeedChain.resolve("iron_ingot", 3, ScenariosTest.w(Map.of("minecraft:raw_iron", 3, "minecraft:oak_planks", 5), true, true, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft k && k.item().contains("boat")), "no boat crafted: " + c.why());
    }

    /**
     * Run L: a boat and planks both in the bag for 3 smelts: the smelt step burns the planks (2 planks of wood) over the boat
     * (5), whatever the items' worth says (here the boat is the cheaper by worth, as an item of unknown use can be).
     */
    @Test
    void theSmeltStepBurnsLessWood() {
        TestData d = boatWorld();
        var f = io.github.mojolowjo.entropybot.craft.CraftPlanner.pickFuel(Map.of("minecraft:oak_boat", 1, "minecraft:oak_planks", 2), 3, d.fuels, 200,
                id -> id.contains("boat") ? 0.1 : 1.0);
        assertEquals(new io.github.mojolowjo.entropybot.craft.CraftPlanner.Fuel("minecraft:oak_planks", 2), f);
        // not loaded (no fuel table): nothing burns, never a guess
        assertNull(io.github.mojolowjo.entropybot.craft.CraftPlanner.pickFuel(Map.of("minecraft:oak_planks", 2), 3, Map.of(), 200, id -> 1.0));
        // a non-wood fuel still stands on its worth against wood (coal 1 piece vs 2 planks)
        var g = io.github.mojolowjo.entropybot.craft.CraftPlanner.pickFuel(Map.of("minecraft:coal", 1, "minecraft:oak_planks", 2), 3, d.fuels, 200,
                id -> id.contains("coal") ? 5.0 : 1.0);
        assertEquals(new io.github.mojolowjo.entropybot.craft.CraftPlanner.Fuel("minecraft:oak_planks", 2), g);
    }

    /** The formula: wood per burn tick from the recipe data (a boat 5 planks / 1200, a plank 1 / 300, a log 4 planks / 300). */
    @Test
    void woodPerBurnFromTheRecipes() {
        boatWorld();
        assertEquals(5, io.github.mojolowjo.entropybot.craft.FuelWood.planks("minecraft:oak_boat"), 1e-9);
        assertEquals(1, io.github.mojolowjo.entropybot.craft.FuelWood.planks("minecraft:oak_planks"), 1e-9);
        assertEquals(4, io.github.mojolowjo.entropybot.craft.FuelWood.planks("minecraft:oak_log"), 1e-9);
        assertEquals(0.5, io.github.mojolowjo.entropybot.craft.FuelWood.planks("minecraft:stick"), 1e-9);
        assertEquals(0, io.github.mojolowjo.entropybot.craft.FuelWood.planks("minecraft:coal"), 1e-9, "not made of wood: none consumed");
        // 3 smelts x 200 ticks: 2 planks (2 planks of wood) vs 1 boat (5 planks of wood)
        assertTrue(io.github.mojolowjo.entropybot.craft.FuelWood.woodFor("minecraft:oak_planks", 3) < io.github.mojolowjo.entropybot.craft.FuelWood.woodFor("minecraft:oak_boat", 3));
    }

    /** z53 review 1: a cycle or a depth cut is unknown wood (+inf), never free; a wood-free recipe beside it still gives 0. */
    @Test
    void unknownWoodIsNotFree() {
        TestData d = boatWorld();
        d.recipe("loop_a", 1, "loop_b");
        d.recipe("loop_b", 1, "loop_a");
        d.recipe("deep_1", 1, "deep_2"); d.recipe("deep_2", 1, "deep_3"); d.recipe("deep_3", 1, "deep_4");
        d.recipe("deep_4", 1, "deep_5"); d.recipe("deep_5", 1, "deep_6"); d.recipe("deep_6", 1, "oak_planks");
        d.install();
        assertTrue(Double.isInfinite(io.github.mojolowjo.entropybot.craft.FuelWood.planks("minecraft:loop_a")), "a cycle is not known");
        assertTrue(Double.isInfinite(io.github.mojolowjo.entropybot.craft.FuelWood.planks("minecraft:deep_1")), "a depth cut is not known");
        assertEquals(0, io.github.mojolowjo.entropybot.craft.FuelWood.planks("minecraft:coal"), 1e-9);
    }
}
