package io.github.mojolowjo.entropybot.nav.steer;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

/**
 * Ghost steering S1: the steerer's hand numbers, all Tuning entries ({@code steer.*}, TuningDefs) so {@code aa tune}
 * can change them per world. Tests build their own.
 *
 * @param horizon     ticks each option is tried for
 * @param every       re-test every this many ticks
 * @param hold        a new choice is kept at least this many ticks, unless the held one now hurts
 * @param giveUpTicks ticks with no progress before the steerer gives up
 * @param progress    blocks closer to the aim that count as progress
 * @param upWeight    how much a block of height counts against a block across in the distance
 * @param hurtCost    blocks added to a try that hurts
 * @param airLow      air under this (300 = full) costs, scaled by how low
 * @param keepBonus   blocks taken off the held choice's score, so near-ties don't flip
 *
 * <p>Pure (JUnit: SteererTest). Loader notes: none.
 */
public record SteerConfig(int horizon, int every, int hold, int giveUpTicks, double progress, double upWeight,
                          double hurtCost, int airLow, double keepBonus) {
    /** The live numbers. */
    public static SteerConfig live() {
        return new SteerConfig(Tuning.i("steer.horizon"), Tuning.i("steer.every"), Tuning.i("steer.hold"),
                Tuning.i("steer.giveUpTicks"), Tuning.get("steer.progress"), Tuning.get("steer.upWeight"),
                Tuning.get("steer.hurtCost"), Tuning.i("steer.airLow"), Tuning.get("steer.keepBonus"));
    }
}
