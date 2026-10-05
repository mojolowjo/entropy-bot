package io.github.mojolowjo.entropybot.route;

import java.util.List;

/**
 * The answer of {@link Router#plan}.
 *
 * @param status what happened; only {@link Status#OK} has a door path, but {@code table} is usable for the heuristic
 *               whenever it is non-empty (also with {@link Status#NO_START_BOX} or {@link Status#UNREACHABLE}).
 * @param ticks  estimated ticks start to goal along {@code path} (+infinity unless OK).
 * @param path   the doors left, in order (start box first); empty when start and goal share a box.
 * @param table  cost-to-go for every known door (never null; {@link CostToGo#empty()} when nothing is known).
 * @param nodes  door-graph nodes settled (for {@code route status}).
 * @param micros planning time.
 * @param reason one line for logs and whispers ("ok", "goal box not built yet", ...).
 */
public record RoutePlan(Status status, double ticks, List<Step> path, CostToGo table, int nodes, long micros,
                        String reason) {

    public enum Status {
        OK,
        /** The goal's box is not in the store: queue it on the now queue, walk plain. */
        NO_GOAL_BOX,
        /** The goal box is known but the start box is not: the table still guides from the boxes it knows. */
        NO_START_BOX,
        /** Both are known but no door path joins them in the map. Walk plain (the map may be wrong). */
        UNREACHABLE,
        /** Start and goal in different dims, or another bad request. */
        BAD_REQUEST,
        /** The planner threw (counted in {@link RouteCounters}, logged); walk plain. */
        ERROR
    }

    /** One door to leave through: its box, index in that box, and its representative cell (a legs-mode waypoint). */
    public record Step(SectionKey box, int door, Cell rep) {
    }

    public RoutePlan {
        path = List.copyOf(path);
        if (table == null) table = CostToGo.empty();
    }
}
