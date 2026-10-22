package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.TaskKind;

import java.util.Map;

/**
 * The food need as numbers (owner 2026-10-18, aa-food). Pure; JUnit FoodModelTest.
 *
 * <p>Minecraft's rules (vanilla 1.21.1 FoodData / FoodConstants): every action adds fixed exhaustion (sprint 0.1 per
 * metre, jump 0.05, sprint-jump 0.2, swim 0.01 per metre, a block broken 0.005, an attack or a hit taken 0.1, one
 * health point healed 6.0); 4.0 exhaustion takes one point, saturation first, then the bar. Every food gives a fixed
 * nutrition and saturation. So the bot's "energy" is {@code food + saturation} and its drain per minute depends on the
 * kind of work (Baritone sprints between jobs, mining breaks blocks, a fight heals). The drain is measured per
 * {@link TaskKind} (BrainStats, saved in aa-stats.json); the hand rates below are only the start.
 *
 * <ul>
 * <li>{@link #energy}: nutrition + saturation of what is in the bag (raw meat at its raw value; poisonous food, rotten
 *     flesh and golden apples left out: they are not food to plan with).</li>
 * <li>{@link #target}: enough on me for {@link #HORIZON_MIN} minutes of the planned work, less what the body still holds
 *     above the urgent line; between {@link #MIN_STOCK} and {@link #MAX_STOCK}.</li>
 * <li>{@link #stockScore}: below the target, {@code 25 + 35 x shortfall^2} (under the urgent line: gathered when nothing
 *     more pressing runs, before the bar is low).</li>
 * </ul>
 */
public final class FoodModel {
    private FoodModel() {}

    public static double horizonMin() { return Tuning.get("foodModel.horizonMin"); }
    public static double minStock() { return Tuning.get("foodModel.minStock"); }
    public static double maxStock() { return Tuning.get("foodModel.maxStock"); }
    /** At or below this the bar is urgent: eat at once (any food), the food desire ranks over the night (Curves.food). */
    public static int urgentFood() { return Tuning.i("foodModel.urgentFood"); }
    public static double stockBase() { return Tuning.get("foodModel.stockBase"); }
    public static double stockW() { return Tuning.get("foodModel.stockW"); }
    /** Energy points per minute when nothing is measured for a kind yet (hand values, replaced after 2 samples). */
    public static double handMean() { return Tuning.get("foodModel.handMean"); }

    /** Raw food -> the cooked food (item paths), from the game's cooking recipes whose result is food (empty without data). */
    public static Map<String, String> cooks() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (var e : ItemWorth.cooks().entrySet()) out.put(path(e.getKey()), path(e.getValue()));
        return out;
    }

    /** The cooked item of a raw one (null: nothing cooks it). */
    public static String cooked(String rawPath) { return rawPath == null ? null : cooks().get(path(rawPath)); }

    static String path(String id) { int i = id.indexOf(':'); return i < 0 ? id : id.substring(i + 1); }

    /** Nutrition + saturation of one item from its FOOD component (0: not plannable food; harmful food is no food). */
    public static double energyOf(String id) { return ItemWorth.energy(id); }

    public static int nutritionOf(String id) { return (int) ItemWorth.nutrition(id); }
    /** The bag's food energy (item path -> count). */
    public static double energy(Map<String, Integer> bag) {
        double e = 0;
        if (bag != null) for (var en : bag.entrySet()) e += energyOf(en.getKey()) * Math.max(0, en.getValue());
        return e;
    }

    /** The highest nutrition of a food in the bag (0: none). */
    public static int bestNutrition(Map<String, Integer> bag) {
        int b = 0;
        if (bag != null) for (var en : bag.entrySet()) if (en.getValue() > 0) b = Math.max(b, nutritionOf(en.getKey()));
        return b;
    }

    /** Hand drain (energy per minute) per kind before anything is measured. */
    public static double handRate(TaskKind k) {
        if (k == null) return handMean();
        return switch (k) {
            case NONE, WAIT, BAG, DEPOSIT -> 0.15;
            case EXPLORE -> 0.8;                                // long sprinted walks
            case IRON, STOCK_STONE, STOCK_COAL, STOCK_IRON, TOOLS -> 0.45;   // blocks broken, jumps, walks
            case FOOD -> 0.6;                                   // a hunt: sprints and attacks
            default -> handMean();
        };
    }

    /** Energy needed for the next {@link #HORIZON_MIN} minutes at {@code ratePerMin}. */
    public static double need(double ratePerMin) { return Math.max(0, ratePerMin) * horizonMin(); }

    /**
     * The bag's food target (energy points): the need for the horizon less what the body holds above the urgent line
     * ({@code food + saturation - URGENT_FOOD}), kept within MIN_STOCK..MAX_STOCK.
     */
    public static double target(double ratePerMin, int food, double saturation) {
        double body = Math.max(0, food + saturation - urgentFood());
        return Math.max(minStock(), Math.min(maxStock(), need(ratePerMin) - body));
    }

    /** Below the target: 25 + 35 x shortfall^2 (25..60, never urgent); at or over it -1. */
    public static double stockScore(double bagEnergy, double target) {
        if (target <= 0 || bagEnergy >= target) return -1;
        double s = (target - bagEnergy) / target;
        return stockBase() + stockW() * s * s;
    }

    /** How many of a food bring the bag to the target (at least 1, at most 16). */
    public static int count(double shortfall, double energyEach) {
        if (energyEach <= 0) return 1;
        return (int) Math.max(1, Math.min(16, Math.ceil(shortfall / energyEach)));
    }

    /** The planned-work rate: the larger of the measured mean and the running kind's rate. */
    public static double plannedRate(BrainStats stats, TaskKind running) {
        if (stats == null) return Math.max(handMean(), handRate(running));
        return Math.max(stats.meanDrain(handMean()), stats.drain(running == null ? TaskKind.NONE.name() : running.name(), handRate(running)));
    }
}

