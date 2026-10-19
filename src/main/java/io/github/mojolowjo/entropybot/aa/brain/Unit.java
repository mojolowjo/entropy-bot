package io.github.mojolowjo.entropybot.aa.brain;

/**
 * AA_ROUND2 0: the brain's one unit is seconds. Option costs are walk-blocks; a block walked takes {@link #SEC_PER_BLOCK}
 * (walking speed 4.317 blocks/s). Pure.
 */
public final class Unit {
    private Unit() {}

    public static final double WALK_BPS = 4.317, SEC_PER_BLOCK = 1 / WALK_BPS;

    public static double seconds(double blocks) { return blocks * SEC_PER_BLOCK; }

    public static double blocks(double seconds) { return seconds * WALK_BPS; }
}
