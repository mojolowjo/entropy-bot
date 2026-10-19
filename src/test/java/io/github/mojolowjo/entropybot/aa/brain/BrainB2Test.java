package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** BRAIN_PLAN B2: curves, P(success), measured costs, the recipe tech tree. */
class BrainB2Test {
    @Test
    void foodCurveIsFlatThenSteep() {
        assertEquals(0, Curves.upkeep(125, 18, 18), 0.01);
        assertEquals(24.7, Curves.upkeep(125, 10, 18), 0.5);
        assertEquals(75.6, Curves.upkeep(125, 4, 18), 0.5);
        assertEquals(100, Curves.upkeep(125, 0, 18), 0.01, "capped");
    }

    @Test
    void pSuccessHalvesAfterTwoFailuresAndUsesMeasuredRuns() {
        assertEquals(1, Curves.pSuccess(1, 0, 0));
        assertEquals(0.5, Curves.pSuccess(2, 0, 0));
        assertEquals(1, Curves.pSuccess(0, 2, 2), 1e-9, "2 runs: not measured yet");
        assertEquals((1 + 1.0) / 6, Curves.pSuccess(0, 4, 1), 1e-9);
        assertEquals(0.2, Curves.pSuccess(3, 3, 0), 1e-9, "measured only, not times the streak");
        assertEquals(30, Curves.progress(60, 1, 600), 1e-9, "cost C halves the value");
    }

    @Test
    void measuredCostReplacesTheHandValueAfterThreeRuns() {
        BrainStats s = new BrainStats();
        s.record("needs.iron", true, 400);
        s.record("needs.iron", false, 100);
        assertEquals(300, s.cost("needs.iron", 300), "2 runs: the hand value");
        s.record("needs.iron", true, 500);
        assertEquals(430, s.cost("needs.iron", 300), 1e-9, "400 + 0.3 x (500 - 400)");
        assertTrue(s.toJson().contains("\"needs.iron\":{\"n\":3,\"ok\":2,\"avgS\":430.0}"), s.toJson());
        assertTrue(s.takeDirty());
        assertFalse(s.takeDirty());
    }

    static TechTree vanillaBits() {
        return new TechTree(List.of(
                new TechTree.Recipe("iron_pickaxe", List.of(Set.of("iron_ingot"), Set.of("iron_ingot"), Set.of("iron_ingot"), Set.of("stick"), Set.of("stick"))),
                new TechTree.Recipe("bucket", List.of(Set.of("iron_ingot"))),
                new TechTree.Recipe("shield", List.of(Set.of("oak_planks", "spruce_planks"), Set.of("iron_ingot"))),
                new TechTree.Recipe("iron_ingot", List.of(Set.of("raw_iron"))),
                new TechTree.Recipe("stick", List.of(Set.of("oak_planks"))),
                new TechTree.Recipe("diamond_pickaxe", List.of(Set.of("diamond"), Set.of("stick"))),
                new TechTree.Recipe("oak_button", List.of(Set.of("oak_planks")))));
    }

    @Test
    void ironUnlocksMoreThanAButtonPlank() {
        TechTree t = vanillaBits();
        Set<String> have = Set.of("oak_planks", "stick");
        double iron = t.value("iron_ingot", have);
        assertTrue(iron > t.value("oak_planks", have), "iron " + iron);
        assertEquals(1.0, t.norm("iron_ingot", have), 1e-9, "iron is the best item here");
        assertEquals(0, t.value("iron_ingot", Set.of("iron_pickaxe", "bucket", "shield")), 1e-9, "nothing new left");
    }

    @Test
    void ideasAreMakeableNowBestFirst() {
        TechTree t = vanillaBits();
        List<TechTree.Idea> ideas = t.ideas(Set.of("oak_planks", "stick", "iron_ingot"), 5);
        assertTrue(ideas.subList(0, 3).stream().anyMatch(i -> i.item().equals("minecraft:iron_pickaxe")), ideas.toString());
        assertEquals("minecraft:oak_button", ideas.get(ideas.size() - 1).item(), "a button is last");
        assertTrue(ideas.stream().noneMatch(i -> i.item().equals("minecraft:diamond_pickaxe")), "no diamond yet");
        assertTrue(ideas.stream().noneMatch(i -> i.item().equals("minecraft:stick")), "had already");
    }
}
