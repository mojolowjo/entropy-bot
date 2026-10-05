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

    /** The stuck watchdog's window for a routed goal-mode walk (review S1): 90 s at 20 ticks a second. */
    public static final long ROUTED_GOAL_STUCK_TICKS = 1800;

    /**
     * The stuck watchdog's window (review S1). A routed walk in goal mode follows RouteGoal's detour, which can lead away
     * from the real goal in a straight line for a while (around a lake, a ravine): it gets {@link #ROUTED_GOAL_STUCK_TICKS}.
     * Everything else (plain walks, legs mode, which measures to the current leg's end) keeps {@code plainTicks}.
     *
     * @param routedGoalMode the walk is routed, in goal mode, and measured against the real goal (no leg end).
     */
    public static long stuckTicks(boolean routedGoalMode, long plainTicks) {
        return routedGoalMode ? Math.max(plainTicks, ROUTED_GOAL_STUCK_TICKS) : plainTicks;
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
