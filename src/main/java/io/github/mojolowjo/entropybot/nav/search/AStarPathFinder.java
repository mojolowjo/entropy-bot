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
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>. *
 * Modified for Entropy Bot 2026-11-27: the fork's version (cost query limits, stop reasons, cost advisor per-cell move
 * prices, lava wading, dimension bounds and border from the context) over SearchContext and MoveCatalog; settings from
 * NavConfig; no slowPath, no printing
 */

package io.github.mojolowjo.entropybot.nav.search;

import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.cost.MutableMoveResult;
import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalXZ;
import io.github.mojolowjo.entropybot.nav.search.openset.BinaryHeapOpenSet;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.BetterWorldBorder;
import net.minecraft.tags.FluidTags;

import java.util.Optional;

/**
 * The actual A* pathfinding
 *
 * <p>Entropy Bot (nav step 6): the fork's A*. The order of moves, the tie breaks, the favouring, the advisor's
 * per-cell prices and the lava charge are the fork's, so on the same world it finds the same route at the same cost
 * ({@code SearchMatchForkTest}, the milestone). {@link #costQuery} bounds a search by nodes and cost instead of the
 * clock (pricing a route, and repeatable tests). Pure (JUnit: SearchMatchForkTest). Loader notes: FluidTags (common
 * side, Mojang names).
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {

    private final Favoring favoring;
    private final SearchContext calcContext;
    private int maxNodes = Integer.MAX_VALUE;
    private double maxCost = Double.POSITIVE_INFINITY;
    private Stop stop = Stop.CANCELLED;
    private volatile int lastNodes;
    private volatile int lastEmptyChunk;

    public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, SearchContext context) {
        super(realStart, startX, startY, startZ, goal, context);
        if (context.config.search().costHeuristic() != GoalXZ.COST_HEURISTIC) {
            throw new IllegalArgumentException("costHeuristic " + context.config.search().costHeuristic() + ": the goals use Baritone's "
                    + GoalXZ.COST_HEURISTIC + " (GoalXZ.COST_HEURISTIC); another heuristic is not supported yet");
        }
        this.favoring = favoring;
        this.calcContext = context;
    }

    /** Fork: stop after {@code maxNodes} opened nodes or when the cheapest open node costs more than {@code maxCost}. */
    public AStarPathFinder costQuery(int maxNodes, double maxCost) {
        this.maxNodes = maxNodes;
        this.maxCost = maxCost;
        return this;
    }

    @Override
    public Stop stop() {
        return stop;
    }

    public int nodesOpened() {
        return lastNodes;
    }

    public int emptyChunkHits() {
        return lastEmptyChunk;
    }

    public int nodesKnown() {
        return mapSize();
    }

    public BetterBlockPos bestNode() {
        PathNode n = bestSoFar[0];
        return n == null ? null : new BetterBlockPos(n.x, n.y, n.z);
    }

    @Override
    protected Optional<Path> calculate0(long primaryTimeout, long failureTimeout) {
        int minY = calcContext.dimensionMinY();
        int height = calcContext.dimensionHeight();
        startNode = getNodeAtPosition(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];// keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
        MutableMoveResult res = new MutableMoveResult();
        BetterWorldBorder worldBorder = calcContext.searchBorder();
        long startTime = System.currentTimeMillis();
        long primaryTimeoutTime = startTime + primaryTimeout;
        long failureTimeoutTime = startTime + failureTimeout;
        boolean failing = true;
        int numNodes = 0;
        int numEmptyChunk = 0;
        boolean isFavoring = !favoring.isEmpty();
        boolean hasAdvisor = calcContext.advisor != null && calcContext.pricesMoves;
        int timeCheckInterval = 1 << 6;
        NavConfig.Search s = calcContext.config.search();
        int pathingMaxChunkBorderFetch = s.pathingMaxChunkBorderFetch(); // grab all settings beforehand so that changing settings during pathing doesn't cause a crash or unpredictable behavior
        double minimumImprovement = s.minimumImprovementRepropagation() ? MIN_IMPROVEMENT : 0;
        MoveCatalog[] allMoves = MoveCatalog.values();
        double lavaCost = calcContext.config.moves().lavaCellCost();
        stop = Stop.EXHAUSTED;
        while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested) {
            if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
                long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    stop = Stop.TIME;
                    break;
                }
            }
            if (numNodes >= maxNodes) {
                stop = Stop.NODES;
                break;
            }
            if (openSet.peekLowestCombinedCost() > maxCost) {
                stop = Stop.COST;
                break;
            }
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                stop = Stop.GOAL;
                lastNodes = numNodes;
                lastEmptyChunk = numEmptyChunk;
                return Optional.of(Path.fromNodes(realStart, startNode, currentNode, numNodes, goal, calcContext));
            }
            for (MoveCatalog moves : allMoves) {
                int newX = currentNode.x + moves.xOffset;
                int newZ = currentNode.z + moves.zOffset;
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.isLoaded(newX, newZ)) {
                    // only need to check if the destination is a loaded chunk if it's in a different chunk than the start of the movement
                    if (!moves.dynamicXZ) { // only increment the counter if the movement would have gone out of bounds guaranteed
                        numEmptyChunk++;
                    }
                    continue;
                }
                if (!moves.dynamicXZ && !worldBorder.entirelyContains(newX, newZ)) {
                    continue;
                }
                if (currentNode.y + moves.yOffset > height || currentNode.y + moves.yOffset < minY) {
                    continue;
                }
                res.reset();
                moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                double actionCost = res.cost;
                if (actionCost >= ActionCosts.COST_INF) {
                    continue;
                }
                if (actionCost <= 0 || Double.isNaN(actionCost)) {
                    throw new IllegalStateException(String.format("%s from %s %s %s calculated implausible cost %s",
                            moves, currentNode.x, currentNode.y, currentNode.z, actionCost));
                }
                // check destination after verifying it's not COST_INF -- some movements return a static IMPOSSIBLE object with COST_INF and destination being 0,0,0 to avoid allocating a new result for every failed calculation
                if (moves.dynamicXZ && !worldBorder.entirelyContains(res.x, res.z)) { // see issue #218
                    continue;
                }
                if (!moves.dynamicXZ && (res.x != newX || res.z != newZ)) {
                    throw new IllegalStateException(String.format("%s from %s %s %s ended at x z %s %s instead of %s %s",
                            moves, currentNode.x, currentNode.y, currentNode.z, res.x, res.z, newX, newZ));
                }
                if (!moves.dynamicY && res.y != currentNode.y + moves.yOffset) {
                    throw new IllegalStateException(String.format("%s from %s %s %s ended at y %s instead of %s",
                            moves, currentNode.x, currentNode.y, currentNode.z, res.y, (currentNode.y + moves.yOffset)));
                }
                long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                if (isFavoring) {
                    // see issue #18
                    actionCost *= favoring.calculate(hashCode);
                }
                if (hasAdvisor) { // fork: the mod's per-cell move price
                    actionCost = MoveCost.applyMove(actionCost, calcContext.moveMultiplier(res.x, res.y, res.z));
                    actionCost = MoveCost.addMove(actionCost, calcContext.moveExtra(res.x, res.y, res.z));
                    if (actionCost >= ActionCosts.COST_INF) {
                        continue;
                    }
                }
                if (lavaCost > 0) { // fork (LavaWade): each lava cell the body ends in costs lavaCellCost
                    actionCost = MoveCost.lavaCharge(actionCost, lavaCost,
                            calcContext.get(res.x, res.y, res.z).getFluidState().is(FluidTags.LAVA),
                            calcContext.get(res.x, res.y + 1, res.z).getFluidState().is(FluidTags.LAVA));
                }
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                double tentativeCost = currentNode.cost + actionCost;
                if (neighbor.cost - tentativeCost > minimumImprovement) {
                    neighbor.previous = currentNode;
                    neighbor.cost = tentativeCost;
                    neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);//dont double count, dont insert into open set if it's already there
                    }
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                        if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                            bestHeuristicSoFar[i] = heuristic;
                            bestSoFar[i] = neighbor;
                            if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                failing = false;
                            }
                        }
                    }
                }
            }
        }
        lastNodes = numNodes;
        lastEmptyChunk = numEmptyChunk;
        if (cancelRequested) {
            stop = Stop.CANCELLED;
            return Optional.empty();
        }
        if (numEmptyChunk > 0) {
            stop = Stop.CHUNKS;
        }
        return bestSoFar(numNodes);
    }
}
