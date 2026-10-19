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

    public static final double HORIZON_MIN = 60, MIN_STOCK = 8, MAX_STOCK = 64;
    /** At or below this the bar is urgent: eat at once (any food), the food desire ranks over the night (Curves.food). */
    public static final int URGENT_FOOD = 6;
    public static final double STOCK_BASE = 25, STOCK_W = 35;
    /** Energy points per minute when nothing is measured for a kind yet (hand values, replaced after 2 samples). */
    public static final double HAND_MEAN = 0.4;

    /** item path -> {nutrition, saturation} (vanilla values). */
    public static final Map<String, double[]> FOODS = Map.ofEntries(
            Map.entry("apple", new double[]{4, 2.4}), Map.entry("baked_potato", new double[]{5, 6}),
            Map.entry("beetroot", new double[]{1, 1.2}), Map.entry("beetroot_soup", new double[]{6, 7.2}),
            Map.entry("bread", new double[]{5, 6}), Map.entry("carrot", new double[]{3, 3.6}),
            Map.entry("cooked_beef", new double[]{8, 12.8}), Map.entry("cooked_porkchop", new double[]{8, 12.8}),
            Map.entry("cooked_chicken", new double[]{6, 7.2}), Map.entry("cooked_mutton", new double[]{6, 9.6}),
            Map.entry("cooked_rabbit", new double[]{5, 6}), Map.entry("cooked_cod", new double[]{5, 6}),
            Map.entry("cooked_salmon", new double[]{6, 9.6}), Map.entry("cookie", new double[]{2, 0.4}),
            Map.entry("dried_kelp", new double[]{1, 0.6}), Map.entry("glow_berries", new double[]{2, 0.4}),
            Map.entry("sweet_berries", new double[]{2, 0.4}), Map.entry("golden_carrot", new double[]{6, 14.4}),
            Map.entry("melon_slice", new double[]{2, 1.2}), Map.entry("mushroom_stew", new double[]{6, 7.2}),
            Map.entry("rabbit_stew", new double[]{10, 12}), Map.entry("potato", new double[]{1, 0.6}),
            Map.entry("pumpkin_pie", new double[]{8, 4.8}), Map.entry("honey_bottle", new double[]{6, 1.2}),
            Map.entry("beef", new double[]{3, 1.8}), Map.entry("porkchop", new double[]{3, 1.8}),
            Map.entry("mutton", new double[]{2, 1.2}), Map.entry("rabbit", new double[]{3, 1.8}),
            Map.entry("cod", new double[]{2, 0.4}), Map.entry("salmon", new double[]{2, 0.4}));

    /** Raw food a furnace turns into the cooked one (the cook verb's names). */
    public static final Map<String, String> COOKS = Map.of("beef", "cooked_beef", "porkchop", "cooked_porkchop",
            "chicken", "cooked_chicken", "mutton", "cooked_mutton", "rabbit", "cooked_rabbit", "cod", "cooked_cod",
            "salmon", "cooked_salmon", "potato", "baked_potato", "kelp", "dried_kelp");

    /** Nutrition + saturation of one item (0: not plannable food). */
    public static double energyOf(String path) {
        double[] f = FOODS.get(path);
        return f == null ? 0 : f[0] + f[1];
    }

    public static int nutritionOf(String path) {
        double[] f = FOODS.get(path);
        return f == null ? 0 : (int) f[0];
    }

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
        if (k == null) return HAND_MEAN;
        return switch (k) {
            case NONE, WAIT, BAG, DEPOSIT -> 0.15;
            case EXPLORE -> 0.8;                                // long sprinted walks
            case IRON, STOCK_STONE, STOCK_COAL, STOCK_IRON, TOOLS -> 0.45;   // blocks broken, jumps, walks
            case FOOD -> 0.6;                                   // a hunt: sprints and attacks
            default -> HAND_MEAN;
        };
    }

    /** Energy needed for the next {@link #HORIZON_MIN} minutes at {@code ratePerMin}. */
    public static double need(double ratePerMin) { return Math.max(0, ratePerMin) * HORIZON_MIN; }

    /**
     * The bag's food target (energy points): the need for the horizon less what the body holds above the urgent line
     * ({@code food + saturation - URGENT_FOOD}), kept within MIN_STOCK..MAX_STOCK.
     */
    public static double target(double ratePerMin, int food, double saturation) {
        double body = Math.max(0, food + saturation - URGENT_FOOD);
        return Math.max(MIN_STOCK, Math.min(MAX_STOCK, need(ratePerMin) - body));
    }

    /** Below the target: 25 + 35 x shortfall^2 (25..60, never urgent); at or over it -1. */
    public static double stockScore(double bagEnergy, double target) {
        if (target <= 0 || bagEnergy >= target) return -1;
        double s = (target - bagEnergy) / target;
        return STOCK_BASE + STOCK_W * s * s;
    }

    /** How many of a food bring the bag to the target (at least 1, at most 16). */
    public static int count(double shortfall, double energyEach) {
        if (energyEach <= 0) return 1;
        return (int) Math.max(1, Math.min(16, Math.ceil(shortfall / energyEach)));
    }

    /** The planned-work rate: the larger of the measured mean and the running kind's rate. */
    public static double plannedRate(BrainStats stats, TaskKind running) {
        if (stats == null) return Math.max(HAND_MEAN, handRate(running));
        return Math.max(stats.meanDrain(HAND_MEAN), stats.drain(running == null ? TaskKind.NONE.name() : running.name(), handRate(running)));
    }
}
