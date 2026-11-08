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

    /**
     * D1 ledger (job-d1): which stacks go in, parallel to {@code bag}, with the run's chain reserves ({@code reserves}, the
     * board's ledger; ids "minecraft:x") left in the bag. A stack goes in only while the bag after it still meets every
     * reserve it can meet now (the fork's {@link baritone.api.process.Reserves#spare}); a stack that would cut into a reserved
     * count stays whole (a quick move moves whole stacks: the safe side, a few over the count stay).
     */
    public static boolean[] goesIn(List<Item> bag, List<baritone.api.process.Reserves.Reserve<String>> reserves) {
        boolean[] out = new boolean[bag.size()];
        Map<String, Integer> left = new java.util.HashMap<>();
        for (Item i : bag) if (i != null && i.count() > 0) left.merge(i.id(), i.count(), Integer::sum);
        boolean none = reserves == null || reserves.isEmpty();
        for (int k = 0; k < bag.size(); k++) {
            Item i = bag.get(k);
            if (i == null || i.count() <= 0 || kept(i)) continue;
            if (none || !baritone.api.process.Reserves.reserved(reserves, i.id())) { out[k] = true; continue; }
            if (baritone.api.process.Reserves.spare(reserves, left, i.id()) >= i.count()) { out[k] = true; left.merge(i.id(), -i.count(), Integer::sum); }
        }
        return out;
    }

    /** What goes into the chest with the reserves left in the bag ({@link #goesIn}): id -> count. */
    public static Map<String, Integer> toPut(List<Item> bag, List<baritone.api.process.Reserves.Reserve<String>> reserves) {
        boolean[] in = goesIn(bag, reserves);
        Map<String, Integer> out = new TreeMap<>();
        for (int k = 0; k < bag.size(); k++) if (in[k]) out.merge(bag.get(k).id(), bag.get(k).count(), Integer::sum);
        return out;
    }

    /** What the reserves keep back from the chest ({@code toPut} without them, less with them): id -> count, empty: nothing. */
    public static Map<String, Integer> heldBack(List<Item> bag, List<baritone.api.process.Reserves.Reserve<String>> reserves) {
        Map<String, Integer> all = toPut(bag), with = toPut(bag, reserves), out = new TreeMap<>();
        for (var e : all.entrySet()) {
            int h = e.getValue() - with.getOrDefault(e.getKey(), 0);
            if (h > 0) out.put(e.getKey(), h);
        }
        return out;
    }

    // ---- deposit-words (owner 2026-11-08): exactly these items and counts, nothing else ----

    /** The item id an owner word names: "cobblestone" -> "minecraft:cobblestone"; a word with a namespace as it is. */
    public static String idOf(String word) {
        String w = word.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        return w.contains(":") ? w : "minecraft:" + w;
    }

    /**
     * How many of each bag stack go in for {@code only} (id -> count asked), parallel to {@code bag}; 0: the stack stays.
     * Whole stacks first (a stack within what is still asked), then ONE split stack for the rest. Never more than asked;
     * never more than the bag holds. The kept set and the reserves do not apply: the owner named the items.
     */
    public static int[] takeOnly(List<Item> bag, Map<String, Integer> only) {
        int[] out = new int[bag.size()];
        if (only == null) return out;
        Map<String, Integer> left = new java.util.HashMap<>();
        for (var e : only.entrySet()) if (e.getValue() != null && e.getValue() > 0) left.put(idOf(e.getKey()), e.getValue());
        for (int k = 0; k < bag.size(); k++) {                       // whole stacks
            Item i = bag.get(k);
            if (i == null || i.count() <= 0) continue;
            int need = left.getOrDefault(i.id(), 0);
            if (need >= i.count()) { out[k] = i.count(); left.put(i.id(), need - i.count()); }
        }
        for (int k = 0; k < bag.size(); k++) {                       // one split stack per item for the rest
            Item i = bag.get(k);
            if (i == null || i.count() <= 0 || out[k] > 0) continue;
            int need = left.getOrDefault(i.id(), 0);
            if (need > 0) { out[k] = need; left.put(i.id(), 0); }
        }
        return out;
    }

    /** What {@link #takeOnly} puts in: id -> count. */
    public static Map<String, Integer> onlyToPut(List<Item> bag, Map<String, Integer> only) {
        int[] take = takeOnly(bag, only);
        Map<String, Integer> out = new TreeMap<>();
        for (int k = 0; k < bag.size(); k++) if (take[k] > 0) out.merge(bag.get(k).id(), take[k], Integer::sum);
        return out;
    }

    /**
     * One line per asked item, said before the walk (rule 7): "cobblestone: 32 of 40 in the bag: putting 32 away",
     * "torch: 2 in the bag, 4 asked: putting 2 away", "iron_ingot: none in the bag".
     */
    public static List<String> onlyReport(List<Item> bag, Map<String, Integer> only) {
        Map<String, Integer> have = new java.util.HashMap<>();
        for (Item i : bag) if (i != null && i.count() > 0) have.merge(i.id(), i.count(), Integer::sum);
        List<String> out = new java.util.ArrayList<>();
        for (var e : only.entrySet()) {
            String id = idOf(e.getKey()), name = id.replaceFirst("^minecraft:", "");
            int asked = e.getValue() == null ? 0 : e.getValue(), h = have.getOrDefault(id, 0);
            if (h <= 0) out.add(name + ": none in the bag");
            else if (h >= asked) out.add(name + ": " + asked + " of " + h + " in the bag: putting " + asked + " away");
            else out.add(name + ": " + h + " in the bag, " + asked + " asked: putting " + h + " away");
        }
        return out;
    }

    /** The bag's count of the asked items (id -> count), for the count after the put. */
    public static int onlyInBag(List<Item> bag, Map<String, Integer> only) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String k : only.keySet()) ids.add(idOf(k));
        int n = 0;
        for (Item i : bag) if (i != null && ids.contains(i.id())) n += Math.max(0, i.count());
        return n;
    }

    public enum How { KNOWN, BAG, CRAFT, WAIT, FAIL }

    /**
     * The chest choice: KNOWN at {@code at}; BAG / CRAFT a new one; WAIT (not loaded); FAIL with {@code why}.
     * {@code estimated} (run JAC): KNOWN on Baritone's estimate only - its path is not known yet, the walk prices it on the way.
     */
    public record Choice(How how, int[] at, String why, boolean estimated) {
        public Choice(How how, int[] at, String why) { this(how, at, why, false); }
    }

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
        return choose(known, pathCost, bonusS, k -> null, chestInBag, canCraft);
    }

    /**
     * Run JAC (2026-11-03, rule 15: not known is not unreachable): the same with {@code estimateS} - Baritone's estimate of
     * the walk to a chest whose path is not known (its chunks not loaded, 360 m off), null = no estimate. A priced chest
     * wins; one still computed is waited for; else the known chest with the cheapest estimate less its bonus is walked
     * toward, priced on the way ({@link Choice#estimated}); a new chest is made only when no known chest has an estimate
     * either (the run crafted a chest from the planks it was to deposit, and failed: no table).
     */
    public static Choice choose(List<int[]> known, Function<int[], Double> pathCost, Function<int[], Double> bonusS,
                                Function<int[], Double> estimateS, boolean chestInBag, boolean canCraft) {
        if (known == null) return new Choice(How.WAIT, null, "the chest memory is not loaded yet");
        int[] best = null, guess = null;
        double bestCost = Double.MAX_VALUE, bestRaw = 0, bestBonus = 0, guessCost = Double.MAX_VALUE, guessRaw = 0;
        int noPath = 0, computing = 0;
        for (int[] k : known) {
            Double c = pathCost.apply(k);
            Double b = bonusS.apply(k);
            double bb = b == null ? 0 : Math.max(0, b);
            if (c == null) {
                Double e = estimateS.apply(k);
                if (e == null || e.isNaN()) { noPath++; continue; }
                if (e - bb < guessCost) { guessCost = e - bb; guess = k; guessRaw = e; }
                continue;
            }
            if (c.isNaN()) { computing++; continue; }
            if (c - bb < bestCost) { bestCost = c - bb; best = k; bestRaw = c; bestBonus = bb; }
        }
        if (best != null) return new Choice(How.KNOWN, best, "the known chest at " + at(best) + " (" + Math.round(bestRaw) + " s away"
                + (bestBonus > 0 ? ", -" + Math.round(bestBonus) + " s bonus" : "") + ")");
        // 0.39.z41: a known chest whose path is still computed is waited for (it may be the nearest): no chest made meanwhile
        if (computing > 0) return new Choice(How.WAIT, null, "the path to " + computing + " known chest" + (computing == 1 ? "" : "s") + " is not known yet (computing)");
        if (guess != null) return new Choice(How.KNOWN, guess, "the known chest at " + at(guess) + ": its path is not known (chunks not loaded), Baritone's estimate "
                + Math.round(guessRaw) + " s - walking toward it, priced on the way", true);
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
