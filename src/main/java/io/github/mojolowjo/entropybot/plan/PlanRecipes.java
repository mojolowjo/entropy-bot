package io.github.mojolowjo.entropybot.plan;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B3: the recipes the planner reasons with (in its own words, see {@link PlanFacts#canon}), read from the game (rm-final):
 * the recipe manager's crafting recipes, its furnace recipes, and the loot tables of the ore blocks, through
 * {@link ItemWorth}. It only needs to know what a {@code craft} step consumes and whether it needs a table; the craft verb
 * itself (CraftPlanner) still does the "how" at run time. A word the game has no data for (no data loaded, a client with no
 * loot tables) has no recipe, no smelting and no ore: the planner then says "no action gives it". Pure.
 */
public final class PlanRecipes {
    private PlanRecipes() {}

    /** out: items one craft gives; in: what one craft takes; table: a 3x3 recipe. */
    public record Recipe(String item, int out, Map<String, Integer> in, boolean table) {}

    /** An ore's family word (the owner's "mine iron_ore": the block's own name) and the pickaxe tier its drop needs. */
    public record Ore(String family, int pick) {}

    /** The item ids a planner word stands for, best first (planks: every planks item; fuel: every coal; else the item itself). */
    private static List<String> idsFor(String word) {
        return switch (word) {
            case PlanFacts.PLANKS -> ItemWorth.withTag("planks");
            case "fuel" -> ItemWorth.withTag("coals");
            default -> List.of(id(word));
        };
    }

    private static String id(String word) { return word.indexOf(':') < 0 ? "minecraft:" + word : word; }

    /** The cheapest crafting recipe of the word: the most items out per ingredient; furnace recipes are no crafts. Null: none. */
    public static Recipe of(String item) {
        for (String id : idsFor(item)) {
            String smelt = ItemWorth.smeltInput(id);
            ItemWorth.Recipe best = null;
            for (ItemWorth.Recipe r : ItemWorth.recipes(id)) {
                if (r.inputs().isEmpty() || r.inputs().get(0).equals(smelt)) continue;
                Map<String, Integer> in = counts(r);
                if (in.containsKey(item)) continue;           // a recipe that takes the item itself is a packing, not a making
                if (best == null || (double) r.outCount() / r.inputs().size() > (double) best.outCount() / best.inputs().size()) best = r;
            }
            if (best != null) return new Recipe(item, best.outCount(), counts(best), ItemWorth.needsTable(id));
        }
        return null;
    }

    private static Map<String, Integer> counts(ItemWorth.Recipe r) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String in : r.inputs()) m.merge(PlanFacts.canon(in), 1, Integer::sum);
        return m;
    }

    /** The word a furnace makes the item from (iron_ingot: raw_iron), or null. */
    public static String smelted(String item) {
        for (String id : idsFor(item)) {
            String raw = ItemWorth.smeltInput(id);
            if (raw != null) return PlanFacts.canon(raw);
        }
        return null;
    }

    /** The ore that drops the word (raw_iron: iron, pickaxe tier 2; fuel: coal, tier 1), or null: no ore of the game drops it. */
    public static Ore ore(String item) {
        for (String id : idsFor(item)) {
            String block = ItemWorth.oreDropping(id);
            if (block == null) continue;
            int tier = MiningTiers.blockTierOf(block);
            if (tier < 0) continue;
            String path = block.substring(block.indexOf(':') + 1).replaceFirst("^(deepslate|nether)_", "").replaceFirst("_ore$", "");
            return new Ore(path, tier);
        }
        return null;
    }
}