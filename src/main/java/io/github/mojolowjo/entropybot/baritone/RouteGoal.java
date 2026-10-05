package io.github.mojolowjo.entropybot.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import io.github.mojolowjo.entropybot.route.CostToGo;
import io.github.mojolowjo.entropybot.routewalk.RouteGoalCore;

/**
 * Mode "goal" of the router (plan section 4.3): the normal goal (GoalBlock, GoalGetToBlock, GoalNear) with a better
 * estimate of the ticks left. A thin adapter over {@link RouteGoalCore} (pure, JUnit-tested): {@code isInGoal} is the
 * wrapped goal's (the real goal), {@code heuristic} the cost-to-go table's estimate, the wrapped goal's own wherever
 * the table knows nothing, never infinity (review R3).
 *
 * <p>Called on Baritone's path thread: the table is immutable and {@code costHeuristic} is read once here, from
 * Baritone's settings (review R6), never the literal 3.563.
 *
 * <p>Loader API: Baritone's {@code api} package only ({@code Goal}, {@code BaritoneAPI.getSettings()}); the same in its
 * Fabric and Forge jars.
 */
public final class RouteGoal implements Goal {
    private final Goal plain;
    private final RouteGoalCore core;

    public RouteGoal(Goal plain, CostToGo table) {
        this.plain = plain;
        this.core = new RouteGoalCore(new RouteGoalCore.Plain() {
            @Override
            public boolean isInGoal(int x, int y, int z) { return plain.isInGoal(x, y, z); }

            @Override
            public double heuristic(int x, int y, int z) { return plain.heuristic(x, y, z); }
        }, table, costHeuristicSetting());
    }

    /** Baritone's costHeuristic setting (3.563 by default); that default when the settings can't be read. */
    public static double costHeuristicSetting() {
        try {
            Double v = BaritoneAPI.getSettings().costHeuristic.value;
            if (v != null && v > 0 && !v.isInfinite() && !v.isNaN()) return v;
        } catch (Throwable ignored) {
            // the settings not there: the default below
        }
        return 3.563;
    }

    public Goal plain() { return plain; }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return core.isInGoal(x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return core.heuristic(x, y, z);
    }

    @Override
    public double heuristic() {
        return plain.heuristic();
    }

    @Override
    public String toString() {
        return "RouteGoal{" + plain + ", " + core.table().size() + " boxes}";
    }
}
