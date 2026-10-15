package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.camp.HousePlan;
import io.github.mojolowjo.entropybot.commands.ClimbPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.30.2 (trial 8 check after the house): under my own roof is not a hole; a mod's crafting table is a station. */
class IndoorsTest {
    @Test
    void underAPlankRoofIsIndoors() {
        FakeWorld w = new FakeWorld();
        w.fill(-1, 1, 53, 56, -1, 1, "air");                // a room dug into the stone, feet at 53
        w.fill(-1, 1, 57, 57, -1, 1, "oak_planks");         // the roof (the rim 5 above me)
        assertTrue(ClimbPlan.indoors(w, 0, 53, 0, 5));
    }

    @Test
    void underStoneIsAHole() {
        FakeWorld w = new FakeWorld();
        w.fill(0, 0, 50, 51, 0, 0, "air");                  // a pit in the stone, feet at 50, stone over the head at 52
        assertFalse(ClimbPlan.indoors(w, 0, 50, 0, 3));
    }

    @Test
    void stationIdsFromOtherMods() {
        assertTrue(HousePlan.isStation("visualworkbench:minecraft/crafting_table", "crafting_table"));
        assertTrue(HousePlan.isStation("minecraft:crafting_table", "crafting_table"));
        assertTrue(HousePlan.isStation("minecraft:furnace", "furnace"));
        assertTrue(HousePlan.isStation("sophisticatedstorage:chest", "chest"));
        assertFalse(HousePlan.isStation("minecraft:chest_minecart", "chest"));
        assertFalse(HousePlan.isStation("minecraft:blast_furnace_x", "furnace"));
    }
}
