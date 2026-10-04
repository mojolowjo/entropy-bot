package io.github.mojolowjo.entropybot.commands;

/**
 * T3 (2026-10-04): what the fence does with a running job whose bot stands outside the areas. Game-free.
 *
 * <p>Gap 0 or 1 (inside, or a step out, as on any area's edge) is fine as before. A job holding dig leases (a clear: it
 * breaks blocks, so a drop or a cave can pull it a few blocks out) that is 2 to 4 blocks out gets {@link #GRACE_TICKS}
 * to come back, re-checked every 20 ticks; back within 1 resets the clock. More than 4 out, or any other job (a
 * walk, a craft trip...), stops at once, as it always did.
 */
public final class FenceGrace {
    private FenceGrace() {}

    /** 15 s. */
    public static final long GRACE_TICKS = 300;
    /** The farthest out the grace covers. */
    public static final int GRACE_GAP = 4;

    public enum Verdict { OK, GRACE, STOP }

    /**
     * @param gap cells between the bot's feet and the nearest area (0 = inside)
     * @param digLeases the job holds break leases of its own
     * @param outSince the tick the bot first stood 2+ out this time (-1: it wasn't)
     */
    public static Verdict verdict(int gap, boolean digLeases, long outSince, long now) {
        if (gap <= 1) return Verdict.OK;
        if (!digLeases || gap > GRACE_GAP) return Verdict.STOP;
        if (outSince >= 0 && now - outSince >= GRACE_TICKS) return Verdict.STOP;
        return Verdict.GRACE;
    }

    /** The stop message: as before, plus how long it waited when the grace ran out. */
    public static String stopMessage(String at, boolean afterGrace) {
        return "stopped: I am outside my areas at " + at + (afterGrace ? " (and didn't get back in within 15 s)" : "")
                + " - " + PolicyCommands.AREA_HINT;
    }
}
