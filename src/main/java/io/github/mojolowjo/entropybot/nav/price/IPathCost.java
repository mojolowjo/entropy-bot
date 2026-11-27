/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Entropy Bot 2026-11-27: the fork's IPathCost (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalGetToBlock;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalNear;
import net.minecraft.core.BlockPos;

/**
 * Entropy path cost: what a walk would cost, BEFORE walking. Runs Baritone's own A* (the same pathfinder, calculation
 * context, settings and CostAdvisor policy hook a real path uses) from the player's feet to a goal, synchronously, with
 * a node budget ({@code pathCostMaxNodes}), a time budget ({@code pathCostMaxMs}) and a cost ceiling (budgetTicks).
 * It never moves the player and never starts, cancels or changes a process or the current path.
 * <p>
 * Thread rule: call on the game (client) thread only; a call from another thread throws IllegalStateException.
 * Entropy z41: {@link #estimateInBackground} builds the search on the game thread and runs it on Baritone's executor.
 */
public interface IPathCost {

    /** Why a search stopped (the input of {@link Answer#of}). */
    enum Stop { GOAL, EXHAUSTED, NODES, TIME, COST, CHUNKS, CANCELLED, ERROR }

    /**
     * The answer: {ticks, complete} for a path that reaches the goal, {ticks, incomplete} for a partial path within
     * budget (its cost plus the heuristic from its end to the goal), or {noPath, reason}.
     */
    final class Answer {
        public static final String BUDGET = "budget";
        public static final String UNREACHABLE = "unreachable";
        public static final String ERROR = "error";
        /** Entropy z41: the search reached chunks that are not loaded (or the goal's own chunk is not loaded): the path is not known. */
        public static final String CHUNKS = "chunks not loaded";

        /** Ticks of the path (plus the heuristic rest when incomplete); NaN when there is no path. */
        public final double ticks;
        public final boolean complete;
        public final boolean noPath;
        /** Null with a path; "budget", "unreachable", "chunks not loaded" or "error" without one. */
        public final String reason;

        /** Entropy z40: the cells of the path behind the answer, start first (empty: no path, or not given). */
        public final java.util.List<BlockPos> path;

        private Answer(double ticks, boolean complete, boolean noPath, String reason) {
            this(ticks, complete, noPath, reason, java.util.List.of());
        }

        private Answer(double ticks, boolean complete, boolean noPath, String reason, java.util.List<BlockPos> path) {
            this.ticks = ticks;
            this.complete = complete;
            this.noPath = noPath;
            this.reason = reason;
            this.path = path;
        }

        /** This answer with the path's cells (a no-path answer keeps none). */
        public Answer withPath(java.util.List<? extends BlockPos> cells) {
            return noPath || cells == null ? this : new Answer(ticks, complete, noPath, reason, java.util.List.copyOf(cells));
        }

        public static Answer complete(double ticks) {
            return new Answer(ticks, true, false, null);
        }

        public static Answer about(double ticks) {
            return new Answer(ticks, false, false, null);
        }

        public static Answer none(String reason) {
            return new Answer(Double.NaN, false, true, reason);
        }

        /**
         * The rule from one search to one answer (pure, unit-tested).
         *
         * @param stop          why the search stopped
         * @param hasPath       the search returned a path (to the goal, or a segment toward it)
         * @param destCost      A* cost in ticks of the path's last node (policy multipliers included)
         * @param destHeuristic the goal's heuristic from that node (0 at the goal)
         * @param budgetTicks   the caller's cost ceiling; a partial estimate above it is "budget"
         */
        public static Answer of(Stop stop, boolean hasPath, double destCost, double destHeuristic, double budgetTicks) {
            switch (stop) {
                case GOAL:
                    return hasPath ? complete(destCost) : none(ERROR);
                case EXHAUSTED:
                    return none(UNREACHABLE); // every reachable node was opened and none is in the goal
                case CANCELLED:
                case ERROR:
                    return none(ERROR);
                case CHUNKS: // Entropy z41: the search reached unloaded chunks before it reached the goal: not known
                    return none(CHUNKS);
                default: // NODES, TIME, COST: the search stopped before it could tell
                    if (!hasPath) {
                        return none(BUDGET);
                    }
                    double est = destCost + Math.max(0, destHeuristic);
                    return est <= budgetTicks ? about(est) : none(BUDGET);
            }
        }

        @Override
        public String toString() {
            return noPath ? "noPath(" + reason + ")" : (complete ? "" : "about ") + Math.round(ticks) + " ticks";
        }
    }

    /**
     * Baritone's A* cost in ticks from the player's feet to the goal, within budget. Game thread only.
     *
     * @param budgetTicks cost ceiling: nodes dearer than this are not opened
     */
    Answer estimate(Goal goal, int budgetTicks);

    /** As {@link #estimate(Goal, int)} with a smaller time budget (min of maxMs and pathCostMaxMs). */
    Answer estimate(Goal goal, int budgetTicks, long maxMs);

    /**
     * Entropy z41: the FULL search in the background. Called on the game thread: the calculation context, the start and the
     * pathfinder are built here, as Baritone builds a real path's (CalculationContext for use on another thread); the A*
     * then runs on Baritone's own executor with this call's budget (maxMs, maxNodes, budgetTicks), not pathCostMaxMs.
     * The future completes on that executor thread: {complete ticks}, or "about" (partial + heuristic) when the budget ran
     * out with a segment, or noPath with budget / unreachable / chunks not loaded / error. A goal whose own chunk is not
     * loaded answers "chunks not loaded" at once (no search). A call off the game thread throws IllegalStateException.
     */
    java.util.concurrent.CompletableFuture<Answer> estimateInBackground(Goal goal, int budgetTicks, long maxMs, int maxNodes);

    /** Cost to stand next to the block (Baritone's GoalGetToBlock). */
    default Answer estimateToBlock(BlockPos pos, int budgetTicks) {
        return estimate(new GoalGetToBlock(pos), budgetTicks);
    }

    /** Cost to come within radius blocks of pos (Baritone's GoalNear). */
    default Answer estimateNear(BlockPos pos, int radius, int budgetTicks) {
        return estimate(new GoalNear(pos, radius), budgetTicks);
    }
}
