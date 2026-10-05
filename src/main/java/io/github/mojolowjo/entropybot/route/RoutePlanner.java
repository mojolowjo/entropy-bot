package io.github.mojolowjo.entropybot.route;

/**
 * The route map as the walking side (R3) sees it. R2's {@code RouteRuntime} implements it; R3 codes against this
 * interface only. Every method is safe to call on the game thread and never throws.
 */
public interface RoutePlanner {
    /**
     * True when plans can be made now: Baritone's internals are there, breaking is off, the feature is on and a world
     * is loaded. When false, {@link #plan} still answers (with {@link RoutePlan.Status#BAD_REQUEST}).
     */
    boolean available();

    /**
     * Plans on a background thread. The future always completes normally (an error or "not available" comes back as
     * {@link RoutePlan.Status#BAD_REQUEST} with the reason); the caller decides how long to wait.
     *
     * @param goalRadius 0 = the goal cell itself; r = any standable cell within r blocks.
     */
    java.util.concurrent.CompletableFuture<RoutePlan> plan(int dim, Cell start, Cell goal, int goalRadius);

    /** The counters for {@code route status} and {@code check}. */
    RouteStats stats();

    /** Puts the boxes on the stretch a to b (inside the areas) on the now queue, e.g. after a failed walk. */
    void requestBuildAlong(int dim, Cell a, Cell b);
}
