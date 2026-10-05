package io.github.mojolowjo.entropybot.route;

/**
 * The route planner as the walking side (R3) sees it; R2's RouteRuntime implements it and installs itself with
 * {@link RoutePlannerHolder#set}. All methods are called on the game thread and must return at once (the plan itself
 * runs on a worker). Never throws: a failure is a completed future with a non-OK {@link RoutePlan}, or an exceptional
 * one, and the walk then goes plain.
 */
public interface RoutePlanner {
    /** False when the planner can't plan at all (Baritone internals missing, not started yet): every walk is plain. */
    boolean available();

    /**
     * Plans from start to goal (same dim). goalRadius: 0 = the goal cell, r = any standable cell within r blocks.
     * Boxes missing on the way go to the now queue. The caller waits at most 300 ms, then walks plain.
     */
    java.util.concurrent.CompletableFuture<RoutePlan> plan(int dim, Cell start, Cell goal, int goalRadius);

    /** The counters for {@code route status} and {@code check}. */
    RouteStats stats();

    /**
     * Marks the boxes on the straight stretch a to b stale and queues them (now queue) to be built again; with a == b,
     * the box of a and its face neighbours. A walk that failed where the map said possible calls it.
     */
    void requestBuildAlong(int dim, Cell a, Cell b);
}
