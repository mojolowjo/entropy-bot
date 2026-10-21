package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND4 C: Costs.blocked from the mining tiers of the blocks (tag data) and the recipes, no names in the code. */
class MiningTiersTest {
    /** Block tiers as the tags would give them: cobble needs wooden (1), iron ore stone (2), diamond ore iron (3), obsidian diamond (4). */
    final Map<String, Integer> blocks = new HashMap<>(Map.of(
            "minecraft:cobblestone", 1, "minecraft:iron_ore", 2, "minecraft:diamond_ore", 3, "minecraft:obsidian", 4,
            "ruby:ruby_ore", 3, "minecraft:oak_log", 0, "minecraft:dirt", 0));
    /** What blocks drop: topaz from a tier-3 block, coal from a tier-1 block (no recipe makes either). */
    final Map<String, Integer> drops = new HashMap<>(Map.of("gem:topaz", 3, "minecraft:coal", 1, "tier:far_ore", 5));
    final MiningTiers.Source src = new MiningTiers.Source() {
        public int blockTier(String id) { return blocks.getOrDefault(id, -1); }
        public int dropTier(String id) { return drops.getOrDefault(id, -1); }
    };
    static final Set<String> STICK = Set.of("minecraft:stick");

    static TechTree.Recipe r(String out, String... ins) {
        List<Set<String>> slots = new ArrayList<>();
        for (String i : ins) slots.add(Set.of(i));
        return new TechTree.Recipe(out, slots);
    }

    final TechTree tree = new TechTree(List.of(
            r("minecraft:stick", "minecraft:oak_log"),
            r("minecraft:stone_pickaxe", "minecraft:cobblestone", "minecraft:stick"),
            r("minecraft:iron_ingot", "minecraft:iron_ore"),
            r("minecraft:iron_pickaxe", "minecraft:iron_ingot", "minecraft:stick"),
            r("minecraft:diamond", "minecraft:diamond_ore"),
            r("minecraft:diamond_pickaxe", "minecraft:diamond", "minecraft:stick"),
            r("minecraft:obsidian_lamp", "minecraft:obsidian"),
            r("ruby:ruby", "ruby:ruby_ore"),
            r("ruby:ruby_sword", "ruby:ruby", "minecraft:stick"),
            r("minecraft:dirt_brick", "minecraft:dirt"),
            r("gem:topaz_helmet", "gem:topaz"),
            r("minecraft:torch", "minecraft:coal", "minecraft:stick"),
            r("tier:far_tool", "tier:far_ore")));

    MiningTiers.Result at(int pick) { return MiningTiers.blocked(tree, src, STICK, pick, 50); }

    @Test void needsComeFromTheBlocksAndTheRecipes() {
        Map<String, Integer> n = MiningTiers.needs(tree, src);
        assertEquals(1, n.get("minecraft:stone_pickaxe"));
        assertEquals(2, n.get("minecraft:iron_pickaxe"));      // iron ore needs a stone pickaxe
        assertEquals(3, n.get("minecraft:diamond_pickaxe"));   // diamond ore needs an iron pickaxe
        assertEquals(4, n.get("minecraft:obsidian_lamp"));
        assertEquals(0, n.get("minecraft:stick"));
    }

    @Test void stonePickBlocksIronTierIdeasIronPickDoesNot() {
        var stone = at(2);
        assertTrue(stone.names().contains("diamond"), stone.names().toString());
        assertTrue(stone.names().contains("obsidian_lamp"));
        assertFalse(stone.names().contains("iron_ingot"));
        var iron = at(3);
        assertFalse(iron.names().contains("diamond"), iron.names().toString());
        assertTrue(iron.names().contains("obsidian_lamp"));      // a diamond pickaxe still holds obsidian back
        assertTrue(iron.share() < stone.share());
        assertEquals(0, at(5).blocked());
    }

    @Test void aModdedBlockWithTheTagCounts() {
        assertTrue(at(2).names().contains("ruby:ruby"));
        assertFalse(at(3).names().contains("ruby:ruby"));
    }

    @Test void aBlockWithNoTagIsNotBlocked() {
        assertFalse(at(0).names().contains("dirt_brick"));
        assertFalse(at(0).names().contains("stick"));
        assertTrue(at(0).names().contains("stone_pickaxe"));     // cobblestone needs a wooden pickaxe at least
        assertFalse(at(1).names().contains("stone_pickaxe"));
    }

    @Test void theTermsNameTheCountAndTheIdeas() {
        var r = at(2);
        assertTrue(r.terms().startsWith(r.blocked() + " of the top " + r.of()), r.terms());
        assertEquals((double) r.blocked() / r.of(), r.share(), 1e-9);
    }

    @Test void ownerOreGoalCountsOnlyUnderStone() {
        var r = at(1);
        assertTrue(Costs.blocked(r, 1, true) > r.share());
        assertEquals(Costs.blocked(at(3), 3, true), at(3).share(), 1e-9);
    }

    @Test void itemsDroppedByABlockNeedThatBlocksTier() {
        assertEquals(3, MiningTiers.needs(tree, src).get("gem:topaz_helmet"));
        assertTrue(at(2).names().contains("gem:topaz_helmet") || at(2).names().contains("topaz_helmet") || !tree.top(STICK, 50).stream().anyMatch(i -> i.item().equals("gem:topaz_helmet")));
        assertEquals(1, MiningTiers.needs(tree, src).get("minecraft:torch"));
    }

    @Test void noPickaxeIsTierZeroAndNeverNegative() {
        assertEquals(MiningTiers.blocked(tree, src, STICK, 0, 50).blocked(), MiningTiers.blocked(tree, src, STICK, -3, 50).blocked());
        assertTrue(at(0).names().contains("iron_ingot"));
    }

    @Test void aModdedTierAboveDiamondIsReadFromItsOwnTags() {
        assertEquals(List.of(1, 1, 2, 3, 4, 5), MiningTiers.rankTiers(List.of(10, 10, 6, 3, 1, 0)));
        assertTrue(MiningTiers.blocked(tree, src, STICK, 4, 50).names().contains("tier:far_tool"));
        assertFalse(MiningTiers.blocked(tree, src, STICK, 5, 50).names().contains("tier:far_tool"));
    }

    @Test void freshVersusStoneToolsAgainstTheOldTable() {
        double fresh = Costs.blocked(MiningTiers.blocked(tree, src, STICK, 0, 8), 0, false);
        double stone = Costs.blocked(MiningTiers.blocked(tree, src, Set.of("minecraft:stick", "minecraft:stone_pickaxe", "minecraft:cobblestone"), 2, 8), 2, false);
        double oldFresh = Costs.blocked(0, false, 0, 0, false), oldStone = Costs.blocked(2, true, 5, 16, false);
        assertTrue(fresh < 1.0, "fresh " + fresh + " (old table " + oldFresh + ")");
        assertTrue(stone < fresh, "stone " + stone + " fresh " + fresh + " (old table " + oldStone + ")");
    }
}

