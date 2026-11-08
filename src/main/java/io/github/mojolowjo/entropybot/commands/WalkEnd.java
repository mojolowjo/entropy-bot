package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

/**
 * P1 fix (0.19.7): how a walk that Baritone ended is judged. Baritone's goto to a goal it can't reach paths to the
 * closest reachable spot (or plans nothing from a sealed pit) and simply goes idle, with no CALC_FAILED: so the end
 * position decides, never Baritone's idleness. Pure, unit-tested.
 */
final class WalkEnd {
    private WalkEnd() {}

    /** Blocks (straight line) from the goal a walk may end and still count as arrived. */
    static int tolerance() { return Tuning.i("walkEnd.tolerance"); }
    /** 0.33.1: a two-number goto may end on top of a wall column or drift in a water column: a little more room. */
    static int xzTolerance() { return Tuning.i("walkEnd.xzTolerance"); }

    /** True when the end spot is within the arrival tolerance of the destination (or there is no fixed destination). */
    static boolean arrived(int[] me, int[] dest) {
        if (dest == null || me == null) return true;
        long dx = me[0] - dest[0], dy = me[1] - dest[1], dz = me[2] - dest[2];
        return dx * dx + dy * dy + dz * dz <= (long) tolerance() * tolerance();
    }

    /** 0.33.1: a "goto x z" goal ({x, z}): arrived when within the tolerance on x z alone (any height). */
    static boolean arrivedXZ(int[] me, int[] xz) {
        if (xz == null || me == null) return true;
        long dx = me[0] - xz[0], dz = me[2] - xz[1];
        return dx * dx + dz * dz <= (long) xzTolerance() * xzTolerance();
    }

    static String shortResultXZ(int[] me, int[] xz) {
        long dx = me[0] - xz[0], dz = me[2] - xz[1];
        return "error: couldn't get to " + xz[0] + " " + xz[1] + " (" + Math.round(Math.sqrt(dx * dx + dz * dz)) + " blocks away, stopped at " + me[0] + " " + me[1] + " " + me[2] + ")";
    }

    /**
     * 0.23.4: what the goal cell looks like. solid: the block id (short) filling the goal's feet cell, or null when it is
     * open; walledIn: open but closed on all four sides and above (no way in); freeNear: a standable cell within 1 block
     * of a solid goal (the one nearest the bot), or null.
     */
    record GoalCell(String solid, boolean walledIn, int[] freeNear) {
        static final GoalCell OPEN = new GoalCell(null, false, null);
    }

    /** 0.31.0 (5): how far up the same column, and how far around, a goto into a solid block looks for a free spot. */
    static int freeUp() { return Tuning.i("walkEnd.freeUp"); }
    static int freeAround() { return Tuning.i("walkEnd.freeAround"); }

    /**
     * 0.31.0 (5): a goto whose target is inside a block goes to the nearest standable cell instead: first the same column
     * upward (up to {@link #FREE_UP}), then the nearest cell within {@link #FREE_AROUND} (straight distance; a tie
     * prefers higher, then the lower x, z). Null when there is none.
     */
    static int[] freeSpotFor(int x, int y, int z, java.util.function.Predicate<int[]> standable) {
        for (int dy = 1; dy <= freeUp(); dy++) {
            int[] c = {x, y + dy, z};
            if (standable.test(c)) return c;
        }
        int[] best = null;
        long bd = Long.MAX_VALUE;
        for (int dx = -freeAround(); dx <= freeAround(); dx++)
            for (int dy = freeAround(); dy >= -freeAround(); dy--)
                for (int dz = -freeAround(); dz <= freeAround(); dz++) {
                    if ((dx | dy | dz) == 0) continue;
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d > (long) freeAround() * freeAround() || d > bd) continue;
                    int[] c = {x + dx, y + dy, z + dz};
                    if (d == bd && best != null && (c[1] < best[1] || (c[1] == best[1] && (c[0] > best[0] || (c[0] == best[0] && c[2] >= best[2]))))) continue;
                    if (!standable.test(c)) continue;
                    bd = d;
                    best = c;
                }
        return best;
    }

    /** Within this straight distance of the goal the walk counts as at it, whatever the goal cell is. */
    static double atGoal() { return Tuning.get("walkEnd.atGoal"); }

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
        if (far <= atGoal()) return g.solid() != null ? nextTo(dest, g.solid()) : null;
        if (g.solid() != null) {
            if (g.freeNear() != null && Math.sqrt(sq(me, g.freeNear())) <= atGoal()) return nextTo(dest, g.solid());
            return "error: couldn't get there (goal is inside " + g.solid() + " at " + xyz(dest) + "; stopped " + Math.round(far) + " blocks away)";
        }
        if (g.walledIn()) return "error: couldn't get there (goal is walled in at " + xyz(dest) + "; stopped " + Math.round(far) + " blocks away)";
        return null;
    }

    /**
     * job-walkend (run JBD 09:22:47): a walk that ended "goal is walled in" while the bot stands in its own shaft (rock over
     * the head or a hole round the feet, Jobs.rockOverHead / holeDepth; rule 15: 0 = not in one) is a dig to the cell, not
     * a walk. Pure.
     */
    static boolean shaftDig(String why, int rockOverHead, int holeDepth) {
        return why != null && why.contains("goal is walled in") && (rockOverHead > 0 || holeDepth > 0);
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

}
