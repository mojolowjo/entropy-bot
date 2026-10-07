package io.github.mojolowjo.entropybot.move;

import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.routewalk.LegsSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.23.3 movement: a far goal cut into legs of at most {@link #MAX_LEG} blocks for {@code LongRouteProcess}. With the
 * route map's door cells the legs follow them ({@link LegsSplitter}); with none, straight-line points every
 * {@link #MAX_LEG} blocks whose height is unknown ({@link Leg#xzOnly}: Baritone's {@code GoalXZ}). Pure, no game classes.
 */
public final class LegPlan {
    private LegPlan() {}

    public static final double MAX_LEG = 36;
    /** A walk goes through the long-route process when the goal is more than this far (flat) ... */
    public static final double ASSIST_FLAT = 48;
    /** ... or more than this many levels up or down. */
    public static final int ASSIST_DY = 10;

    /** A leg's end; xzOnly: only x and z matter (no map height for it); last: the real goal. */
    public record Leg(Cell end, boolean xzOnly, boolean last) {}

    /** Whether a walk from me to dest goes through the long-route process. */
    public static boolean needsAssist(int[] me, int[] dest) {
        if (me == null || dest == null) return false;
        double dx = dest[0] - me[0], dz = dest[2] - me[2];
        return Math.sqrt(dx * dx + dz * dz) > ASSIST_FLAT || Math.abs(dest[1] - me[1]) > ASSIST_DY;
    }

    /** The legs from start to goal: never empty, the last one is the goal. waypoints: the route map's door cells (may be empty). */
    public static List<Leg> legs(Cell start, List<Cell> waypoints, Cell goal) {
        List<Leg> out = new ArrayList<>();
        if (waypoints != null && !waypoints.isEmpty()) {
            List<Cell> ends = LegsSplitter.split(start, waypoints, goal, MAX_LEG, LegsSplitter.GOAL_SNAP);
            for (int i = 0; i < ends.size(); i++) out.add(new Leg(ends.get(i), false, i == ends.size() - 1));
            return out;
        }
        double dx = goal.x() - start.x(), dz = goal.z() - start.z();
        double flat = Math.sqrt(dx * dx + dz * dz);
        int n = (int) Math.ceil(flat / MAX_LEG);
        for (int i = 1; i < n; i++) {
            double f = i / (double) n;
            out.add(new Leg(new Cell((int) Math.round(start.x() + dx * f), start.y(), (int) Math.round(start.z() + dz * f)), true, false));
        }
        out.add(new Leg(goal, false, true));
        return out;
    }

    /** Distance from (x,y,z) to a leg's end (flat for an xz-only leg). */
    public static double distTo(Leg l, int x, int y, int z) {
        double dx = l.end().x() - x, dz = l.end().z() - z, dy = l.xzOnly() ? 0 : l.end().y() - y;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A leg (not the last) counts as reached this close to its end, so the next goal is set while the bot still walks. */
    public static final double ADVANCE_AT = 10;
}
