package io.github.mojolowjo.entropybot.nav.search;

import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;

/**
 * nav step 6: how the search adds the cost advisor's per-cell answers and the lava charge to a move's own price (the
 * fork's {@code MoveCost} and {@code LavaWade}, fork-only classes, written from the 1.11.3-entropy jar). NaN or a
 * non-positive answer means "no say"; COST_INF on either side stays COST_INF.
 *
 * <p>Pure (JUnit: SearchMatchForkTest). Loader notes: none.
 */
public final class MoveCost {
    private MoveCost() {}

    public static double applyMove(double actionCost, double multiplier) {
        if (Double.isNaN(multiplier) || multiplier <= 0) {
            return actionCost;
        }
        return multiplier < ActionCosts.COST_INF && actionCost < ActionCosts.COST_INF ? actionCost * multiplier : ActionCosts.COST_INF;
    }

    public static double addMove(double actionCost, double extra) {
        if (Double.isNaN(extra) || extra <= 0) {
            return actionCost;
        }
        return extra < ActionCosts.COST_INF && actionCost < ActionCosts.COST_INF ? actionCost + extra : ActionCosts.COST_INF;
    }

    /** LavaWade: {@code perCell} for the feet in lava and again for the head in lava. */
    public static double lavaCharge(double moveCost, double perCell, boolean feetLava, boolean headLava) {
        return perCell <= 0 ? moveCost : moveCost + (feetLava ? perCell : 0) + (headLava ? perCell : 0);
    }
}
