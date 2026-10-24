package io.github.mojolowjo.entropybot.aa.brain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The strip order's deposit leg (owner 2026-10-22, "let's do the deposit leg next"). Pure (JUnit: DepositRuleTest).
 *
 * <p>The chest: the nearest KNOWN container of the station memory (the bot's own placed chests and Baritone's scan: the
 * same three-state fact as the crafting stations), nearest by Baritone's path cost when several (a chest with no path is
 * passed over); none known (or none with a path) -> a chest from the bag; else one crafted (the craft job: 8 planks).
 * The new one is placed near the tunnel start. Not loaded -> wait (rule 15), never "none".
 *
 * <p>The kept set, from item data only (no name list): tools (the tool tags, or any damageable item), food (the food
 * component), station items (an item whose block opens a menu: table, furnace, chest), torches (an item whose block is a
 * torch block). Everything else in the bag goes in: the strip's ores and every drop it picked up on the way.
 */
public final class DepositRule {
    private DepositRule() {}

    /** One bag stack's item facts (read from the game's item data by the caller). */
    public record Item(String id, int count, boolean tool, boolean food, boolean station, boolean torch) {}

    /** Kept in the bag: tools, food, station items, torches. */
    public static boolean kept(Item i) {
        return i.tool() || i.food() || i.station() || i.torch();
    }

    /** What goes into the chest: id -> count, the kept set left out. */
    public static Map<String, Integer> toPut(List<Item> bag) {
        Map<String, Integer> out = new TreeMap<>();
        for (Item i : bag) if (i != null && i.count() > 0 && !kept(i)) out.merge(i.id(), i.count(), Integer::sum);
        return out;
    }

    public enum How { KNOWN, BAG, CRAFT, WAIT, FAIL }

    /** The chest choice: KNOWN at {@code at}; BAG / CRAFT a new one; WAIT (not loaded); FAIL with {@code why}. */
    public record Choice(How how, int[] at, String why) {}

    /**
     * @param known     the known containers nearest-first by the line (null = the fact is not loaded yet)
     * @param pathCost  Baritone's complete path cost in seconds to a cell, null = no path, NaN = still computed (0.39.z41)
     * @param chestInBag a chest item is in the bag
     * @param canCraft   the bag holds 8 planks (the chest recipe's input, from the recipe data)
     */
    public static Choice choose(List<int[]> known, Function<int[], Double> pathCost, boolean chestInBag, boolean canCraft) {
        return choose(known, pathCost, k -> 0.0, chestInBag, canCraft);
    }

    /**
     * main-stations (0.39.z35): the same with each chest's price bonus in seconds ({@code bonusS}: the main chest's
     * station.mainBonusS): a price, not a rule (a main far beyond the bonus loses to a near one).
     */
    public static Choice choose(List<int[]> known, Function<int[], Double> pathCost, Function<int[], Double> bonusS, boolean chestInBag, boolean canCraft) {
        if (known == null) return new Choice(How.WAIT, null, "the chest memory is not loaded yet");
        int[] best = null;
        double bestCost = Double.MAX_VALUE, bestRaw = 0, bestBonus = 0;
        int noPath = 0, computing = 0;
        for (int[] k : known) {
            Double c = pathCost.apply(k);
            if (c == null) { noPath++; continue; }
            if (c.isNaN()) { computing++; continue; }
            Double b = bonusS.apply(k);
            double bb = b == null ? 0 : Math.max(0, b);
            if (c - bb < bestCost) { bestCost = c - bb; best = k; bestRaw = c; bestBonus = bb; }
        }
        if (best != null) return new Choice(How.KNOWN, best, "the known chest at " + at(best) + " (" + Math.round(bestRaw) + " s away"
                + (bestBonus > 0 ? ", -" + Math.round(bestBonus) + " s bonus" : "") + ")");
        // 0.39.z41: a known chest whose path is still computed is waited for (it may be the nearest): no chest made meanwhile
        if (computing > 0) return new Choice(How.WAIT, null, "the path to " + computing + " known chest" + (computing == 1 ? "" : "s") + " is not known yet (computing)");
        String note = noPath > 0 ? " (no path to the " + noPath + " known)" : "";
        if (chestInBag) return new Choice(How.BAG, null, "a chest from the bag" + note);
        if (canCraft) return new Choice(How.CRAFT, null, "a chest crafted from 8 planks" + note);
        return new Choice(How.FAIL, null, noPath > 0 ? "no path to any known chest, and no chest or 8 planks in the bag"
                : "no chest known, and no chest or 8 planks in the bag");
    }

    /** "chest at x y z full: N items left". */
    public static String full(int[] at, int left) {
        return "chest at " + at(at) + " full: " + left + " item" + (left == 1 ? "" : "s") + " left";
    }

    public static String at(int[] p) {
        return p[0] + " " + p[1] + " " + p[2];
    }

    /**
     * The order's saved deposits (per world, with the strip's progress): how many, how many items in all, the last chest,
     * and where the bag filled (the walk back's target).
     */
    public record Deposits(int count, int items, int[] lastChest, int[] back) {
        public static final Deposits NONE = new Deposits(0, 0, null, null);

        public Deposits add(int n, int[] chest) { return new Deposits(count + 1, items + n, chest, back); }

        public Deposits from(int[] where) { return new Deposits(count, items, lastChest, where); }

        /** "1 deposit (36 items at x y z)", "" none. */
        public String line() {
            if (count == 0) return "";
            return count + " deposit" + (count == 1 ? "" : "s") + " (" + items + " items" + (lastChest == null ? "" : " at " + at(lastChest)) + ")";
        }
    }
}
