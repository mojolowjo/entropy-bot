package io.github.mojolowjo.entropybot.routewalk;

/**
 * "Use the router for this walk?" (plan section 4.1), as a pure function. The router is asked only when every
 * condition holds; otherwise the walk is exactly today's plain Baritone walk. {@code why} says which condition failed
 * (for {@code route status}, the trip log and the debug log), "ok" when the router is used.
 */
public final class RouteDecision {
    private RouteDecision() {
    }

    /** Walks shorter than this (straight line, blocks) stay plain: Baritone alone does them in one short search. */
    public static final double MIN_DISTANCE = 32;

    public record Result(boolean use, String why) {
    }

    /**
     * @param enabled          the feature is on ({@code route on}), or a test trip asks for a routed mode.
     * @param plannerAvailable {@code RoutePlanner.available()}.
     * @param breakingOn       Baritone's allowBreak (or allowPlace) is on: a mining walk, never routed (review R5).
     * @param startDim         the bot's dimension id ({@code RoutePlannerHolder.dimId}); stage 1 routes only dim 0.
     * @param goalDim          the goal's dimension id.
     * @param startInArea      the bot stands inside one of the owner's areas.
     * @param goalInArea       the goal lies inside one of them.
     * @param distance         straight-line blocks from the bot to the goal.
     * @param fixedGoal        the goal is a fixed spot (not a moving player, not "goto x z").
     */
    public static Result decide(boolean enabled, boolean plannerAvailable, boolean breakingOn, int startDim, int goalDim,
                                boolean startInArea, boolean goalInArea, double distance, boolean fixedGoal) {
        if (!enabled) return new Result(false, "route off");
        if (!fixedGoal) return new Result(false, "not a fixed goal");
        if (breakingOn) return new Result(false, "breaking is on");
        if (startDim != goalDim) return new Result(false, "another dimension");
        if (startDim != 0) return new Result(false, "not the overworld");
        if (!startInArea || !goalInArea) return new Result(false, startInArea ? "goal outside my areas" : "start outside my areas");
        if (!(distance > MIN_DISTANCE)) return new Result(false, "short walk");
        if (!plannerAvailable) return new Result(false, "planner not running");
        return new Result(true, "ok");
    }
}
