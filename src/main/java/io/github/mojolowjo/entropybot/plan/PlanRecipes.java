package io.github.mojolowjo.entropybot.plan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B3: the few vanilla recipes the planner reasons with (in its own words, see {@link PlanFacts#canon}). It only needs
 * to know what a {@code craft} step consumes and whether it needs a table; the craft verb itself (CraftPlanner) still
 * does the "how" at run time, so modded recipes stay its job. Pure.
 */
public final class PlanRecipes {
    private PlanRecipes() {}

    /** out: items one craft gives; in: what one craft takes; table: a 3x3 recipe. */
    public record Recipe(String item, int out, Map<String, Integer> in, boolean table) {}

    /** smelted item -> raw input. */
    public static final Map<String, String> SMELT = Map.of(
            "iron_ingot", "raw_iron", "gold_ingot", "raw_gold", "copper_ingot", "raw_copper", "glass", "sand", "stone", "cobblestone", "charcoal", "log");

    /** ore drop -> {family, pickaxe tier needed}. */
    public static final Map<String, Object[]> ORES = Map.of(
            "raw_iron", new Object[]{"iron", 2}, "raw_copper", new Object[]{"copper", 2}, "raw_gold", new Object[]{"gold", 3},
            "fuel", new Object[]{"coal", 1}, "diamond", new Object[]{"diamond", 3}, "redstone", new Object[]{"redstone", 3},
            "lapis_lazuli", new Object[]{"lapis", 2}, "emerald", new Object[]{"emerald", 3});

    static final Map<String, Recipe> R = new LinkedHashMap<>();

    private static void r(String item, int out, boolean table, Object... in) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < in.length; i += 2) m.put((String) in[i], (Integer) in[i + 1]);
        R.put(item, new Recipe(item, out, m, table));
    }

    static {
        r("planks", 4, false, "log", 1);
        r("stick", 4, false, "planks", 2);
        r("crafting_table", 1, false, "planks", 4);
        r("torch", 4, false, "stick", 1, "fuel", 1);
        r("chest", 1, true, "planks", 8);
        r("furnace", 1, true, "cobblestone", 8);
        r("white_bed", 1, true, "white_wool", 3, "planks", 3);
        r("bucket", 1, true, "iron_ingot", 3);
        r("shield", 1, true, "planks", 6, "iron_ingot", 1);
        String[][] mats = {{"wooden", "planks"}, {"stone", "cobblestone"}, {"iron", "iron_ingot"}, {"diamond", "diamond"}};
        for (String[] m : mats) {
            r(m[0] + "_pickaxe", 1, true, m[1], 3, "stick", 2);
            r(m[0] + "_axe", 1, true, m[1], 3, "stick", 2);
            r(m[0] + "_shovel", 1, true, m[1], 1, "stick", 2);
            r(m[0] + "_sword", 1, true, m[1], 2, "stick", 1);
            r(m[0] + "_hoe", 1, true, m[1], 2, "stick", 2);
        }
        r("iron_helmet", 1, true, "iron_ingot", 5);
        r("iron_chestplate", 1, true, "iron_ingot", 8);
        r("iron_leggings", 1, true, "iron_ingot", 7);
        r("iron_boots", 1, true, "iron_ingot", 4);
    }

    public static Recipe of(String item) { return R.get(item); }

    public static Map<String, Recipe> all() { return java.util.Collections.unmodifiableMap(R); }
}
