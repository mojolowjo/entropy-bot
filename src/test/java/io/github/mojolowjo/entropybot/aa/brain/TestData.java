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
    public final Map<String, String> smeltInputs = new HashMap<>(), oreDrops = new HashMap<>();
    public final Set<String> tableItems = new HashSet<>();
    @Override public String smeltInput(String result) { return smeltInputs.get(result); }
    @Override public boolean needsTable(String id) { return tableItems.contains(id); }
    @Override public String oreDropping(String item) { return oreDrops.get(item); }
    public final Set<String> fishing = new HashSet<>();
    @Override public Set<String> fishingFish() { return fishing; }
    public final Map<String, ItemWorth.Mob> mobs = new HashMap<>();
    @Override public ItemWorth.Mob mob(String id) { return mobs.get(id); }
    public TestData mob(String id, double hp, double dmg, ItemWorth.MobMove move, boolean flagged) { mobs.put(id(id), new ItemWorth.Mob(hp, dmg, move, flagged)); return this; }
    public final Set<String> container = new HashSet<>();
    @Override public boolean container(String id) { return container.contains(id); }
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
        d.armour("leather_chestplate", 3, 0, 80).armour("golden_chestplate", 5, 0, 112).armour("chainmail_chestplate", 5, 0, 240).armour("netherite_chestplate", 8, 3, 592);
        for (String a : List.of("leather", "golden", "chainmail", "iron", "diamond", "netherite")) d.tag(a + "_chestplate", "minecraft:trimmable_armor");
        String[][] armourMat = {{"leather", "leather"}, {"golden", "gold_ingot"}, {"iron", "iron_ingot"}, {"diamond", "diamond"}};
        int[] armourCost = {5, 8, 7, 4};
        String[] armourSlot = {"helmet", "chestplate", "leggings", "boots"};
        for (String[] m : armourMat) for (int i = 0; i < 4; i++) d.recipe(m[0] + "_" + armourSlot[i], 1, java.util.Collections.nCopies(armourCost[i], m[1]).toArray(new String[0]));
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
        d.mob("zombie", 20, 3, ItemWorth.MobMove.WALK, true).mob("husk", 20, 3, ItemWorth.MobMove.WALK, true).mob("drowned", 20, 3, ItemWorth.MobMove.RANGED, true)
                .mob("zombie_villager", 20, 3, ItemWorth.MobMove.WALK, true).mob("skeleton", 20, Double.NaN, ItemWorth.MobMove.RANGED, true).mob("stray", 20, Double.NaN, ItemWorth.MobMove.RANGED, true)
                .mob("spider", 16, 2, ItemWorth.MobMove.SPIDER, true).mob("cave_spider", 12, 2, ItemWorth.MobMove.SPIDER, true).mob("nyfsspiders:big_spider", 40, 4, ItemWorth.MobMove.SPIDER, true)
                .mob("enderman", 40, 7, ItemWorth.MobMove.TELEPORT, true).mob("witch", 26, Double.NaN, ItemWorth.MobMove.RANGED, false).mob("pillager", 24, Double.NaN, ItemWorth.MobMove.RANGED, true)
                .mob("vindicator", 24, 13, ItemWorth.MobMove.WALK, true).mob("slime", 16, 4, ItemWorth.MobMove.WALK, false).mob("phantom", 20, 2, ItemWorth.MobMove.FLY, false)
                .mob("blaze", 20, 6, ItemWorth.MobMove.FLY, false).mob("wither_skeleton", 20, 8, ItemWorth.MobMove.WALK, true).mob("creeper", 20, Double.NaN, ItemWorth.MobMove.WALK, true);
        d.fishing.addAll(List.of(id("cod"), id("salmon"), id("tropical_fish")));
        d.recipe("fishing_rod", 1, "stick", "stick", "stick", "string", "string");
        d.station.addAll(List.of(id("crafting_table"), id("furnace"), id("chest"), id("blast_furnace")));
        d.container.addAll(List.of(id("chest"), id("furnace"), id("blast_furnace"), id("trapped_chest")));
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
        d.weapon("leafscopperbackport:copper_sword", 5, 190).tool("leafscopperbackport:copper_axe", Role.AXE, 5, 1, 190);
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

    /** The vanilla data plus the recipes, furnace recipes and ore drops the goal planner reads (PlanRecipes). */
    public static TestData planner() {
        TestData d = vanilla();
        for (String s : List.of("iron_ingot", "diamond", "coal")) d.recipes.remove(id(s));
        for (String w : List.of("oak", "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak")) d.recipe(w + "_planks", 4, w + "_log");
        d.recipe("stick", 4, "oak_planks", "oak_planks").recipe("torch", 4, "coal", "stick").recipe("crafting_table", 1, "oak_planks", "oak_planks", "oak_planks", "oak_planks");
        d.recipe("chest", 1, java.util.Collections.nCopies(8, "oak_planks").toArray(new String[0])).recipe("furnace", 1, java.util.Collections.nCopies(8, "cobblestone").toArray(new String[0]));
        d.recipe("white_bed", 1, "white_wool", "white_wool", "white_wool", "oak_planks", "oak_planks", "oak_planks").recipe("bucket", 1, "iron_ingot", "iron_ingot", "iron_ingot");
        d.recipe("shield", 1, "oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks", "iron_ingot");
        String[][] mats = {{"wooden", "oak_planks"}, {"stone", "cobblestone"}, {"iron", "iron_ingot"}, {"diamond", "diamond"}};
        for (String[] m : mats) {
            d.recipe(m[0] + "_pickaxe", 1, m[1], m[1], m[1], "stick", "stick").recipe(m[0] + "_axe", 1, m[1], m[1], m[1], "stick", "stick")
                    .recipe(m[0] + "_shovel", 1, m[1], "stick", "stick").recipe(m[0] + "_sword", 1, m[1], m[1], "stick").recipe(m[0] + "_hoe", 1, m[1], m[1], "stick", "stick");
        }
        d.recipe("iron_helmet", 1, java.util.Collections.nCopies(5, "iron_ingot").toArray(new String[0])).recipe("iron_chestplate", 1, java.util.Collections.nCopies(8, "iron_ingot").toArray(new String[0]))
                .recipe("iron_leggings", 1, java.util.Collections.nCopies(7, "iron_ingot").toArray(new String[0])).recipe("iron_boots", 1, java.util.Collections.nCopies(4, "iron_ingot").toArray(new String[0]));
        for (String s : List.of("chest", "furnace", "white_bed", "bucket", "shield", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots")) d.tableItems.add(id(s));
        for (String m : List.of("wooden", "stone", "iron", "diamond")) for (String k : List.of("pickaxe", "axe", "shovel", "sword", "hoe")) d.tableItems.add(id(m + "_" + k));
        String[][] smelt = {{"iron_ingot", "raw_iron"}, {"gold_ingot", "raw_gold"}, {"copper_ingot", "raw_copper"}, {"glass", "sand"}, {"stone", "cobblestone"}, {"charcoal", "oak_log"}};
        for (String[] s : smelt) { d.smeltInputs.put(id(s[0]), id(s[1])); d.recipe(s[0], 1, s[1]); }
        String[][] ores = {{"raw_iron", "iron_ore"}, {"raw_copper", "copper_ore"}, {"raw_gold", "gold_ore"}, {"coal", "coal_ore"}, {"diamond", "diamond_ore"},
                {"redstone", "redstone_ore"}, {"lapis_lazuli", "lapis_ore"}, {"emerald", "emerald_ore"}};
        for (String[] o : ores) d.oreDrops.put(id(o[0]), id(o[1]));
        d.tag("coal", "minecraft:coals").tag("charcoal", "minecraft:coals");
        return d;
    }

    /** Installs this as the item data and returns it. */
    public TestData install() { ItemWorth.use(this); MiningTiers.use(this); ToolGroup.use(List.of(new ToolGroup.Tier("wood", 2, 59), new ToolGroup.Tier("stone", 4, 131), new ToolGroup.Tier("iron", 6, 250), new ToolGroup.Tier("diamond", 8, 1561), new ToolGroup.Tier("gold", 12, 32), new ToolGroup.Tier("netherite", 9, 2031))); ItemWorth.resetWarnings(); return this; }

    public static void clear() { ItemWorth.use(null); MiningTiers.use(null); ToolGroup.use(null); }

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
