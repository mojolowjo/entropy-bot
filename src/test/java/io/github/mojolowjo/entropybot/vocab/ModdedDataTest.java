package io.github.mojolowjo.entropybot.vocab;

import io.github.mojolowjo.entropybot.aa.brain.Junk;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.camp.FoodPlan;
import io.github.mojolowjo.entropybot.clear.FloorFill;
import io.github.mojolowjo.entropybot.restore.RestoreRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** rm-names pass 2: a modded pack through the tag, food, mob-loot and junk readers, with no list consulted. */
class ModdedDataTest {
    @AfterEach void tearDown() { TestData.clear(); }

    TestData pack() {
        TestData d = TestData.vanilla();
        d.food("moddium:spoiled_pie", 4, 1).food("moddium:glow_berry", 1, 0.2).food("moddium:boar_meat", 3, 1.8).food("moddium:cooked_boar", 9, 12)
                .block("moddium:basalt_brick", 1.8, 0).block("moddium:cedar_log", 2, 0).block("moddium:rune_ore", 3, 0).block("moddium:humus", 0.4, 0);
        d.junkFood.add("moddium:spoiled_pie");
        d.cooks.put("moddium:boar_meat", "moddium:cooked_boar");
        d.mobFood.put("moddium:boar", "moddium:boar_meat");
        d.stone.add("moddium:basalt_brick");
        d.tag("moddium:cedar_log", "minecraft:logs").tag("moddium:rune_ore", "c:ores").tag("moddium:humus", "minecraft:dirt").tag("moddium:humus", "minecraft:mineable/shovel");
        return d.install();
    }

    @Test void junkFoodIsHarmfulOrThinByTheFoodData() {
        pack();
        assertTrue(Junk.is("moddium:spoiled_pie"), "a harmful food is junk by its data, no name");
        assertTrue(Junk.is("rotten_flesh"), "vanilla's poisons come the same way (set by the adapter from FoodProperties)");
        assertFalse(Junk.is("moddium:no_data_at_all"), "no data: unknown, kept");
    }

    @Test void huntAndFoodKindsReadTheLootAndRecipes() {
        pack();
        assertTrue(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood().containsKey("moddium:boar"), "a mob whose loot table drops a food is huntable");
        assertEquals("moddium:boar_meat", io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood().get("moddium:boar"));
        assertTrue(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood().containsValue("moddium:boar_meat"));
        assertEquals("cooked_boar", io.github.mojolowjo.entropybot.aa.brain.FoodModel.cooked("boar_meat"));
        assertTrue(FoodPlan.raw("moddium:boar_meat") && !FoodPlan.edible("moddium:boar_meat") && FoodPlan.edible("moddium:cooked_boar"));
        assertFalse(FoodPlan.edible("moddium:spoiled_pie"));
        assertTrue(FoodPlan.order().contains("boar"));
    }

    @Test void blockFamiliesComeFromTagsAndHardness() {
        pack();
        assertTrue(Kinds.byName("logs", "moddium:cedar_log", null), "logs = the logs tag, any mod");
        assertTrue(Kinds.byName("ores", "moddium:rune_ore", null));
        assertTrue(Kinds.byName("stone", "moddium:basalt_brick", null));
        assertFalse(Kinds.byName("stone", "moddium:humus", null), "soft is no stone");
        assertTrue(FloorFill.isFallback("moddium:humus") && FloorFill.isFallback("moddium:basalt_brick"));
        assertEquals("moddium:basalt_brick", FloorFill.fallback(Map.of("moddium:humus", 5, "moddium:basalt_brick", 5, "minecraft:cobblestone", 5)).get(0));
        assertTrue(RestoreRules.natural("minecraft:stone") && !RestoreRules.natural("moddium:rune_ore"));
    }
}
