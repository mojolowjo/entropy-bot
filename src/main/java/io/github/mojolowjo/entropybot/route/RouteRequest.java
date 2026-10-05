package io.github.mojolowjo.entropybot.route;

/**
 * A planning request.
 *
 * @param goalRadius    0 = the goal cell itself; r = any standable cell within r blocks (straight line), for
 *                      {@code GoalNear}/{@code GoalGetToBlock}.
 * @param moves         moves for the start and goal boxes (box plus margin), on the planning thread; may be null:
 *                      then the start and goal boxes are estimated by straight-line distance x costHeuristic.
 * @param costHeuristic Baritone's {@code Settings.costHeuristic} (review R6).
 */
public record RouteRequest(int dim, Cell start, Cell goal, int goalRadius, CellMoves moves, double costHeuristic) {
}
