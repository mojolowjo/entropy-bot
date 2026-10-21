package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth.Facts;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth.Recipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND4 C: worth derived from item data (vanilla numbers as the game states them), a recipe-only modded item, and an explicit unknown. */
class ItemWorthTest {
    final Map<String, Facts> facts = new HashMap<>();
    final Map<String, List<Recipe>> recipes = new HashMap<>();

    @BeforeEach
    void setUp() {
        pick("wooden_pickaxe", 2, 0, 59); pick("stone_pickaxe", 4, 1, 131); pick("iron_pickaxe", 6, 2, 250);
        pick("diamond_pickaxe", 8, 3, 1561); pick("golden_pickaxe", 12, 0, 32);
        facts.put("minecraft:iron_sword", new Facts("weapon", 0, 0, 250, 0, 0, 6, 0, 0, 0, 0));
        facts.put("minecraft:wooden_sword", new Facts("weapon", 0, 0, 59, 0, 0, 5 - 1, 0, 0, 0, 0));
        facts.put("minecraft:iron_chestplate", new Facts("", 0, 0, 240, 6, 0, 0, 0, 0, 0, 0));
        facts.put("minecraft:diamond_chestplate", new Facts("", 0, 0, 528, 8, 2, 0, 0, 0, 0, 0));
        facts.put("minecraft:cooked_beef", new Facts("", 0, 0, 0, 0, 0, 0, 8, 12.8, 0, 0));
        facts.put("minecraft:cobblestone", new Facts("", 0, 0, 0, 0, 0, 0, 0, 0, 2, 0));
        facts.put("minecraft:torch", new Facts("", 0, 0, 0, 0, 0, 0, 0, 0, 0, 14));
        facts.put("minecraft:oak_planks", new Facts("", 0, 0, 0, 0, 0, 0, 0, 0, 2, 0));
        // a modded pack: an osmium pickaxe with speed 9, a part with a recipe only, a part whose input nobody knows
        pick("osmium:osmium_pickaxe", 9, 2, 700);
        recipes.put("osmium:gear", List.of(new Recipe(2, List.of("minecraft:cobblestone", "minecraft:oak_planks"))));
        recipes.put("osmium:circuit", List.of(new Recipe(1, List.of("osmium:gear", "osmium:mystery_dust"))));
        recipes.put("osmium:ouroboros", List.of(new Recipe(1, List.of("osmium:ouroboros_b"))));
        recipes.put("osmium:ouroboros_b", List.of(new Recipe(1, List.of("osmium:ouroboros"))));
        ItemWorth.use(new ItemWorth.Source() {
            public Facts facts(String id) { return facts.getOrDefault(id, Facts.NONE); }
            public List<Recipe> recipes(String id) { return recipes.getOrDefault(id, List.of()); }
            public List<String> smeltResults() { return List.of("minecraft:iron_ingot"); }
        });
    }

    @AfterEach
    void tearDown() { ItemWorth.use(null); }

    void pick(String id, double speed, int level, double dur) {
        facts.put(id.contains(":") ? id : "minecraft:" + id, new Facts("pickaxe", speed, level, dur, 0, 0, 0, 0, 0, 0, 0));
    }

    double v(String id) { return ItemWorth.of(id).value(); }

    @Test
    void toolsRankByTheirData() {
        assertTrue(v("wooden_pickaxe") < v("stone_pickaxe") && v("stone_pickaxe") < v("iron_pickaxe") && v("iron_pickaxe") < v("diamond_pickaxe"));
        assertEquals(30, v("iron_pickaxe"), 3);          // near the old hand table's 30
        assertEquals(15, v("stone_pickaxe"), 3);
        assertTrue(v("golden_pickaxe") < v("stone_pickaxe"), "fast but brittle and low level");
        assertTrue(v("iron_sword") < v("iron_pickaxe") && v("iron_sword") > v("wooden_sword"));
    }

    @Test
    void armourAndFoodAndBlocks() {
        assertEquals(5, v("iron_chestplate"), 0.5);
        assertTrue(v("diamond_chestplate") > v("iron_chestplate"));
        assertEquals(0.25 * 20.8, v("cooked_beef"), 1e-9);
        assertEquals(0.02, v("cobblestone"), 1e-9);
        assertEquals(0.098, v("torch"), 1e-9);
    }

    @Test
    void aModdedPickaxeOutranksStoneWithNoNameList() {
        assertTrue(v("osmium:osmium_pickaxe") > v("stone_pickaxe"));
        assertTrue(ItemWorth.of("osmium:osmium_pickaxe").why().contains("speed 9.0"));
    }

    @Test
    void aRecipeOnlyItemIsWorthItsIngredients() {
        ItemWorth.Worth g = ItemWorth.of("osmium:gear");
        assertTrue(g.known());
        assertEquals((0.02 + 0.02) * 1.1 / 2, g.value(), 1e-9);
        assertTrue(g.why().startsWith("recipe:"));
    }

    @Test
    void noDataIsAnExplicitUnknownNotZero() {
        ItemWorth.Worth w = ItemWorth.of("osmium:mystery_dust");
        assertFalse(w.known());
        assertTrue(w.why().contains("no facts and no recipe"));
        assertFalse(ItemWorth.of("osmium:circuit").known());                 // an unknown input makes it unknown
        assertTrue(ItemWorth.of("osmium:circuit").why().contains("mystery_dust"));
        assertFalse(ItemWorth.of("osmium:ouroboros").known());               // a loop
        assertTrue(ItemWorth.explain("osmium:mystery_dust").contains("UNKNOWN"));
        assertEquals(7.5, ItemWorth.valueOr("osmium:mystery_dust", 7.5));    // the caller's table
    }

    @Test
    void withoutDataEveryoneKeepsTheirTables() {
        ItemWorth.use(null);
        assertFalse(ItemWorth.of("iron_pickaxe").known());
        assertEquals(30, Kit.toolValue("iron_pickaxe"));
        assertEquals(1.5, Kit.itemValue("raw_iron"));
        assertEquals(0.02, Kit.itemValue("cobblestone"));
        assertTrue(Worth.SMELTS.contains("iron_ingot") && ItemWorth.smelts().isEmpty());
    }

    @Test
    void kitReadsTheDataFirstAndTheTableForWhatItGivesNone() {
        assertEquals(v("osmium:osmium_pickaxe"), Kit.toolValue("osmium:osmium_pickaxe"), 1e-9);   // a modded kit is recovered
        assertEquals(v("iron_pickaxe"), Kit.toolValue("minecraft:iron_pickaxe"), 1e-9);
        assertTrue(Kit.kept("osmium:osmium_pickaxe"));
        assertEquals(1.5, Kit.itemValue("raw_iron"));                          // scarcity: no component says it, the table stays
        assertEquals(v("osmium:gear"), Kit.itemValue("osmium:gear"), 1e-9);
        assertEquals(List.of("minecraft:iron_ingot"), ItemWorth.smelts());
    }

    @Test
    void tuningNamesAreDefined() {
        for (String n : List.of("worth.toolK", "worth.weaponK", "worth.armourK", "worth.foodK", "worth.hardK", "worth.lightK", "worth.margin", "worth.depth"))
            assertFalse(Double.isNaN(Tuning.defOf(n)), n);
    }
    @Test
    void goldIsNotAboveStoneAndTheTiersKeepTheirOrder() {
        assertTrue(v("golden_pickaxe") <= v("stone_pickaxe"));
        assertTrue(v("wooden_pickaxe") < v("stone_pickaxe") && v("stone_pickaxe") < v("iron_pickaxe") && v("iron_pickaxe") < v("diamond_pickaxe"));
        assertEquals(Kit.toolValue("golden_pickaxe") <= Kit.toolValue("stone_pickaxe"), true);
    }

    @Test
    void oneTermNeverPassesTheCap() {
        facts.put("osmium:god_sword", new Facts("weapon", 0, 0, 5000, 0, 0, 50, 0, 0, 0, 0));
        facts.put("osmium:god_pick", new Facts("pickaxe", 500, 9, 9000, 0, 0, 0, 0, 0, 0, 0));
        facts.put("osmium:god_plate", new Facts("", 0, 0, 900, 40, 300, 0, 0, 0, 0, 0));
        ItemWorth.use(sourceNow());
        for (String id : List.of("osmium:god_sword", "osmium:god_pick", "osmium:god_plate")) assertEquals(Tuning.get("worth.cap"), v(id), 1e-9, id);
    }

    @Test
    void anUncraftingRecipeNeverMakesAnIngotJunk() {
        // ingot <-> block, plus nuggets: the storage-block recipes are skipped; with no other recipe the ingot is UNKNOWN (the LOOT table applies)
        recipes.put("osmium:ingot", List.of(new Recipe(9, List.of("osmium:block")), new Recipe(1, List.of("osmium:nugget", "osmium:nugget", "osmium:nugget", "osmium:nugget", "osmium:nugget", "osmium:nugget", "osmium:nugget", "osmium:nugget", "osmium:nugget"))));
        recipes.put("osmium:block", List.of(new Recipe(1, java.util.Collections.nCopies(9, "osmium:ingot"))));
        recipes.put("osmium:nugget", List.of(new Recipe(9, List.of("osmium:ingot"))));
        ItemWorth.use(sourceNow());
        assertFalse(ItemWorth.of("osmium:ingot").known());
        assertEquals(1.5, ItemWorth.valueOr("osmium:ingot", 1.5));
        // and a recipe result is never under the floor
        recipes.put("osmium:dust", List.of(new Recipe(64, List.of("minecraft:cobblestone", "minecraft:oak_planks"))));
        ItemWorth.use(sourceNow());
        assertTrue(v("osmium:dust") >= Tuning.get("worth.floor"));
    }

    @Test
    void subResultsAreMemoised() {
        int[] calls = {0};
        // a chain of 4 parts each with 3 recipes of 3 inputs: cold fan-out is huge uncached
        Map<String, List<Recipe>> r = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            List<Recipe> rs = new java.util.ArrayList<>();
            for (int k = 0; k < 3; k++) rs.add(new Recipe(1, List.of("m:p" + (i + 1), "m:p" + (i + 1), "m:p" + (i + 1))));
            r.put("m:p" + i, rs);
        }
        r.put("m:p3", List.of(new Recipe(1, List.of("minecraft:cobblestone"))));
        ItemWorth.use(new ItemWorth.Source() {
            public Facts facts(String id) { return facts.getOrDefault(id, Facts.NONE); }
            public List<Recipe> recipes(String id) { calls[0]++; return r.getOrDefault(id, List.of()); }
        });
        assertTrue(ItemWorth.of("m:p0").known());
        int cold = calls[0];
        assertTrue(cold < 60, "cold calls " + cold);
        ItemWorth.of("m:p0");
        ItemWorth.of("m:p1");
        assertEquals(cold, calls[0], "warm reads call nothing");
    }

    @Test
    void furnaceWorthIsStableAcrossRepeatedScoring() {
        var tree = new TechTree(List.of(new TechTree.Recipe("minecraft:iron_pickaxe", List.of(java.util.Set.of("minecraft:iron_ingot")))));
        double a = Worth.furnace(tree, java.util.Set.of());
        assertEquals(a, Worth.furnace(tree, java.util.Set.of()), 0);
        assertEquals(a, Worth.furnace(tree, new java.util.HashSet<>()), 0);
    }

    ItemWorth.Source sourceNow() {
        return new ItemWorth.Source() {
            public Facts facts(String id) { return facts.getOrDefault(id, Facts.NONE); }
            public List<Recipe> recipes(String id) { return recipes.getOrDefault(id, List.of()); }
            public List<String> smeltResults() { return List.of("minecraft:iron_ingot"); }
        };
    }
}