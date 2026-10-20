package io.github.mojolowjo.entropybot.guard;

import java.util.Locale;

/**
 * aa-costs (item 1; replaces "never dig down from in the water" and "skip an ore 2+ below while in water"): the
 * drowning risk of digging down from in the water, from the air left, the blocks to dig, the water over me, the
 * pickaxe and the time to dig. Need = digDown x {@link #digS} + (digDown + waterAbove) / {@link #SWIM_UP} (dig, then
 * swim back up the flooded shaft), times the margin {@link #MARGIN}; risk = need / air seconds. Risk 1 or more cannot
 * be done (no air to come back); under 1 it is a cost of {@link #K} x risk^2 walk-blocks. Pure (JUnit: DrownRiskTest).
 */
public final class DrownRisk {
    private DrownRisk() {}

    public static final double SWIM_UP = 2.0, MARGIN = 1.5, K = 60;
    /** Vanilla: breaking is 5x slower submerged (no Aqua Affinity) and 5x again off the ground; we count the 5x. */
    public static final double WATER_SLOW = 5;
    /** Seconds per stone block on dry ground by pickaxe tier (0 hand, 1 wood/gold, 2 stone, 3 iron, 4 diamond+), plus the drop. */
    static final double[] DRY_S = {7.5, 1.15, 0.6, 0.4, 0.3};
    public static final double DROP_S = 0.4;
    /** The tier assumed when unknown (a stone pickaxe). */
    public static final int DEFAULT_TIER = 2;

    /** Seconds to dig one block down while submerged with this pickaxe tier. */
    public static double digS(int tier) { return DRY_S[Math.max(0, Math.min(4, tier))] * WATER_SLOW + DROP_S; }

    public static double needS(int digDown, int waterAbove, int tier) {
        int d = Math.max(0, digDown);
        return d * digS(tier) + (d + Math.max(0, waterAbove)) / SWIM_UP;
    }

    public static double needS(int digDown, int waterAbove) { return needS(digDown, waterAbove, DEFAULT_TIER); }

    /** need x margin / air seconds; air in ticks (300 full = 15 s). */
    public static double risk(int digDown, int waterAbove, int air, int tier) {
        double need = needS(digDown, waterAbove, tier) * MARGIN;
        double a = Math.max(0, air) / 20.0;
        if (a <= 0) return need > 0 ? Double.POSITIVE_INFINITY : 0;
        return need / a;
    }

    public static double risk(int digDown, int waterAbove, int air) { return risk(digDown, waterAbove, air, DEFAULT_TIER); }

    public static boolean fatal(double risk) { return risk >= 1; }

    /** Seconds to place one block (aim, click). */
    public static final double PLACE_S = 0.5;
    /** The deepest water column counted per cell (the A* scan stops here; deeper counts as this). */
    public static final int SURFACE_SCAN = 32;

    /**
     * aa-water (to-do 1f): the risk of working one cell {@code depth} blocks below the water surface: the work there
     * ({@code workS}: a dig {@link #digS}, a place {@link #PLACE_S}) plus the swim up from that depth, x {@link #MARGIN},
     * over the air left. The cell's own depth counts, not my feet's (a deep flooded cave is costly from any cell in it).
     */
    public static double cellRisk(int depth, double workS, int air) {
        double need = (Math.max(0, workS) + Math.max(0, depth) / SWIM_UP) * MARGIN;
        double a = Math.max(0, air) / 20.0;
        if (a <= 0) return need > 0 ? Double.POSITIVE_INFINITY : 0;
        return need / a;
    }

    /** A* break multiplier for a cell under water: 1 + 4 risk^2, infinite when fatal. */
    public static double breakMult(int depth, int air, int tier) {
        double r = cellRisk(depth, digS(tier), air);
        return fatal(r) ? Double.POSITIVE_INFINITY : 1 + 4 * r * r;
    }

    /** A* place multiplier for a cell in water: 1 + 4 risk^2, infinite when fatal. */
    public static double placeMult(int depth, int air) {
        double r = cellRisk(depth, PLACE_S, air);
        return fatal(r) ? Double.POSITIVE_INFINITY : 1 + 4 * r * r;
    }

    /** Walk-blocks: K x risk^2 under 1, infinite from 1. */
    public static double cost(double risk) { return fatal(risk) ? Double.POSITIVE_INFINITY : K * risk * risk; }

    public static String terms(int digDown, int waterAbove, int air, int tier) {
        double r = risk(digDown, waterAbove, air, tier);
        return String.format(Locale.ROOT, "dig %d down under %d water (pickaxe tier %d, %.1f s/block): needs %.1f s x%.1f, air %.1f s, drown risk %s",
                digDown, waterAbove, tier, digS(tier), needS(digDown, waterAbove, tier), MARGIN, Math.max(0, air) / 20.0,
                Double.isInfinite(r) ? "max" : String.format(Locale.ROOT, "%.2f", r));
    }

    public static String terms(int digDown, int waterAbove, int air) { return terms(digDown, waterAbove, air, DEFAULT_TIER); }

    /** The best pickaxe tier among item ids ("minecraft:stone_pickaxe" -> 2), 0 none. */
    public static int pickTier(Iterable<String> itemIds) {
        int t = 0;
        for (String id : itemIds) {
            if (id == null || !id.endsWith("_pickaxe")) continue;
            int v = id.contains("netherite") || id.contains("diamond") ? 4 : id.contains("iron") ? 3 : id.contains("stone") ? 2 : 1;
            t = Math.max(t, v);
        }
        return t;
    }
}
