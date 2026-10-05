package io.github.mojolowjo.entropybot.route;

/**
 * Plans over the door graph of a {@link RouteStore}: one backward Dijkstra from the goal (cells within the goal box,
 * then doors), giving every door its ticks to the goal ({@link CostToGo}); then the start box's cells to its doors
 * pick the door path. Stale boxes are used. Run it on a worker, not the game thread (well under 100 ms for a few
 * thousand doors). Thread-safe: no state between calls.
 */
public interface Router {
    RoutePlan plan(RouteRequest request);
}
