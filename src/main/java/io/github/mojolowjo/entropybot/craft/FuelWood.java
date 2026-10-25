package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 0.39.z51 item 4 (run L 16:38:25: a boat crafted from 5 planks and burned where 2 planks did): the wood a fuel consumes,
 * in planks, from the recipe data alone. A plank (the game's planks tag) is 1; a log (the logs tag) is the planks its own
 * recipe makes from it (4 in vanilla); any other item is the cheapest of its recipes' inputs' wood over the recipe's output
 * count (a stick 0.5, a boat 5, charcoal one log); an item no recipe makes from wood is 0. A fuel option's wood is the
 * pieces it burns for {@code n} smelts (the game's burn ticks, Tuning craftJob.smeltTicks a smelt) times its wood. No
 * fuel names, no boat case. Pure over {@link ItemWorth} (JUnit: Z51FuelScenarioTest). Loader notes: none.
 */
public final class FuelWood {
    private FuelWood() {}

    static final int DEPTH = 4; // structural: recipe depth searched (a boat: planks <- log)

    /** The planks of wood one {@code id} consumes (0: none, or not known from the recipes). */
    public static double planks(String id) {
        if (id == null) return 0;
        synchronized (CACHE) {
            if (cacheVersion != ItemWorth.version()) { CACHE.clear(); cacheVersion = ItemWorth.version(); }
            Double v = CACHE.get(id);
            if (v != null) return v;
        }
        double v = planks(id, DEPTH, new HashSet<>());
        synchronized (CACHE) { CACHE.put(id, v); }
        return v;
    }

    /** Per item data version (ItemWorth.use clears it): the recipe walk runs once an item. */
    private static final java.util.Map<String, Double> CACHE = new java.util.HashMap<>();
    private static long cacheVersion = Long.MIN_VALUE;

    private static double planks(String id, int depth, Set<String> path) {
        if (id == null) return 0;
        if (ItemWorth.isIn(id, "minecraft:planks") || ItemWorth.plank(id)) return 1;   // structural: the unit itself
        if (ItemWorth.isIn(id, "minecraft:logs")) return logPlanks(id);
        if (depth <= 0 || !path.add(id)) return 0;
        double best = Double.NaN;
        for (ItemWorth.Recipe r : ItemWorth.recipes(id)) {
            double sum = 0;
            for (List<String> opts : r.options()) {
                double low = Double.NaN;
                for (String o : opts) {
                    double w = planks(o, depth - 1, path);
                    if (Double.isNaN(low) || w < low) low = w;
                }
                sum += Double.isNaN(low) ? 0 : low;
            }
            double each = sum / Math.max(1, r.outCount());
            if (Double.isNaN(best) || each < best) best = each;
        }
        path.remove(id);
        return Double.isNaN(best) ? 0 : best;
    }

    /** The planks one log makes: the most any planks recipe gives from it alone (the recipe data); 1 when none is known. */
    static double logPlanks(String log) {
        double most = 0;
        for (String p : ItemWorth.itemsIn("minecraft:planks"))
            for (ItemWorth.Recipe r : ItemWorth.recipes(p, ItemWorth.CRAFT))
                if (r.options().size() == 1 && r.options().get(0).contains(log)) most = Math.max(most, r.outCount());
        return most > 0 ? most : 1;   // structural: a log with no planks recipe is one piece of wood
    }

    /** The pieces of {@code id} that burn {@code n} smelts (0: not a fuel). */
    public static int pieces(String id, int n) {
        int burn = CraftPlanner.burnTicks(ItemWorth.fuels(), id);
        return burn <= 0 ? 0 : (int) Math.ceil((double) n * CraftJob.smeltTicks() / burn);
    }

    /** The planks of wood burning {@code n} smelts on {@code id} consumes (+inf: not a fuel). */
    public static double woodFor(String id, int n) {
        int k = pieces(id, n);
        return k <= 0 ? Double.POSITIVE_INFINITY : k * planks(id);
    }
}
