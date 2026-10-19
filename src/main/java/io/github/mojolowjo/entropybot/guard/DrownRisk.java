package io.github.mojolowjo.entropybot.guard;

import java.util.Locale;

/**
 * aa-costs (item 1; replaces "never dig down from in the water" and "skip an ore 2+ below while in water"): the
 * drowning risk of digging down from in the water, from the air left, the blocks to dig, the water over me and the
 * time to dig. Need = digDown x {@link #DIG_S} + (digDown + waterAbove) / {@link #SWIM_UP} (dig, then swim back up the
 * flooded shaft), times the margin {@link #MARGIN}; risk = need / air seconds. Risk 1 or more cannot be done (no air
 * to come back); under 1 it is a cost of {@link #K} x risk^2 walk-blocks. Pure (JUnit: DrownRiskTest).
 */
public final class DrownRisk {
    private DrownRisk() {}

    /** Seconds to dig one block down (stone with a stone pickaxe plus the drop); blocks per second swimming up. */
    public static final double DIG_S = 1.0, SWIM_UP = 2.0, MARGIN = 1.5, K = 60;

    public static double needS(int digDown, int waterAbove) {
        int d = Math.max(0, digDown);
        return d * DIG_S + (d + Math.max(0, waterAbove)) / SWIM_UP;
    }

    /** need x margin / air seconds; air in ticks (300 full = 15 s). */
    public static double risk(int digDown, int waterAbove, int air) {
        double need = needS(digDown, waterAbove) * MARGIN;
        double a = Math.max(0, air) / 20.0;
        if (a <= 0) return need > 0 ? Double.POSITIVE_INFINITY : 0;
        return need / a;
    }

    public static boolean fatal(double risk) { return risk >= 1; }

    /** Walk-blocks: K x risk^2 under 1, infinite from 1. */
    public static double cost(double risk) { return fatal(risk) ? Double.POSITIVE_INFINITY : K * risk * risk; }

    public static String terms(int digDown, int waterAbove, int air) {
        double r = risk(digDown, waterAbove, air);
        return String.format(Locale.ROOT, "dig %d down under %d water: needs %.1f s x%.1f, air %.1f s, drown risk %s", digDown, waterAbove,
                needS(digDown, waterAbove), MARGIN, Math.max(0, air) / 20.0, Double.isInfinite(r) ? "max" : String.format(Locale.ROOT, "%.2f", r));
    }
}
