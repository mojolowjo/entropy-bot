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
 * Entropy Bot 2026-11-27: the fork's PathCost (behavior), over our AStarPathFinder and a PriceWorld instead of
 * PathingBehavior.createPathfinder and Baritone's executor.
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.search.AStarPathFinder;
import io.github.mojolowjo.entropybot.nav.search.Favoring;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.PathCalculationResult;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.search.goals.HasGoalPos;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Entropy path cost: our planner's A* run with a budget, from the world's start to a goal, never walked. The synchronous
 * estimate runs on the caller's (game) thread with {@link PriceConfig#costMaxNodes} / {@link PriceConfig#costMaxMs}; the
 * background one is built on the game thread and run on the world's executor with the caller's budget. Pure but for block
 * states (JUnit: PathCostTest). Loader notes: none.
 */
public final class PathCost implements IPathCost {
    private final PriceWorld world;
    private final PriceConfig config;

    public PathCost(PriceWorld world, PriceConfig config) {
        this.world = world;
        this.config = config;
    }

    @Override
    public Answer estimate(Goal goal, int budgetTicks) {
        return estimate(goal, budgetTicks, config.costMaxMs());
    }

    @Override
    public Answer estimate(Goal goal, int budgetTicks, long maxMs) {
        if (!world.onGameThread()) {
            throw new IllegalStateException("IPathCost.estimate runs on the game thread only");
        }
        BetterBlockPos start = world.start();
        if (goal == null || start == null || world.world() == null) {
            return Answer.none(Answer.ERROR);
        }
        long ms = Math.max(1, Math.min(maxMs, config.costMaxMs()));
        AStarPathFinder finder = finder(start, goal, world.context().context());
        finder.costQuery(config.costMaxNodes(), budgetTicks);
        return answer(finder, finder.calculate(ms, ms), goal, budgetTicks);
    }

    @Override
    public CompletableFuture<Answer> estimateInBackground(Goal goal, int budgetTicks, long maxMs, int maxNodes) {
        if (!world.onGameThread()) {
            throw new IllegalStateException("IPathCost.estimateInBackground is called on the game thread (it runs the search on the executor)");
        }
        BetterBlockPos start = world.start();
        if (goal == null || start == null || world.world() == null) {
            return CompletableFuture.completedFuture(Answer.none(Answer.ERROR));
        }
        SearchContext context = world.context().context();
        if (goal instanceof HasGoalPos rp && !context.bsi.worldContainsLoadedChunk(rp.getGoalPos().getX(), rp.getGoalPos().getZ())) {
            return CompletableFuture.completedFuture(Answer.none(Answer.CHUNKS)); // the goal's own chunk: not known
        }
        AStarPathFinder finder = finder(start, goal, context);
        finder.costQuery(Math.max(1, maxNodes), budgetTicks);
        long ms = Math.max(1, maxMs);
        CompletableFuture<Answer> out = new CompletableFuture<>();
        world.executor().execute(() -> {
            try {
                out.complete(answer(finder, finder.calculate(ms, ms), goal, budgetTicks));
            } catch (RuntimeException e) {
                out.completeExceptionally(e); // the caller says it; never a quiet "no path"
            }
        });
        return out;
    }

    /** No previous path, so no favouring; the goal as given (the fork's createPathfinder for a cost query). */
    private AStarPathFinder finder(BetterBlockPos start, Goal goal, SearchContext context) {
        return new AStarPathFinder(start, start.x, start.y, start.z, goal, Favoring.none(), context);
    }

    /** One finished search to one answer (the cells ride along). */
    private Answer answer(AStarPathFinder finder, PathCalculationResult r, Goal goal, int budgetTicks) {
        if (r.getType() == PathCalculationResult.Type.EXCEPTION) {
            world.say("Entropy path cost: the search to " + goal + " threw " + r.failure()); // Baritone said it in chat; never quiet
            return Answer.none(Answer.ERROR);
        }
        Optional<Path> path = r.getPath();
        IPathCost.Stop stop = PriceQueue.stopOf(r, finder.stop());
        if (path.isEmpty()) {
            return Answer.of(stop, false, Double.NaN, Double.NaN, budgetTicks);
        }
        BetterBlockPos dest = path.get().getDest();
        double cost = finder.nodeCost(dest);
        double rest = goal.isInGoal(dest) ? 0 : goal.heuristic(dest);
        if (Double.isNaN(cost)) {
            cost = path.get().ticksRemainingFrom(0); // a node the search did not keep: the steps' own costs
        }
        return Answer.of(stop, true, cost, rest, budgetTicks).withPath(path.get().positions());
    }
}
