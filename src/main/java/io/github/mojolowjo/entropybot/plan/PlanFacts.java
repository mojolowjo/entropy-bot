package io.github.mojolowjo.entropybot.plan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * B3 (docs/BRAIN_PLAN.md 6): what the planner starts from. bag and stock: item -> count in the planner's words
 * ({@link #canon}: any log is "log", any planks "planks", coal and charcoal "fuel", edible items "food"); flags: what is
 * true in the world now ({@code table}, {@code furnace} within reach, {@code pick1..4} the best pickaxe tier carried,
 * {@code trees}, {@code stone}, {@code mine}, {@code cave}, {@code ore:<family>} seen near, {@code spot:table},
 * {@code spot:furnace}, {@code quarry}); feet, table, furnace: the bot's spot and free cells next to it (for the place
 * steps, as bootstrap does); dir: the quarry's direction. Pure: JUnit builds fake ones.
 */
public final class PlanFacts {
    public final Map<String, Integer> bag = new LinkedHashMap<>();
    public final Map<String, Integer> stock = new LinkedHashMap<>();
    public final Set<String> flags = new LinkedHashSet<>();
    public int[] feet = {0, 64, 0}, table, furnace, dir = {1, 0};

    /** The planner's word for an item id ("minecraft:oak_log" -> "log"). */
    public static String canon(String id) {
        String p = id == null ? "" : id.toLowerCase(java.util.Locale.ROOT).trim();
        if (p.startsWith("minecraft:")) p = p.substring(10);
        if (p.matches("^(stripped_)?[a-z_]+_(log|wood|stem|hyphae)$") || p.equals("logs") || p.equals("wood")) return "log";
        if (p.endsWith("_planks")) return "planks";
        if (p.equals("coal") || p.equals("charcoal")) return "fuel";
        if (p.equals("bed")) return "white_bed";
        if (p.equals("cobbled_deepslate") || p.equals("blackstone")) return "cobblestone";
        return p;
    }

    /** Adds a whole inventory (namespaced ids); foodIds: the edible ones (they also count as "food"). */
    public PlanFacts addBag(Map<String, Integer> inv, Set<String> foodIds) {
        add(bag, inv, foodIds);
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            String p = canon(e.getKey());
            if (e.getValue() <= 0) continue;
            int t = p.endsWith("_pickaxe") ? tier(p.substring(0, p.length() - 8)) : 0;
            for (int i = 1; i <= t; i++) flags.add("pick" + i);
        }
        return this;
    }

    public PlanFacts addStock(Map<String, Integer> totals, Set<String> foodIds) {
        add(stock, totals, foodIds);
        return this;
    }

    private static void add(Map<String, Integer> to, Map<String, Integer> from, Set<String> foodIds) {
        for (Map.Entry<String, Integer> e : from.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            to.merge(canon(e.getKey()), e.getValue(), Integer::sum);
            if (foodIds != null && foodIds.contains(e.getKey())) to.merge("food", e.getValue(), Integer::sum);
        }
    }

    /** Pickaxe material tier: wooden/golden 1, stone/copper 2, iron 3, diamond/netherite 4. */
    public static int tier(String material) {
        return switch (material) {
            case "wooden", "golden" -> 1;
            case "stone", "copper" -> 2;
            case "iron" -> 3;
            case "diamond", "netherite" -> 4;
            default -> 0;
        };
    }

    public PlanFacts flag(String... f) {
        for (String s : f) flags.add(s);
        return this;
    }

    public PlanFacts bag(String item, int n) {
        bag.merge(canon(item), n, Integer::sum);
        if (canon(item).endsWith("_pickaxe")) for (int i = 1; i <= tier(canon(item).replace("_pickaxe", "")); i++) flags.add("pick" + i);
        return this;
    }

    public PlanFacts stock(String item, int n) {
        stock.merge(canon(item), n, Integer::sum);
        return this;
    }

    /** One line for the document and the logs. */
    public String summary() {
        return "bag " + bag + ", stock " + stock.size() + " kinds, flags " + flags;
    }
}
