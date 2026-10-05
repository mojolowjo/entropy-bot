package io.github.mojolowjo.entropybot.routewalk;

import io.github.mojolowjo.entropybot.route.CostToGo;
import io.github.mojolowjo.entropybot.route.RouteGoalMath;

/**
 * The loader-neutral part of {@code baritone.RouteGoal}: a wrapped goal plus the plan's cost-to-go table.
 * {@code isInGoal} is the wrapped goal's; {@code heuristic} is {@link RouteGoalMath#heuristic} with the wrapped goal's
 * own heuristic as the floor and fallback (never infinity, never an exception). Immutable, safe on Baritone's path
 * thread. The Baritone {@code Goal} adapter only forwards to it.
 */
public final class RouteGoalCore {
    /** The wrapped goal, as the two methods Baritone's {@code Goal} has. */
    public interface Plain {
        boolean isInGoal(int x, int y, int z);

        double heuristic(int x, int y, int z);
    }

    private final Plain plain;
    private final CostToGo table;
    private final double costHeuristic;

    public RouteGoalCore(Plain plain, CostToGo table, double costHeuristic) {
        this.plain = plain;
        this.table = table == null ? CostToGo.empty() : table;
        this.costHeuristic = costHeuristic;
    }

    public CostToGo table() { return table; }

    public double costHeuristic() { return costHeuristic; }

    public boolean isInGoal(int x, int y, int z) {
        return plain.isInGoal(x, y, z);
    }

    public double heuristic(int x, int y, int z) {
        double p = plain.heuristic(x, y, z);
        try {
            double h = RouteGoalMath.heuristic(table, x, y, z, p, costHeuristic);
            return h < Double.POSITIVE_INFINITY ? h : p;
        } catch (RuntimeException e) {
            return p;      // never let the map break a path search
        }
    }
}
