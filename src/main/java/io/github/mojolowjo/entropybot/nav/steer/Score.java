package io.github.mojolowjo.entropybot.nav.steer;

/**
 * Ghost steering S1: how good one try was; lower is better. A try that reaches the target scores below every try that
 * doesn't (-1 plus how late it got there, as a share of the horizon); otherwise it scores its end distance to the aim
 * point. Hurting adds {@code hurtCost}, and air under {@code airLow} adds up to {@code hurtCost} more as it runs out.
 *
 * <p>Pure (JUnit: SteererTest). Loader notes: none.
 */
public final class Score {
    private Score() {}

    /**
     * @param reachedAt the tick (1-based) the try first held a target cell, or -1
     * @param endDistance the try's end distance to the aim point (see {@link Target#distance})
     */
    public static double of(int reachedAt, double endDistance, boolean hurt, int air, SteerConfig c) {
        double s = reachedAt > 0 ? -1.0 + (double) reachedAt / c.horizon() : endDistance;
        if (hurt) s += c.hurtCost();
        if (air < c.airLow()) s += c.hurtCost() * (c.airLow() - air) / (double) c.airLow();
        return s;
    }
}
