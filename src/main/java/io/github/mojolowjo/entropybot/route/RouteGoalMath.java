package io.github.mojolowjo.entropybot.route;

/**
 * The heuristic R3's {@code RouteGoal.heuristic(x, y, z)} returns. Pure, allocation-free, safe on Baritone's path
 * thread.
 *
 * <p>For the box of (x, y, z): the cheapest over its doors of
 * {@code blocks to the door's cell bounds * costHeuristic + that door's ticks to the goal}, never below {@code plain}.
 * Review R3 (prefer underestimates): the plain heuristic is used, never infinity, when there is no table, the box is
 * unknown, the box is stale, the point is in the goal's own box, or no door of the box has a known cost.
 * Review R6: {@code costHeuristic} is Baritone's {@code Settings.costHeuristic} (read by {@code GoalXZ}), passed in,
 * never the literal 3.563.
 */
public final class RouteGoalMath {
    private RouteGoalMath() {
    }

    /**
     * @param plain         the wrapped goal's own heuristic at (x, y, z).
     * @param costHeuristic ticks per block for the walk to the door, Baritone's {@code costHeuristic} setting.
     */
    public static double heuristic(CostToGo table, int x, int y, int z, double plain, double costHeuristic) {
        if (table == null || table.size() == 0 || table.inGoalBox(x, y, z)) return plain;
        CostToGo.BoxCosts b = table.box(x, y, z);
        if (b == null || b.stale()) return plain;
        double best = Double.POSITIVE_INFINITY;
        Door[] doors = b.doors();
        double[] ticks = b.exitTicks();
        for (int e = 0; e < doors.length; e++) {
            double t = ticks[e];
            if (!(t < Double.POSITIVE_INFINITY)) continue;
            double v = doors[e].distanceTo(x, y, z) * costHeuristic + t;
            if (v < best) best = v;
        }
        if (!(best < Double.POSITIVE_INFINITY)) return plain;
        return Math.max(plain, best);
    }
}
