package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;

/**
 * 0.39.z41 (owner 2026-10-23: "I may want to price things myself or change the pricing"): the ONE place the chain turns
 * game numbers into its seconds, one Tuning multiplier per step kind (defaults 1.0): a walk from Baritone's path ticks
 * ({@code price.walkX}, its lava part too), a break from the game's break formula ({@code price.breakX}), a place from the
 * place step's seconds ({@code price.placeX}). No other scaling of these anywhere. Pure. Loader notes: none.
 */
public final class TickPrice {
    private TickPrice() {}

    /** Baritone's path ticks -> the chain's walk seconds. */
    public static double walkS(double ticks) { return ticks / GameTimes.TPS * Tuning.get("price.walkX"); }

    /** The game's break seconds (GameTimes.breakSeconds) -> the chain's break seconds. */
    public static double breakS(double gameSeconds) { return gameSeconds * Tuning.get("price.breakX"); }

    /** The place step's seconds -> the chain's place seconds. */
    public static double placeS(double seconds) { return seconds * Tuning.get("price.placeX"); }
}
