package io.github.mojolowjo.entropybot.commands;

/**
 * P1 fix (0.19.7): how a walk that Baritone ended is judged. Baritone's goto to a goal it can't reach paths to the
 * closest reachable spot (or plans nothing from a sealed pit) and simply goes idle, with no CALC_FAILED: so the end
 * position decides, never Baritone's idleness. Pure, unit-tested.
 */
final class WalkEnd {
    private WalkEnd() {}

    /** Blocks (straight line) from the goal a walk may end and still count as arrived. */
    static final int TOLERANCE = 3;
    /** How often a walk that ended short tries to step off / dig out and walk again before it gives up. */
    static final int SHORT_TRIES = 6;

    /** True when the end spot is within the arrival tolerance of the destination (or there is no fixed destination). */
    static boolean arrived(int[] me, int[] dest) {
        if (dest == null || me == null) return true;
        long dx = me[0] - dest[0], dy = me[1] - dest[1], dz = me[2] - dest[2];
        return dx * dx + dy * dy + dz * dz <= (long) TOLERANCE * TOLERANCE;
    }

    /**
     * 0.23.4: what the goal cell looks like. solid: the block id (short) filling the goal's feet cell, or null when it is
     * open; walledIn: open but closed on all four sides and above (no way in); freeNear: a standable cell within 1 block
     * of a solid goal (the one nearest the bot), or null.
     */
    record GoalCell(String solid, boolean walledIn, int[] freeNear) {
        static final GoalCell OPEN = new GoalCell(null, false, null);
    }

    /** Within this straight distance of the goal the walk counts as at it, whatever the goal cell is. */
    static final double AT_GOAL = 1.0;

    /**
     * 0.23.4 (run 9a): the 3-block tolerance hid a miss when the goal cell itself is solid or walled in. Null: judge by
     * {@link #arrived} as before. Else the whole answer: "ok: arrived next to x y z (the spot is inside stone)" when the
     * goal is inside a block and the bot stands on a free cell within 1 block of it, or
     * "error: couldn't get there (goal is inside stone at x y z; stopped N blocks away)" /
     * "error: couldn't get there (goal is walled in at x y z; stopped N blocks away)".
     */
    static String goalVerdict(int[] me, int[] dest, GoalCell g) {
        if (me == null || dest == null || g == null) return null;
        double far = Math.sqrt(sq(me, dest));
        if (far <= AT_GOAL) return g.solid() != null ? nextTo(dest, g.solid()) : null;
        if (g.solid() != null) {
            if (g.freeNear() != null && Math.sqrt(sq(me, g.freeNear())) <= AT_GOAL) return nextTo(dest, g.solid());
            return "error: couldn't get there (goal is inside " + g.solid() + " at " + xyz(dest) + "; stopped " + Math.round(far) + " blocks away)";
        }
        if (g.walledIn()) return "error: couldn't get there (goal is walled in at " + xyz(dest) + "; stopped " + Math.round(far) + " blocks away)";
        return null;
    }

    private static String nextTo(int[] dest, String block) {
        return "ok: arrived next to " + xyz(dest) + " (the spot is inside " + block + ")";
    }

    private static String xyz(int[] p) { return p[0] + " " + p[1] + " " + p[2]; }

    private static double sq(int[] a, int[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** The answer of a walk that ended short (the strip mine's wording, with where it stopped). */
    static String shortResult(int[] me, int[] dest) {
        long dx = me[0] - dest[0], dy = me[1] - dest[1], dz = me[2] - dest[2];
        long far = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        return "error: couldn't get there (" + far + " blocks away, stopped at " + me[0] + " " + me[1] + " " + me[2] + ")";
    }

    /**
     * Does the best free step next to the bot help a walk that ended short? Only when it is clearly closer to the goal;
     * else (none, or the cell it came from) the bot counts as enclosed and the escape dig-out is the way (live 0.19.7:
     * a one-step escape pocket made the unstick step back and forth).
     */
    static boolean stepHelps(double stepDist, double hereDist) {
        return stepDist < hereDist - 0.5;
    }

    /** Should a walk that ended short try again (step off, or dig out when enclosed)? */
    static boolean retryShort(int shortTries) {
        return shortTries < SHORT_TRIES;
    }

    /**
     * The escape dig-out trigger: no free step next to the bot, underground, inside the areas, and escapes left.
     * Used by startUnstick (no free step) for every walk end, stuck, no-path or ended-short alike.
     */
    static boolean escapeTrigger(boolean freeStep, boolean underground, boolean inAreas, int escapes, int maxEscapes, boolean reflex) {
        return !freeStep && underground && inAreas && escapes < maxEscapes && !reflex;
    }
}
