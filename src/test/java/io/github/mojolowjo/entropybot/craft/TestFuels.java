package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;

import java.util.List;
import java.util.Map;

/**
 * 0.39.z47: the game's fuel table (vanilla burn ticks, as the adapter reads NeoForge getBurnTime) for the furnace tests:
 * the planner and FurnaceJobs take their fuel from ItemWorth.fuels(), no name list. A furnace smelts one item in 200 ticks.
 */
final class TestFuels {
    private TestFuels() {}

    static final Map<String, Integer> FUELS = Map.ofEntries(
            Map.entry("minecraft:coal", 1600), Map.entry("minecraft:charcoal", 1600), Map.entry("minecraft:coal_block", 16000),
            Map.entry("minecraft:lava_bucket", 20000), Map.entry("minecraft:blaze_rod", 2400), Map.entry("minecraft:dried_kelp_block", 4000),
            Map.entry("minecraft:oak_planks", 300), Map.entry("minecraft:spruce_planks", 300), Map.entry("minecraft:birch_planks", 300),
            Map.entry("minecraft:oak_log", 300), Map.entry("minecraft:spruce_log", 300), Map.entry("minecraft:stick", 100));

    static void install() {
        ItemWorth.use(new ItemWorth.Source() {
            @Override public ItemWorth.Facts facts(String id) { return ItemWorth.Facts.NONE; }
            @Override public List<ItemWorth.Recipe> recipes(String id) { return List.of(); }
            @Override public Map<String, Integer> fuels() { return FUELS; }
        });
    }

    static void remove() { ItemWorth.use(null); }
}
