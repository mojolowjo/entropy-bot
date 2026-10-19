package io.github.mojolowjo.entropybot.aa.brain;

/**
 * BRAIN_PLAN 2.2 (B2): the score curves. Pure.
 * <ul>
 * <li>{@link #upkeep}: {@code w x shortfall^2}, flat while comfortable and steep near empty. Food (w 125, full 18):
 *     18 -> 0, 10 -> 25, 4 -> 76.</li>
 * <li>{@link #progress}: {@code value x P / (1 + cost / C)}.</li>
 * <li>{@link #pSuccess}: 1 until 2 failures in a row (0.5), 1/n after; after 3 measured runs the smoothed success
 *     rate instead.</li>
 * </ul>
 */
public final class Curves {
    private Curves() {}

    /** Seconds at which a progress desire's value halves. */
    public static final double COST_C = 600;
    /** Value flowing to a prerequisite keeps this share (2.2). */
    public static final double FLOW = 0.9;

    /** 0..w: {@code w x ((full - have) / full)^2}, 0 at or above full. */
    public static double upkeep(double w, double have, double full) {
        if (full <= 0 || have >= full) return 0;
        double s = (full - Math.max(0, have)) / full;
        return Math.min(100, w * s * s);
    }

    public static double progress(double value, double p, double costS) {
        return value * p / (1 + Math.max(0, costS) / COST_C);
    }

    /** P(success) from the failures in a row and the measured runs (n, ok). */
    public static double pSuccess(int streak, int n, int ok) {
        // review: one source, never both (the same failures would count twice): measured after 3 runs, else the streak
        if (n >= 3) return (ok + 1.0) / (n + 2.0);
        return streak >= 2 ? 1.0 / streak : 1;
    }
}
