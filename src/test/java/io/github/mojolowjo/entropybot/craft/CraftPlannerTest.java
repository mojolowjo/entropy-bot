package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.craft.CraftPlanner.AllPlan;
import io.github.mojolowjo.entropybot.craft.CraftPlanner.Target;
import io.github.mojolowjo.entropybot.craft.CraftPlanner.Targets;
import io.github.mojolowjo.entropybot.craft.Crafter.Craft;
import io.github.mojolowjo.entropybot.craft.Crafter.Plan;
import io.github.mojolowjo.entropybot.craft.Crafter.Smelt;
import io.github.mojolowjo.entropybot.craft.Crafter.Step;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.mojolowjo.entropybot.craft.FakeRecipes.alts;
import static io.github.mojolowjo.entropybot.craft.FakeRecipes.m;
import static org.junit.jupiter.api.Assertions.*;

class CraftPlannerTest {
    static final List<String> PLANKS = alts("oak_planks", "spruce_planks");

    /** A small vanilla-like world, plus sim.js's made-up Refined Storage processor. */
    static FakeRecipes world() {
        FakeRecipes f = new FakeRecipes();
        f.shapeless("oak_planks", "oak_planks", 4, alts("oak_log", "oak_wood"));
        f.shapeless("spruce_planks", "spruce_planks", 4, alts("spruce_log"));
        f.shaped("stick", "stick", 4, new String[] {"#", "#"}, '#', PLANKS);
        f.shaped("torch", "torch", 4, new String[] {"c", "s"}, 'c', alts("coal", "charcoal"), 's', alts("stick"));
        f.shaped("crafting_table", "crafting_table", 1, new String[] {"##", "##"}, '#', PLANKS);
        f.shaped("stone_pickaxe", "stone_pickaxe", 1, new String[] {"ccc", ".s.", ".s."}, 'c', alts("cobblestone"), 's', alts("stick"));
        f.shaped("bread", "bread", 1, new String[] {"www"}, 'w', alts("wheat"));
        f.smelting("iron_ingot_from_smelting_raw_iron", "iron_ingot", 1, alts("raw_iron"));
        f.shapeless("iron_ingot_from_iron_block", "iron_ingot", 9, alts("iron_block"));
        f.shaped("iron_block", "iron_block", 1, new String[] {"iii", "iii", "iii"}, 'i', alts("iron_ingot"));
        f.shaped("iron_pickaxe", "iron_pickaxe", 1, new String[] {"iii", ".s.", ".s."}, 'i', alts("iron_ingot"), 's', alts("stick"));
        f.shapeless("repair_iron_pickaxe", "iron_pickaxe", 1, alts("iron_pickaxe"), alts("iron_pickaxe"));
        f.shaped("iron_sword", "iron_sword", 1, new String[] {"i", "i", "s"}, 'i', alts("iron_ingot"), 's', alts("stick"));
        f.shaped("iron_axe", "iron_axe", 1, new String[] {"ii", "is", ".s"}, 'i', alts("iron_ingot"), 's', alts("stick"));
        f.shaped("iron_helmet", "iron_helmet", 1, new String[] {"iii", "i.i"}, 'i', alts("iron_ingot"));
        f.shaped("iron_boots", "iron_boots", 1, new String[] {"i.i", "i.i"}, 'i', alts("iron_ingot"));
        f.shaped("othermod:iron_helmet", "othermod:iron_helmet", 1, new String[] {"iii", "i.i"}, 'i', alts("iron_ingot"));
        f.shaped("leafscopperbackport:copper_armor_helmet", "leafscopperbackport:copper_armor_helmet", 1, new String[] {"iii", "i.i"}, 'i', alts("copper_ingot"));
        f.shaped("leafscopperbackport:copper_helmet", "leafscopperbackport:copper_helmet", 1, new String[] {"iii", "i.i"}, 'i', alts("copper_ingot"));
        f.smelting("glass", "glass", 1, alts("sand", "red_sand"));
        f.shapeless("refinedstorage:raw_basic_processor", "refinedstorage:raw_basic_processor", 1,
                alts("iron_ingot"), alts("refinedstorage:silicon"), alts("refinedstorage:processor_binding"));
        f.smelting("refinedstorage:basic_processor", "refinedstorage:basic_processor", 1, alts("refinedstorage:raw_basic_processor"));
        f.shaped("mysticalagriculture:inferium_block", "mysticalagriculture:inferium_block", 1, new String[] {"eee", "eee", "eee"},
                'e', alts("mysticalagriculture:inferium_essence"));
        f.item("bedrock", "coal_block", "charcoal");
        return f;
    }

    static Map<String, Integer> counts(Object... kv) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) c.put(m((String) kv[i]), (Integer) kv[i + 1]);
        return c;
    }

    final CraftPlanner p = new CraftPlanner(world());

    /** sim.js's plan(): "craft N -> id | smelt n input (fuel) -> id" or "ERR why" (planAll's prefix included). */
    String sim(String id, int n, Map<String, Integer> c) {
        AllPlan r = p.planAll(List.of(new Target(m(id), n)), c);
        if (!r.ok()) return "ERR " + r.error();
        List<String> parts = new ArrayList<>();
        for (Step s : r.steps()) {
            parts.add(s instanceof Smelt sm ? "smelt " + sm.n() + " " + sm.input() + " (" + sm.fuelCount() + " " + sm.fuel() + ") -> " + sm.item()
                    : "craft " + s.want() + " -> " + s.item());
        }
        return String.join(" | ", parts);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // sim.js 'crafting' scenario
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void simCraftingScenario() {
        assertEquals("craft 2 -> refinedstorage:raw_basic_processor | smelt 2 refinedstorage:raw_basic_processor (1 minecraft:coal) -> refinedstorage:basic_processor",
                sim("refinedstorage:basic_processor", 2, counts("iron_ingot", 2, "refinedstorage:silicon", 2, "refinedstorage:processor_binding", 2, "coal", 1)),
                "a processor: craft the raw one, then smelt it with coal");
        assertEquals("ERR refinedstorage:basic_processor: no fuel (coal or charcoal) to smelt 2 refinedstorage:raw_basic_processor",
                sim("refinedstorage:basic_processor", 2, counts("iron_ingot", 2, "refinedstorage:silicon", 2, "refinedstorage:processor_binding", 2)),
                "no fuel: it says so");
        assertEquals("smelt 3 minecraft:raw_iron (3 minecraft:oak_planks) -> minecraft:iron_ingot",
                sim("iron_ingot", 3, counts("raw_iron", 5, "oak_planks", 4)), "an ingot from raw ore, planks as fuel");
        assertEquals("craft 8 -> minecraft:torch", sim("torch", 8, counts("raw_iron", 1, "charcoal", 2, "stick", 2)), "a plain craft (charcoal for coal)");
        assertEquals("smelt 1 minecraft:raw_iron (1 minecraft:coal) -> minecraft:iron_ingot | craft 1 -> refinedstorage:raw_basic_processor"
                        + " | smelt 1 refinedstorage:raw_basic_processor (1 minecraft:coal) -> refinedstorage:basic_processor",
                sim("refinedstorage:basic_processor", 1, counts("raw_iron", 1, "refinedstorage:silicon", 1, "refinedstorage:processor_binding", 1, "coal", 2)),
                "two furnace steps deep");
    }

    @Test
    void simNeedListsWhatComesFromStorage() {
        Map<String, Integer> storage = counts("iron_ingot", 5, "coal", 3, "refinedstorage:silicon", 4, "refinedstorage:processor_binding", 4);
        assertEquals("I can make it: 1 refinedstorage:raw_basic_processor -> 1 refinedstorage:basic_processor; from storage: "
                        + "1 iron_ingot, 1 coal, 1 refinedstorage:silicon, 1 refinedstorage:processor_binding",
                CraftTexts.needReply(p, "refinedstorage:basic_processor 1", new LinkedHashMap<>(), storage, true));
        assertEquals("I can make that from what I carry: 1 refinedstorage:raw_basic_processor -> 1 refinedstorage:basic_processor (with the furnace)",
                CraftTexts.needReply(p, "refinedstorage:basic_processor", storage, Map.of(), false));
        assertEquals("I can make that from what I carry: 2 oak_planks -> 1 stick -> 4 torch",
                CraftTexts.needReply(p, "torch 4", counts("oak_log", 1, "coal", 1), Map.of(), false));
        assertEquals("missing: refinedstorage:basic_processor: missing 1 refinedstorage:raw_basic_processor to smelt into refinedstorage:basic_processor"
                        + " (counting my chests and the RS network)",
                CraftTexts.needReply(p, "refinedstorage:basic_processor", new LinkedHashMap<>(), counts("coal", 3), true));
        assertEquals("missing: torch: missing 1 coal (or similar) (counting my chests)", CraftTexts.needReply(p, "torch", counts("stick", 4), Map.of(), false));
        assertEquals("error: I don't know an item called unobtainium", CraftTexts.needReply(p, "unobtainium", Map.of(), Map.of(), false));
        assertEquals("usage: need <item> [n]", CraftTexts.needReply(p, "", Map.of(), Map.of(), false));
    }

    @Test
    void suppliesWithoutCommas() {
        String[] err = new String[1];
        Map<String, Integer> s = CraftTexts.parseSupplies(p, "torch 32 bread 16", Map.of(), err);
        assertEquals("supplies (have/want): torch 0/32, bread 0/16 - \"restock\" tops them up", CraftTexts.suppliesReply(s, Map.of()));
        assertEquals(CraftTexts.NO_SUPPLIES, CraftTexts.suppliesReply(Map.of(), Map.of()));
        assertEquals("error: " + CraftTexts.NO_SUPPLIES, CraftTexts.restock(Map.of(), Map.of(), Map.of()).reply());
        CraftTexts.Restock r = CraftTexts.restock(s, counts("torch", 20), counts("torch", 5, "bread", 30));
        assertEquals(counts("torch", 5, "bread", 16), r.take());
        assertEquals("torch 7", r.craftText());
        assertEquals("restocking torch, bread", r.label());
        assertEquals("ok: I have all my supplies", CraftTexts.restock(s, counts("torch", 32, "bread", 20), Map.of()).reply());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the planner
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void logsToPlanksToSticksToTorch() {
        Map<String, Integer> c = counts("oak_log", 1, "coal", 1);
        Plan plan = p.plan(m("torch"), 4, c);
        assertTrue(plan.ok(), plan.error());
        assertEquals(List.of(new Craft("minecraft:oak_planks", m("oak_planks"), 2, 1, false), new Craft("minecraft:stick", m("stick"), 1, 1, false),
                new Craft("minecraft:torch", m("torch"), 4, 1, false)), plan.steps());
        assertEquals("2 oak_planks -> 1 stick -> 4 torch", CraftPlanner.describeSteps(plan.steps()));
        assertEquals(counts("oak_log", 0, "coal", 0, "oak_planks", 2, "stick", 3, "torch", 4), c, "counts updated in place");
    }

    @Test
    void failureLeavesCountsAlone() {
        Map<String, Integer> c = counts("oak_log", 1);
        Map<String, Integer> before = new LinkedHashMap<>(c);
        Plan plan = p.plan(m("torch"), 4, c);
        assertFalse(plan.ok());
        assertEquals("missing 1 coal (or similar)", plan.error());
        assertTrue(plan.steps().isEmpty());
        assertEquals(before, c);
    }

    @Test
    void tagAlternativesMixAndTheFirstGoesFirst() {
        Map<String, Integer> c = counts("oak_planks", 1, "spruce_planks", 3);
        Plan plan = p.plan(m("stick"), 4, c);
        assertTrue(plan.ok());
        assertEquals(counts("oak_planks", 0, "spruce_planks", 2, "stick", 4), c);
        assertEquals("missing 2 oak_planks (or similar)", p.plan(m("stick"), 8, counts("oak_planks", 1, "spruce_planks", 1)).error());
    }

    @Test
    void aPickaxeNeedsATable() {
        Map<String, Integer> c = counts("cobblestone", 3, "spruce_log", 1);
        Plan plan = p.plan(m("stone_pickaxe"), 1, c);
        assertTrue(plan.ok(), plan.error());
        assertEquals(List.of(new Craft("minecraft:spruce_planks", m("spruce_planks"), 2, 1, false), new Craft("minecraft:stick", m("stick"), 2, 1, false),
                new Craft("minecraft:stone_pickaxe", m("stone_pickaxe"), 1, 1, true)), plan.steps());
        assertEquals("missing 1 cobblestone", p.plan(m("stone_pickaxe"), 1, counts("cobblestone", 2, "stick", 2)).error());
        assertFalse(p.craftingRecipesFor(m("crafting_table")).get(0).needsTable(), "2x2 fits the inventory");
        assertTrue(p.craftingRecipesFor(m("bread")).get(0).needsTable(), "3 wide does not");
    }

    @Test
    void smeltingWithFuel() {
        Map<String, Integer> c = counts("sand", 9, "coal", 1, "coal_block", 1, "oak_planks", 20);
        Plan plan = p.plan(m("glass"), 9, c);
        assertEquals(List.of(new Smelt("minecraft:glass", m("glass"), 9, m("sand"), 9, m("coal_block"), 1)), plan.steps(),
                "one coal does not do 9: a coal block before planks");
        assertEquals(0, c.get(m("coal_block")));
        assertEquals(9, c.get(m("glass")));
        c = counts("raw_iron", 8, "coal", 1);
        assertEquals(List.of(new Smelt("minecraft:iron_ingot_from_smelting_raw_iron", m("iron_ingot"), 8, m("raw_iron"), 8, m("coal"), 1)),
                p.plan(m("iron_ingot"), 8, c).steps(), "coal does 8");
        assertEquals(counts("raw_iron", 0, "coal", 0, "iron_ingot", 8), c);
        // the bridge's quirk (9 ingots -> a block -> 9 ingots) is gone since package D: an item is never its own ingredient
        assertEquals("9 iron_ingot",
                CraftPlanner.describeSteps(p.plan(m("iron_ingot"), 9, counts("raw_iron", 9, "coal_block", 1)).steps()));
        assertInstanceOf(Smelt.class, p.plan(m("iron_ingot"), 9, counts("raw_iron", 9, "coal_block", 1)).steps().get(0));
        assertEquals(List.of(new Craft("minecraft:iron_ingot_from_iron_block", m("iron_ingot"), 9, 1, false)),
                p.plan(m("iron_ingot"), 9, counts("iron_block", 1, "raw_iron", 9, "coal_block", 1)).steps(),
                "a block in stock is unpacked (no lower tier in stock), before the furnace");
        assertEquals(new CraftPlanner.Fuel(m("coal"), 1), CraftPlanner.pickFuel(counts("oak_planks", 64, "coal", 1), 8), "coal first, 8 a piece");
        assertEquals(new CraftPlanner.Fuel(m("charcoal"), 2), CraftPlanner.pickFuel(counts("charcoal", 2), 9));
        assertEquals(new CraftPlanner.Fuel(m("spruce_planks"), 9), CraftPlanner.pickFuel(counts("coal", 1, "spruce_planks", 9), 9));
        assertNull(CraftPlanner.pickFuel(counts("coal", 1, "oak_planks", 8), 9));
        // no fuel for an item whose only crafting recipe is a breakdown: the furnace's reason is the useful one (package D)
        assertEquals("no fuel (coal or charcoal) to smelt 3 raw_iron", p.plan(m("iron_ingot"), 3, counts("raw_iron", 3)).error());
    }

    @Test
    void smeltingErrors() {
        assertEquals("need 3 of one kind of sand to smelt", p.plan(m("glass"), 3, counts("sand", 2, "red_sand", 2, "coal", 1)).error());
        assertEquals("missing 2 sand (or similar) to smelt into glass", p.plan(m("glass"), 3, counts("sand", 1, "coal", 1)).error());
        assertEquals("no fuel (coal or charcoal) to smelt 3 red_sand", p.plan(m("glass"), 3, counts("sand", 2, "red_sand", 3)).error());
        assertEquals("there is no crafting recipe for bedrock", p.plan(m("bedrock"), 1, counts()).error());
        Plan s = p.planSmelt(m("glass"), 2, counts("red_sand", 2, "coal", 1));
        assertEquals(List.of(new Smelt("minecraft:glass", m("glass"), 2, m("red_sand"), 2, m("coal"), 1)), s.steps());
        assertEquals(CraftPlanner.NO_SMELTING, p.planSmelt(m("torch"), 1, counts("coal", 5)).error());
        assertEquals("error: can't smelt 1 torch - no recipe", CraftTexts.cantSmelt("1 torch", CraftPlanner.NO_SMELTING));
    }

    @Test
    void deeperThanTwoLevels() {
        FakeRecipes f = world();
        f.shapeless("oak_log_from_seed", "oak_log", 1, alts("log_seed"));
        CraftPlanner deep = new CraftPlanner(f);
        assertTrue(deep.plan(m("torch"), 4, counts("oak_log", 1, "coal", 1)).ok(), "torch <- stick <- planks <- logs on hand");
        Plan plan = deep.plan(m("torch"), 4, counts("log_seed", 1, "coal", 1));
        assertTrue(plan.ok(), "package D: a third level (the seed) is in reach now: " + plan.error());
        assertEquals("1 oak_log -> 2 oak_planks -> 1 stick -> 4 torch", CraftPlanner.describeSteps(plan.steps()), "bottom-up");
    }

    @Test
    void countsThreadAcrossTargets() {
        AllPlan r = p.planAll(List.of(new Target(m("torch"), 4), new Target(m("stick"), 2)), counts("oak_log", 1, "coal", 1));
        assertTrue(r.ok(), r.error());
        assertEquals("2 oak_planks -> 1 stick -> 4 torch -> 2 stick", CraftPlanner.describeSteps(r.steps()));
        assertEquals(0, r.counts().get(m("oak_planks")), "the second target used the first one's leftover planks");
        assertEquals(7, r.counts().get(m("stick")));
        r = p.planAll(List.of(new Target(m("torch"), 4), new Target(m("crafting_table"), 1)), counts("oak_log", 1, "coal", 1));
        assertEquals("crafting_table: missing 2 oak_planks (or similar)", r.error());
    }

    @Test
    void repairRecipesAreSkippedAndRecipesUsing() {
        assertEquals(1, p.craftingRecipesFor(m("iron_pickaxe")).size());
        assertEquals("minecraft:iron_pickaxe", p.craftingRecipesFor(m("iron_pickaxe")).get(0).recipeId());
        List<Crafter.Recipe> using = p.craftingRecipesUsing("mysticalagriculture:inferium_essence");
        assertEquals(1, using.size());
        assertEquals(List.of(new Crafter.Need(List.of("mysticalagriculture:inferium_essence"), 9)), using.get(0).needs());
        assertEquals("mysticalagriculture:inferium_block", using.get(0).output());
        assertTrue(p.craftingRecipesUsing("minecraft:nope").isEmpty());
        assertEquals(List.of(new Crafter.Need(alts("iron_ingot"), 3), new Crafter.Need(alts("stick"), 2)),
                p.craftingRecipesFor(m("iron_pickaxe")).get(0).needs(), "merged by ingredient, in order");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // names, targets, recipe info
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void resolveItemOnGenericNames() {
        assertEquals(m("oak_planks"), p.resolveItem("planks", counts()), "a tie: the first");
        assertEquals(m("spruce_planks"), p.resolveItem("planks", counts("spruce_log", 1)), "the kind the logs make");
        assertEquals(m("spruce_planks"), p.resolveItem("Planks ", counts("spruce_planks", 3)), "the kind it holds");
        assertEquals(m("stick"), p.resolveItem("sticks", counts()));
        assertEquals(m("torch"), p.resolveItem("torch", counts()));
        assertEquals("refinedstorage:silicon", p.resolveItem("silicon", counts()));
        assertEquals("refinedstorage:basic_processor", p.resolveItem("refinedstorage:basic_processor", counts()));
        assertNull(p.resolveItem("minecraft:nope", counts()));
        assertNull(p.resolveItem("unobtainium", counts()));
        assertNull(p.resolveItem("  ", counts()));
    }

    @Test
    void parseCraftTargetsWithSetsAndCounts() {
        Targets t = p.parseCraftTargets("torch 32 bread 16", counts());
        assertEquals(List.of(new Target(m("torch"), 32), new Target(m("bread"), 16)), t.list());
        t = p.parseCraftTargets("planks 8, stone pickaxe", counts());
        assertEquals(List.of(new Target(m("oak_planks"), 8), new Target(m("stone_pickaxe"), 1)), t.list());
        t = p.parseCraftTargets("iron tools", counts());
        assertEquals(List.of(new Target(m("iron_sword"), 1), new Target(m("iron_pickaxe"), 1), new Target(m("iron_axe"), 1)), t.list());
        t = p.parseCraftTargets("iron armor 2", counts());
        assertEquals(List.of(new Target(m("iron_helmet"), 2), new Target(m("iron_boots"), 2)), t.list(), "vanilla before othermod");
        assertEquals(List.of("leafscopperbackport:copper_helmet"), p.expandSet("copper", CraftPlanner.ARMOR_PIECES), "the shortest modded name");
        assertEquals("I don't know any craftable gold armor", p.parseCraftTargets("gold armor", counts()).error());
        assertEquals("I don't know an item called unobtainium", p.parseCraftTargets("torch 2, unobtainium 3", counts()).error());
        assertTrue(p.parseCraftTargets(" , ", counts()).list().isEmpty());
        assertEquals("1 torch, 8 oak_planks", CraftPlanner.label(List.of(new Target(m("torch"), 1), new Target(m("oak_planks"), 8))));
    }

    @Test
    void recipeInfo() {
        assertEquals("[minecraft:crafting] makes 4: 1x minecraft:coal (or 1 alternatives), 1x minecraft:stick", p.recipeInfo("torch", counts()));
        assertEquals("[minecraft:crafting] makes 4: 2x minecraft:oak_planks (or 1 alternatives)", p.recipeInfo("sticks please", counts()));
        assertEquals("[minecraft:smelting] makes 1: 1x minecraft:sand (or 1 alternatives)", p.recipeInfo("glass", counts()));
        assertEquals("[minecraft:crafting] makes 9: 1x minecraft:iron_block", p.recipeInfo("iron_ingot", counts()));
        assertEquals("none: no recipes for minecraft:bedrock", p.recipeInfo("bedrock", counts()));
        assertEquals("error: I don't know an item called unobtainium", p.recipeInfo("unobtainium", counts()));
    }

    @Test
    void verbTexts() {
        assertEquals(new CraftTexts.NameCount("iron_ingot", "iron ingot", 9), CraftTexts.parseNameCount(" Iron Ingot 9", 1));
        assertEquals(64, CraftTexts.parseNameCount("coal", CraftTexts.GET_DEFAULT).n());
        assertNull(CraftTexts.parseNameCount("  ", 1));
        assertEquals("error: none of my chests or the RS network has coal (as far as I know - \"scan base\" / \"rs\")", CraftTexts.getNone(m("coal"), true));
        assertEquals("getting 10 coal (all I know of)", CraftTexts.getSeqLabel(m("coal"), 64, 10));
        assertEquals("getting materials from 2 place(s), then crafting 1 refinedstorage:basic_processor", CraftTexts.craftSeqLabel(2, "1 refinedstorage:basic_processor"));
        assertEquals("error: can't craft 1 torch - torch: missing 1 coal (or similar) (even counting the chests I know and the RS network)",
                CraftTexts.craftEvenWithStorage("1 torch", p.planAll(List.of(new Target(m("torch"), 1)), counts()).error(), true));
        assertEquals(Map.of(m("iron_ingot"), 3), CraftTexts.fromStorage(counts("iron_ingot", 5), counts("iron_ingot", 1), counts("iron_ingot", 1)));
        CraftTexts.Kit k = CraftTexts.kit(p, "iron", counts("iron_sword", 1));
        assertEquals(List.of(m("iron_helmet"), m("iron_boots"), m("iron_sword"), m("iron_pickaxe"), m("iron_axe")), k.all());
        assertEquals(List.of(m("iron_helmet"), m("iron_boots"), m("iron_pickaxe"), m("iron_axe")), k.missing());
    }
}
