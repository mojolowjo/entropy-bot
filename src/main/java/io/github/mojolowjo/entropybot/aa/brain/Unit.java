package io.github.mojolowjo.entropybot.aa.brain;

/**
 * AA_ROUND2 0: the brain's one unit is seconds. Option costs are walk-blocks; a block walked takes {@link #SEC_PER_BLOCK}
 * (walking speed 4.317 blocks/s). aa-times: the conversions use the <i>live</i> walk speed when the adapter has set one
 * ({@link #setLiveBps}: {@code GameTimes.walkBps} from the MOVEMENT_SPEED attribute, so Speed, Slowness and Soul Speed
 * count); without a player the constant stands. Pure.
 */
public final class Unit {
    private Unit() {}

    public static final double WALK_BPS = 4.317, SEC_PER_BLOCK = 1 / WALK_BPS;

    private static volatile double liveBps = 0;

    /** The adapter's walk speed (blocks a second); 0 or less = none read, back to the constant. */
    public static void setLiveBps(double bps) { liveBps = Double.isFinite(bps) && bps > 0 ? bps : 0; }

    /** The walk speed the conversions use now: the live one, else {@link #WALK_BPS}. */
    public static double walkBps() { return liveBps > 0 ? liveBps : WALK_BPS; }

    /** True when a live speed is in use (status lines: "walk 4.3 (game)"). */
    public static boolean live() { return liveBps > 0; }

    public static double seconds(double blocks) { return blocks / walkBps(); }

    public static double blocks(double seconds) { return seconds * walkBps(); }
}
