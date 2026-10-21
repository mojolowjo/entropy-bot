package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth.Facts;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth.Recipe;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth.Role;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test data only (allowed to name items, NoNameListsTest): a fake item world standing in for the game's components, tags and
 * recipes, so the brain's tests run on data and never on a name list. {@link #vanilla()} carries vanilla-like numbers;
 * a test adds a modded item and the production code must treat it like any other.
 */
public final class TestData implements ItemWorth.Source, MiningTiers.Source {
    /** Block (or item placing it) -> the pickaxe tier that mines it; missing = unknown. */
    public final Map<String, Integer> tiers = new HashMap<>();
    @Override public int blockTier(String itemId) { return tiers.getOrDefault(itemId, -1); }
    public final Map<String, Facts> facts = new HashMap<>();
    public final Map<String, List<Recipe>> recipes = new HashMap<>();
    public final Map<String, String> cooks = new HashMap<>();
    public final Map<String, Set<String>> tags = new HashMap<>();
    public final Map<String, String> mobFood = new HashMap<>();
    public final Set<String> shields = new HashSet<>();
    public TestData tag(String id, String... t) { tags.computeIfAbsent(id(id), k -> new HashSet<>()).addAll(Arrays.asList(t)); return this; }
    public final Set<String> riskyFood = new HashSet<>();
    @Override public boolean riskyFood(String id) { return riskyFood.contains(id); }
    public final Set<String> junkFood = new HashSet<>(), effectFood = new HashSet<>();
    public final Set<String> stone = new HashSet<>(), station = new HashSet<>(), ore = new HashSet<>(), plank = new HashSet<>();

    public static String id(String s) { return s.contains(":") ? s : "minecraft:" + s; }

    public TestData tool(String id, Role role, double speed, int level, double dur) { facts.put(id(id), new Facts(role, speed, level, dur, 0, 0, 0, 0, 0, 0, 0)); return this; }
    public TestData weapon(String id, double attack, double dur) { facts.put(id(id), new Facts(Role.WEAPON, 0, 0, dur, 0, 0, attack, 0, 0, 0, 0)); return this; }
    public TestData armour(String id, double def, double tough, double dur) { facts.put(id(id), new Facts(Role.OTHER, 0, 0, dur, def, tough, 0, 0, 0, 0, 0)); return this; }
    public TestData food(String id, double nut, double sat) { facts.put(id(id), new Facts(Role.OTHER, 0, 0, 0, 0, 0, 0, nut, sat, 0, 0)); return this; }
    public TestData block(String id, double hard, int light) { facts.put(id(id), new Facts(Role.OTHER, 0, 0, 0, 0, 0, 0, 0, 0, hard, light)); return this; }
    public TestData recipe(String out, int n, String... in) {
        recipes.computeIfAbsent(id(out), k -> new ArrayList<>()).add(new Recipe(n, Arrays.stream(in).map(TestData::id).toList()));
        return this;
    }

    public static TestData vanilla() {
        TestData d = new TestData();
        Object[][] mats = {{"wooden", 2.0, 0, 59.0, 4.0}, {"stone", 4.0, 1, 131.0, 5.0}, {"iron", 6.0, 2, 250.0, 6.0}, {"diamond", 8.0, 3, 1561.0, 7.0},
                {"golden", 12.0, 0, 32.0, 4.0}, {"netherite", 9.0, 3, 2031.0, 8.0}};
        for (Object[] m : mats) {
            String n = (String) m[0];
            double sp = (Double) m[1], du = (Double) m[3];
            int lv = (Integer) m[2];
            d.tool(n + "_pickaxe", Role.PICKAXE, sp, lv, du).tool(n + "_axe", Role.AXE, sp, lv, du).tool(n + "_shovel", Role.SHOVEL, sp, lv, du)
                    .tool(n + "_hoe", Role.HOE, sp, lv, du).weapon(n + "_sword", (Double) m[4], du);
        }
        d.armour("leather_helmet", 1, 0, 55).armour("iron_chestplate", 6, 0, 240).armour("diamond_chestplate", 8, 2, 528).armour("iron_helmet", 2, 0, 165)
                .armour("shield", 0, 0, 336).armour("bow", 0, 0, 384)
                .food("cooked_beef", 8, 12.8).food("beef", 3, 1.8).food("cooked_porkchop", 8, 12.8).food("porkchop", 3, 1.8).food("bread", 5, 6)
                .food("apple", 4, 2.4).food("sweet_berries", 2, 0.4).food("mushroom_stew", 6, 7.2).food("cod", 2, 0.4).food("cooked_cod", 5, 6)
                .food("dried_kelp", 1, 0.6).food("kelp", 1, 0.6).food("baked_potato", 5, 6).food("potato", 1, 0.6).food("cooked_chicken", 6, 7.2).food("chicken", 2, 1.2)
                .food("mutton", 2, 1.2).food("cooked_mutton", 6, 9.6)
                .block("cobblestone", 2, 0).block("stone", 1.5, 0).block("dirt", 0.5, 0).block("gravel", 0.6, 0).block("sand", 0.5, 0).block("netherrack", 0.4, 0)
                .block("oak_planks", 2, 0).block("oak_log", 2, 0).block("iron_ore", 3, 0).block("diamond_ore", 3, 0).block("coal_ore", 3, 0).block("crafting_table", 2.5, 0)
                .block("furnace", 3.5, 0)
                .block("torch", 0, 14);
        for (String s : List.of("cobblestone", "stone", "dirt", "gravel", "sand", "netherrack")) d.stone.add(id(s));
        d.ore.addAll(List.of(id("iron_ore"), id("diamond_ore"), id("coal_ore")));
        d.station.add(id("crafting_table"));
        d.plank.add(id("oak_planks"));
        for (String s : List.of("iron_ore", "copper_ore", "lapis_ore", "deepslate_iron_ore")) d.tiers.put(id(s), 2);
        for (String s : List.of("gold_ore", "diamond_ore", "redstone_ore", "emerald_ore", "deepslate_diamond_ore")) d.tiers.put(id(s), 3);
        for (String s : List.of("coal_ore", "stone", "cobblestone")) d.tiers.put(id(s), 1);
        d.tiers.put(id("dirt"), 0);
        for (String s : List.of("cobbled_deepslate", "deepslate", "tuff", "andesite", "diorite", "granite", "blackstone", "coarse_dirt", "red_sand", "calcite", "basalt")) d.block(s, s.equals("cobbled_deepslate") ? 3.5 : s.equals("deepslate") ? 3 : 1.5, 0);
        for (String s : List.of("cobbled_deepslate", "deepslate", "tuff", "andesite", "diorite", "granite", "blackstone", "calcite", "basalt")) { d.stone.add(id(s)); d.tiers.put(id(s), 1); }
        d.stone.addAll(List.of(id("coarse_dirt"), id("red_sand")));
        d.tag("coarse_dirt", "minecraft:dirt").tag("red_sand", "minecraft:sand").tag("grass_block", "minecraft:dirt").tag("podzol", "minecraft:dirt");
        for (String w : List.of("spruce", "birch", "jungle", "acacia", "cherry", "dark_oak")) { d.block(w + "_planks", 2, 0).block(w + "_log", 2, 0); d.plank.add(id(w + "_planks")); d.tag(w + "_planks", "minecraft:planks").tag(w + "_log", "minecraft:logs"); }
        d.tag("crimson_stem", "minecraft:logs").tag("stripped_oak_log", "minecraft:logs").block("crimson_stem", 2, 0).block("stripped_oak_log", 2, 0);
        for (String w : List.of("oak", "birch", "spruce")) { d.block(w + "_leaves", 0.2, 0).tag(w + "_leaves", "minecraft:leaves"); }
        d.tag("birch_log", "minecraft:logs").tag("deepslate_iron_ore", "c:ores").block("deepslate_iron_ore", 4.5, 0).ore.add(id("deepslate_iron_ore"));
        d.tool("leafscopperbackport:copper_pickaxe", Role.PICKAXE, 5, 1, 190).tool("minecraft:wooden_shovel", Role.SHOVEL, 2, 0, 59);
        for (String s : List.of("stone", "deepslate", "andesite", "diorite", "granite", "tuff", "calcite")) d.tag(s, "minecraft:base_stone_overworld");
        for (String s : List.of("gravel", "clay", "mud", "red_sand", "sand", "dirt", "coarse_dirt")) d.tag(s, "minecraft:mineable/shovel");
        d.tag("clay", "minecraft:clay_like").tag("mud", "minecraft:mud_like").tag("wheat", "minecraft:crops").tag("pumpkin_stem", "minecraft:crops");
        d.riskyFood.add(id("chicken"));
        d.tag("coal", "minecraft:coals").tag("charcoal", "minecraft:coals").tag("ancient_debris", "c:ores").tag("wheat_seeds", "minecraft:villager_plantable_seeds").tag("mysticalagriculture:inferium_seeds", "minecraft:villager_plantable_seeds");
        d.food("golden_carrot", 6, 14.4).food("golden_apple", 4, 9.6).food("carrot", 3, 3.6).food("suspicious_stew", 6, 7.2).food("pumpkin_pie", 8, 4.8).food("cookie", 2, 0.4).food("melon_slice", 2, 1.2);
        d.effectFood.addAll(List.of(id("golden_carrot"), id("golden_apple"), id("enchanted_golden_apple"), id("suspicious_stew")));
        d.junkFood.addAll(List.of(id("rotten_flesh"), id("spider_eye"), id("poisonous_potato"), id("chorus_fruit"), id("pufferfish"), id("snowball_junk")));
        d.tag("carrot", "minecraft:villager_plantable_seeds").tag("potato", "minecraft:villager_plantable_seeds").tag("wheat_seeds", "minecraft:villager_plantable_seeds");
        d.recipe("bread", 1, "wheat", "wheat", "wheat");
        d.mobFood.putAll(Map.of(id("cow"), id("beef"), id("pig"), id("porkchop"), id("sheep"), id("mutton"), id("chicken"), id("chicken")));
        d.shields.add(id("shield"));
        for (String s : List.of("oak_log")) d.tag(s, "minecraft:logs");
        d.tag("oak_planks", "minecraft:planks").tag("dirt", "minecraft:dirt").tag("sand", "minecraft:sand").tag("stone", "minecraft:base_stone_overworld")
                .tag("cobblestone", "minecraft:stone_tool_materials").tag("cobbled_deepslate", "minecraft:stone_tool_materials").tag("blackstone", "minecraft:stone_tool_materials").tag("iron_ore", "c:ores", "minecraft:mineable/pickaxe").tag("diamond_ore", "c:ores")
                .tag("coal_ore", "c:ores");
        d.recipe("iron_ingot", 1, "iron_ore").recipe("diamond", 1, "diamond_ore").recipe("coal", 1, "coal_ore");
        d.cooks.putAll(Map.of(id("beef"), id("cooked_beef"), id("porkchop"), id("cooked_porkchop"), id("chicken"), id("cooked_chicken"), id("mutton"), id("cooked_mutton"),
                id("cod"), id("cooked_cod"), id("potato"), id("baked_potato"), id("kelp"), id("dried_kelp")));
        return d;
    }

    /** Installs this as the item data and returns it. */
    public TestData install() { ItemWorth.use(this); MiningTiers.use(this); ItemWorth.resetWarnings(); return this; }

    public static void clear() { ItemWorth.use(null); MiningTiers.use(null); }

    @Override public Facts facts(String id) { return facts.getOrDefault(id, Facts.NONE); }
    @Override public List<Recipe> recipes(String id) { return recipes.getOrDefault(id, List.of()); }
    @Override public List<String> smeltResults() { return List.of(id("iron_ingot"), id("diamond"), id("coal")); }
    @Override public Map<String, String> cooks() { return cooks; }
    @Override public boolean stoneLike(String id) { return stone.contains(id); }
    @Override public boolean station(String id) { return station.contains(id); }
    @Override public boolean ore(String id) { return ore.contains(id); }
    @Override public Set<String> withTag(String tag) {
        Set<String> out = new HashSet<>();
        for (var e : tags.entrySet()) if (e.getValue().contains(tag)) out.add(e.getKey());
        return out;
    }
    @Override public Set<String> tags(String id) { return tags.getOrDefault(id, Set.of()); }
    @Override public Map<String, String> mobFood() { return mobFood; }
    @Override public boolean shield(String id) { return shields.contains(id); }
    @Override public boolean junkFood(String id) { return junkFood.contains(id); }
    @Override public boolean effectFood(String id) { return effectFood.contains(id); }
    @Override public boolean plank(String id) { return plank.contains(id); }
}
