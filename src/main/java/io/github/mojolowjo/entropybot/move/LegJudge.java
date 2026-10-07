package io.github.mojolowjo.entropybot.move;

/**
 * 0.23.3 movement: the small rules about a leg. Pure.
 * <ul>
 *   <li>{@link #resume}: a long walk paused by a fight or a meal resumes its leg (no new route) when the bot stands
 *       within {@link #RESUME_WITHIN} blocks of where it paused; further off, the route is planned again from there.</li>
 *   <li>{@link #ranLong}: a leg whose walked distance is over {@link #LONG_FACTOR} times the straight distance to its
 *       waypoint was a poor choice: its map edge gets a penalty and the whole route is planned again.</li>
 *   <li>{@link #replanWhole}: a failed leg or one that ran long plans the whole route again from the current spot.</li>
 * </ul>
 */
public final class LegJudge {
    private LegJudge() {}

    public static final double RESUME_WITHIN = 3;
    public static final double LONG_FACTOR = 2;
    /** Legs shorter than this are never judged long (a few steps around a tree are no detour). */
    public static final double MIN_JUDGED = 8;

    public static boolean resume(int[] pausedAt, int[] now) {
        if (pausedAt == null || now == null) return false;
        double dx = now[0] - pausedAt[0], dy = now[1] - pausedAt[1], dz = now[2] - pausedAt[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= RESUME_WITHIN;
    }

    public static boolean ranLong(double walked, double straight) {
        return straight >= MIN_JUDGED && walked > LONG_FACTOR * straight;
    }

    public static boolean replanWhole(boolean failed, boolean ranLong) {
        return failed || ranLong;
    }
}
