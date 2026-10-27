package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 fuel (run DH 04:38:11: "craft dark_oak_slab 114 > craft composter 16" for 24 smelts): the members of one burn
 * class burn the same ticks a piece, so a member of more wood a piece than the class's least-wood member (a composter: 7
 * slabs = 3.5 planks, 300 ticks like 1 plank) is out of the class's word - the member is never left to seconds alone.
 * Members of no wood (mangrove_roots: no recipe) stay. Coal in the bag burns as before (the control).
 */
class Z77FuelWoodTest {
    @AfterEach
    void clear() { TestData.clear(); }

    /** The planner's world plus slabs (150 ticks, 6 from 3 planks), the composter (300, 7 slabs) and mangrove_roots (300, no recipe). */
    static TestData composterWorld() { return composterWorld(true); }

    static TestData composterWorld(boolean slabsBurn) {
        TestData d = ScenariosTest.world("wooden", "stone");
        d.recipe("oak_slab", 6, "oak_planks", "oak_planks", "oak_planks").tag("oak_slab", "minecraft:wooden_slabs");
        if (slabsBurn) d.fuel("oak_slab", 150);
        d.recipe("composter", 1, Collections.nCopies(7, "#minecraft:wooden_slabs").toArray(new String[0])).fuel("composter", 300);
        d.fuel("mangrove_roots", 300);
        d.tableItems.add(TestData.id("oak_slab"));
        d.tableItems.add(TestData.id("composter"));
        return d.install();
    }

    static boolean composter(Chain c) {
        return c.steps().stream().anyMatch(s -> s instanceof Step.Craft k && k.item().contains("composter"));
    }

    /** The brief's scenario: 24 smelts, logs in the bag, no coal: planks or slabs burn, no composter made. */
    @Test
    void twentyFourSmeltsLogsInTheBagBurnPlanksOrSlabs() {
        composterWorld();
        Chain c = NeedChain.resolve("iron_ingot", 24, ScenariosTest.w(Map.of("minecraft:raw_iron", 24, "minecraft:oak_log", 8), true, true, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertFalse(composter(c), "no composter crafted: " + c.why());
        assertTrue(c.why().contains("planks") || c.why().contains("slab"), "planks or slabs burn: " + c.why());
        assertTrue(c.dropped().stream().anyMatch(x -> x.startsWith("burn 300 ticks: ") && x.contains("composter 3.5") && x.contains("vs 1.0")),
                "the composter is left out by the wood it consumes a piece: " + c.dropped());
    }

    /**
     * DH's shape: the member a class way makes is chosen by seconds alone, so a composter quick to make (112 slabs in the bag,
     * here not a fuel of their own) won over planks from a tree 40 m away though it burns 56 planks of wood for 16. Now the
     * composter is out of the class: planks are made from the tree.
     */
    @Test
    void slabsInTheBagAreNotCraftedIntoComposters() {
        composterWorld(false);
        Chain c = NeedChain.resolve("iron_ingot", 24, ScenariosTest.w(Map.of("minecraft:raw_iron", 24, "minecraft:oak_slab", 112), true, true,
                Map.of("oak_log", List.of(ScenariosTest.tree(40, 8)))));
        assertTrue(c.ok(), c.why());
        assertFalse(composter(c), "no composter crafted: " + c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Craft k && k.item().contains("planks")), "planks made for the burn: " + c.why());
    }

    /** Control: coal in the bag burns as now (3 coal for 24 smelts), nothing made of wood. */
    @Test
    void coalInTheBagBurnsAsBefore() {
        composterWorld();
        Chain c = NeedChain.resolve("iron_ingot", 24, ScenariosTest.w(Map.of("minecraft:raw_iron", 24, "minecraft:oak_log", 8, "minecraft:coal", 3), true, true, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.why().contains("burn 3 coal"), c.why());
        assertFalse(composter(c), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft k && (k.item().contains("planks") || k.item().contains("slab"))), "no wood made for the burn: " + c.why());
    }
}
