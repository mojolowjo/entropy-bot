package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.sense.CaveMouths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z49 scenarios (docs/SCENARIOS.md): S67 the explore while paths compute is not a way (no price, no margin), S68 a
 * smelt step uses the known furnace at any distance; the cave candidate numbers (item 3).
 */
class Z49ScenarioTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    // ---- S67 ----

    @Test
    void s67TheComputingExploreIsNotAWay() {
        // run K: "the new way 136 s vs 104 s left" - the running pick was the explore while paths compute: no price, replaced
        assertTrue(Goals.switches(false, Double.NaN, false, true, 136, true, 10), "the tree way replaces the computing explore");
        assertTrue(Goals.switches(false, 104, true, true, 136, false, 10), "whatever its old number, no margin against it");
        assertFalse(Goals.switches(true, 100, false, true, 98, false, 10), "two real ways, 2 s cheaper: no switch");
        assertTrue(Goals.switches(true, 100, false, true, 70, false, 10), "two real ways, 30 s cheaper: switch");
        assertFalse(Goals.switches(true, 100, false, false, Double.NaN, false, 10), "a new pick waiting on paths never replaces a way");
    }

    @Test
    void s67TheTreeRunsWithinASecondOfItsPath() {
        Z48ScenarioTest.Hands h = new Z48ScenarioTest.Hands();
        Steps run = new Steps(h);
        Step ex = new Step.Explore(5, null, true), craft = new Step.Craft("minecraft:torch", 4);
        Step tree = new Step.Craft("minecraft:oak_planks", 4);
        run.start("goal.torch", List.of(ex, craft), 0, (failed, fact, why) -> new Steps.Routine(List.of(tree, craft), null));
        run.repriceAtNextStep(FactChange.priced("walk to -28 70 -51: 14 s, complete"));
        run.tick(1);
        assertEquals(1, h.cancels, "the explore ends at the next tick");
        assertEquals(tree.text(), h.started.get(h.started.size() - 1));
    }

    // ---- S68: 3 raw_iron, the bot's own furnace 26 m ----

    private static World ironWorld(Map<String, Integer> bag, Spot furnace) {
        ScenariosTest.world("wooden", "stone");
        Map<String, List<Spot>> ids = new HashMap<>();
        return FakePaths.paths(new World(bag, true, furnace != null, w -> ScenariosTest.lookup(ids, w), null, null, null, 0, null,
                k -> ItemWorth.SMELT.equals(k) ? furnace : null));
    }

    @Test
    void s68TheKnownFurnace26mIsWalkedToAndUsed() {
        Map<String, Integer> bag = Map.of("minecraft:raw_iron", 3, "minecraft:cobblestone", 5, "minecraft:coal", 2, "minecraft:stone_pickaxe", 1);
        Chain c = NeedChain.resolve("iron_ingot", 3, ironWorld(bag, new Spot(-242, 83, 203, 26, "minecraft:furnace")));
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Smelt), c.steps().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.PlaceNear || s instanceof Step.Collect), "no new furnace: " + c.steps());
    }

    @Test
    void s68NoFurnaceKnownAnd8CobblestonePlacesOne() {
        Map<String, Integer> bag = Map.of("minecraft:raw_iron", 3, "minecraft:cobblestone", 8, "minecraft:coal", 2, "minecraft:stone_pickaxe", 1);
        Chain c = NeedChain.resolve("iron_ingot", 3, ironWorld(bag, null));
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.PlaceNear), c.steps().toString());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Smelt), c.steps().toString());
    }

    // ---- item 3: the candidate's numbers ----

    @Test
    void candidateLinesCarryTheShapeNumbers() {
        // x 0..8 slopes down 1 a column to x 8 at 70; x 9.. jumps to 76 (the cliff)
        int[] top = new int[256];
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) top[lx + 16 * lz] = lx <= 8 ? 70 + (8 - lx) : 76;
        List<CaveMouths.Candidate> l = CaveMouths.candidates(0, 0, top);
        assertFalse(l.isEmpty());
        String w = CaveMouths.where(l.get(0));
        assertTrue(w.contains("mouth: slope -x ") && w.contains(" cols steps ") && w.contains("cliff +x +6"), w);
        assertEquals("depth 0 to its lowest air, sky 15 there / 15 one above, water no", CaveMouths.holeNumbers(0, 15, 15, false));
        assertEquals("cliffs +z +6 / -z +5", CaveMouths.shapeNumbers("ravine", "+z", 6, "-z", 5, null));
    }
}
