package io.github.mojolowjo.entropybot.guard;

/**
 * AREAS (0.39.3, AREAS_PLAN 3): the price of a Baritone move by the labelled places, for the fork's
 * {@code CostAdvisor.moveMultiplier} (baritone/CostAdvisorHook). Pure (JUnit: MoveRulesTest); lock-free on an
 * immutable policy snapshot; the hot A* loop early-outs when no keep-out or danger box exists.
 */
public final class MoveRules {
    private MoveRules() {}

    /** Baritone's COST_INF (ActionCosts.COST_INF), copied so this class stays free of Baritone types. */
    public static final double COST_INF = 1000000.0;
    /** The owner's default (AREAS_PLAN 10.7): a move inside a danger area costs this much more. */
    public static final double DEFAULT_DANGER = 4;

    /** Bounds 1..64 (1 = a danger area costs nothing extra): the value when in bounds, else {@code was}. */
    public static double danger(double m, double was) {
        return !Double.isNaN(m) && m >= 1 && m <= 64 ? m : was;
    }

    /**
     * The multiplier on a move whose feet end at x y z: {@code COST_INF} inside a keep-out box (feet or head cell),
     * {@code dangerMult} inside a danger box, NaN (no opinion) elsewhere. {@code at} = the bot's cell when the
     * calculation began: a bot already inside a keep-out box (the owner drew it around it) gets that box priced like
     * danger instead of impassable, so it walks out by the shortest way and never plans a goal inside.
     */
    public static double moveMultiplier(Policy p, String dim, int[] at, int x, int y, int z, double dangerMult) {
        if (p == null || dim == null) return Double.NaN;
        if (p.keepOut.isEmpty() && p.danger.isEmpty()) return Double.NaN;
        double dear = Math.max(1, dangerMult);
        Box ko = p.keepOutAt(dim, x, y, z);
        if (ko == null) ko = p.keepOutAt(dim, x, y + 1, z);
        if (ko != null) {
            boolean startedInside = at != null && at.length >= 3 && (ko.contains(dim, at[0], at[1], at[2]) || ko.contains(dim, at[0], at[1] + 1, at[2]));
            return startedInside ? dear : COST_INF;
        }
        return p.dangerAt(dim, x, y, z) != null ? dear : Double.NaN;
    }
}
