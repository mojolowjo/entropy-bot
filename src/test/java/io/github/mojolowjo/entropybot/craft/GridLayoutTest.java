package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.craft.Crafter.Craft;
import io.github.mojolowjo.entropybot.craft.Crafter.Smelt;
import io.github.mojolowjo.entropybot.craft.GridLayout.Layout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.mojolowjo.entropybot.craft.CraftPlannerTest.counts;
import static io.github.mojolowjo.entropybot.craft.FakeRecipes.alts;
import static io.github.mojolowjo.entropybot.craft.FakeRecipes.m;
import static org.junit.jupiter.api.Assertions.*;

class GridLayoutTest {
    final FakeRecipes f = CraftPlannerTest.world();

    RecipeData r(String id) {
        return f.recipe(m(id));
    }

    @Test
    void shaped2x2TakesTheKindThereIsMostOf() {
        Map<String, Integer> c = counts("oak_planks", 2, "spruce_planks", 3);
        Layout l = GridLayout.layout(r("crafting_table"), GridLayout.INVENTORY, c);
        assertTrue(l.ok());
        assertEquals(Map.of(0, m("spruce_planks"), 1, m("oak_planks"), 2, m("spruce_planks"), 3, m("oak_planks")), l.slots());
        assertEquals(counts("oak_planks", 2, "spruce_planks", 3), c, "the caller's counts stay");
        l = GridLayout.layout(r("crafting_table"), GridLayout.TABLE, c);
        assertEquals(List.of(0, 1, 3, 4), List.copyOf(l.slots().keySet()), "top-left of the 3x3");
        assertEquals(Map.of(0, m("coal"), 2, m("stick")), GridLayout.layout(r("torch"), 2, counts("stick", 1, "coal", 1)).slots());
        assertEquals(Map.of(0, m("charcoal"), 3, m("stick")), GridLayout.layout(r("torch"), 3, counts("stick", 1, "coal", 1, "charcoal", 4)).slots());
        assertEquals(1, GridLayout.menuSlot(0));
    }

    @Test
    void shaped3x3NeedsTheTable() {
        Map<String, Integer> c = counts("cobblestone", 3, "stick", 2);
        Layout l = GridLayout.layout(r("stone_pickaxe"), GridLayout.INVENTORY, c);
        assertFalse(l.ok());
        assertEquals("stone_pickaxe needs a crafting table", l.error());
        l = GridLayout.layout(r("stone_pickaxe"), GridLayout.TABLE, c);
        assertEquals(Map.of(0, m("cobblestone"), 1, m("cobblestone"), 2, m("cobblestone"), 4, m("stick"), 7, m("stick")), l.slots());
        l = GridLayout.layout(r("stone_pickaxe"), GridLayout.TABLE, counts("cobblestone", 2, "stick", 2));
        assertEquals(GridLayout.RAN_OUT, l.error());
        assertEquals("cobblestone", l.missing());
        assertEquals(Map.of(0, m("wheat"), 1, m("wheat"), 2, m("wheat")), GridLayout.layout(r("bread"), 3, counts("wheat", 3)).slots());
    }

    @Test
    void shapelessFillsInOrder() {
        assertEquals(Map.of(0, m("oak_wood")), GridLayout.layout(r("oak_planks"), 2, counts("oak_log", 1, "oak_wood", 5)).slots());
        RecipeData proc = f.recipe("refinedstorage:raw_basic_processor");
        assertEquals(Map.of(0, m("iron_ingot"), 1, "refinedstorage:silicon", 2, "refinedstorage:processor_binding"),
                GridLayout.layout(proc, 2, counts("iron_ingot", 1, "refinedstorage:silicon", 1, "refinedstorage:processor_binding", 1)).slots());
        RecipeData five = RecipeData.shapeless("x:five", "x:five", 1, List.of(alts("dirt"), alts("dirt"), alts("dirt"), alts("dirt"), alts("dirt")));
        assertEquals("x:five needs a crafting table", GridLayout.layout(five, 2, counts("dirt", 5)).error());
        assertTrue(five.needsTable());
        assertEquals(List.of(0, 1, 2, 3, 4), List.copyOf(GridLayout.layout(five, 3, counts("dirt", 5)).slots().keySet()));
        assertEquals("not a crafting recipe", GridLayout.layout(f.recipe(m("glass")), 3, counts("sand", 1)).error());
    }

    @Test
    void craftJobDecisionsAndTexts() {
        List<Crafter.Step> steps = List.of(new Craft("minecraft:stick", m("stick"), 4, 1, false),
                new Smelt("minecraft:glass", m("glass"), 2, m("sand"), 2, m("coal"), 1),
                new Craft("a", m("torch"), 4, 1, false), new Craft("b", m("stone_pickaxe"), 1, 1, true));
        List<CraftJob.Segment> seg = CraftJob.segments(steps);
        assertEquals(3, seg.size());
        assertEquals(1, seg.get(0).crafts().size());
        assertTrue(seg.get(1).isSmelt());
        assertEquals(2, seg.get(2).crafts().size());
        assertEquals("started: crafting 4 stick -> 2 glass -> 4 torch -> 1 stone_pickaxe", CraftJob.started(steps));
        assertEquals("crafting stick 4/8 (step 2/3)", CraftJob.progress(m("stick"), 4, 8, 1, 3));
        assertEquals("crafting stick 4/4", CraftJob.progress(m("stick"), 4, 4, 0, 1));
        assertEquals(8, CraftJob.madeAfterTake(4, 4));
        assertTrue(CraftJob.stepDone(8, 5));
        assertEquals("error: the grid did not make stick", CraftJob.gridDidNotMake(0, 0, m("stick")));
        assertEquals("partial: could not craft torch - ingredients ran out [no coal]", CraftJob.couldNotCraft(1, 0, m("torch"), "no coal"));
        assertEquals(CraftJob.FillFailed.GO_TO_TABLE, CraftJob.onFillFailed(true, false));
        assertEquals(CraftJob.FillFailed.GIVE_UP, CraftJob.onFillFailed(true, true));
        assertEquals(CraftJob.FillFailed.GIVE_UP, CraftJob.onFillFailed(false, false));
        assertEquals("error: stone_pickaxe needs a crafting table and there is none near me or the base", CraftJob.noTable(m("stone_pickaxe")));
        assertEquals(440, CraftJob.smeltWaitTicks(2));
        assertEquals("crafting 2 glass - waiting for the furnace (glass, 17 s)", CraftJob.smeltWaitStatus("crafting 2 glass", m("glass"), 440, 100));
        assertEquals(CraftJob.SmeltCheck.RETRY, CraftJob.smeltCheck(1, 2, 1));
        assertEquals(CraftJob.SmeltCheck.GIVE_UP, CraftJob.smeltCheck(1, 2, 4));
        assertEquals(CraftJob.SmeltCheck.DONE, CraftJob.smeltCheck(2, 2, 4));
        assertEquals("the furnace at 1 2 3 made only 1 of 2 glass", CraftJob.furnaceShort("1 2 3", 1, 2, m("glass")));
        assertEquals("error: can't craft 2 glass - smelting glass needs a furnace, and there is none near me or the base", CraftTexts.craftNoFurnace("2 glass", m("glass")));
    }
}
