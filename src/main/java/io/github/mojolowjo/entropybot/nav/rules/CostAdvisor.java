package io.github.mojolowjo.entropybot.nav.rules;

import io.github.mojolowjo.entropybot.nav.world.BlockView;
import net.minecraft.world.level.block.state.BlockState;

/**
 * nav step 4: the mod's say inside the route search, our version of the fork's {@code CostAdvisor}. The fork's hook
 * was a static field plus a per-search token from {@code begin()}; here the caller hands one advisor to each
 * {@link SearchContext}, so the object itself is the per-search state and there is no token. Every answer may be NaN,
 * meaning "no say" (Baritone's own price stands). The mod's {@code CostAdvisorHook} (guard/BreakPolicy prices) is
 * the real one; it is wired in step 8.
 *
 * <p>Pure (JUnit: SearchContextTest). Loader notes: BlockState only (common side, Mojang names).
 */
public interface CostAdvisor {

    /** Factor on the mining time of x y z (COST_INF forbids; NaN = x1). */
    double breakMultiplier(int x, int y, int z, BlockState state, BlockView view);

    /** Ticks to place a block at x y z, given Baritone's own price (NaN = keep it). */
    double placeCost(int x, int y, int z, BlockState state, BlockView view, double defaultCost);

    /** Factor on a move whose feet end in x y z (NaN = no say). */
    default double moveMultiplier(int x, int y, int z) {
        return Double.NaN;
    }

    /** Ticks added to a move whose feet end in x y z (NaN = no say). */
    default double moveExtra(int x, int y, int z, BlockView view) {
        return Double.NaN;
    }

    /** False when the two move calls above always say NaN, so the search can skip them. */
    default boolean pricesMoves() {
        return true;
    }

    /**
     * The advisor's rules epoch: a new number whenever its rules change (the fork's CostAdvisor.rulesEpoch, z57). A kept
     * price whose epoch differs is dropped (nav step 7, the price stamp). 0: the advisor does not say.
     */
    default long rulesEpoch() {
        return 0;
    }
}
