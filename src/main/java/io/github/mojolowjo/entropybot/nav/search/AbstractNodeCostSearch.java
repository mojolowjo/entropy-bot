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
 * Modified for Entropy Bot 2026-11-27: the fork's version (cancel sticks, quiet, partialSoFar, nodeCost, Partial)
 * over SearchContext; map size and the cutoffs from NavConfig; no chat logging; an exception is kept as text
 */

package io.github.mojolowjo.entropybot.nav.search;

import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Any pathfinding algorithm that keeps track of nodes recursively by their cost (e.g. A*, dijkstra)
 *
 * <p>Entropy Bot (nav step 6): the fork's version. {@link #calculate} never throws: a failure inside the search is an
 * EXCEPTION result whose text says what failed. Pure (JUnit: SearchMatchForkTest). Loader notes: none.
 *
 * @author leijurv
 */
public abstract class AbstractNodeCostSearch {

    protected final BetterBlockPos realStart;
    protected final int startX;
    protected final int startY;
    protected final int startZ;

    protected final Goal goal;

    protected final SearchContext context;

    /**
     * @see <a href="https://github.com/cabaletta/baritone/issues/107">Issue #107</a>
     */
    private final Long2ObjectOpenHashMap<PathNode> map;

    protected PathNode startNode;

    protected PathNode mostRecentConsidered;

    protected final PathNode[] bestSoFar = new PathNode[COEFFICIENTS.length];

    private volatile boolean isFinished;

    protected volatile boolean cancelRequested;

    /** Fork: no progress lines (Baritone prints them; ours never prints). */
    protected boolean quiet = true;

    /**
     * This is really complicated and hard to explain. I wrote a comment in the old version of MineBot but it was so
     * long it was easier as a Google Doc (because I could insert charts).
     *
     * @see <a href="https://docs.google.com/document/d/1WVHHXKXFdCR1Oz__KtK8sFqyvSwJN_H4lftkHFgmzlc/edit"></a>
     */
    protected static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};

    /**
     * If a path goes less than 5 blocks and doesn't make it to its goal, it's not worth considering.
     */
    protected static final double MIN_DIST_PATH = 5; // structural: Baritone's search constant, equal to the fork (SearchMatchForkTest)

    /**
     * there are floating point errors caused by random combinations of traverse and diagonal over a flat area
     * that means that sometimes there's a cost improvement of like 10 ^ -16
     * it's not worth the time to update the costs, decrease-key the heap, potentially repropagate, etc
     * <p>
     * who cares about a hundredth of a tick? that's half a millisecond for crying out loud!
     */
    protected static final double MIN_IMPROVEMENT = 0.01; // structural: Baritone's search constant, equal to the fork (SearchMatchForkTest)

    static final int MAX_CHAIN = 1_000_000; // structural: the fork's cap on a partial chain

    AbstractNodeCostSearch(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, SearchContext context) {
        this.realStart = realStart;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.goal = goal;
        this.context = context;
        NavConfig.Search s = context.config.search();
        this.map = new Long2ObjectOpenHashMap<>(s.pathingMapDefaultSize(), s.pathingMapLoadFactor());
    }

    public void cancel() {
        cancelRequested = true;
    }

    public synchronized PathCalculationResult calculate(long primaryTimeout, long failureTimeout) {
        if (isFinished) {
            throw new IllegalStateException("Path finder cannot be reused!");
        }
        try {
            Path path = calculate0(primaryTimeout, failureTimeout).orElse(null);
            if (cancelRequested) {
                return new PathCalculationResult(PathCalculationResult.Type.CANCELLATION, null, stop(), null);
            }
            if (path == null) {
                return new PathCalculationResult(PathCalculationResult.Type.FAILURE, null, stop(), null);
            }
            NavConfig.Search s = context.config.search();
            path = path.cutoffAtLoadedChunks(context.bsi, s.cutoffAtLoadBoundary());
            path = path.staticCutoff(goal, s.pathCutoffMinimumLength(), s.pathCutoffFactor());
            if (goal.isInGoal(path.getDest())) {
                return new PathCalculationResult(PathCalculationResult.Type.SUCCESS_TO_GOAL, path, stop(), null);
            } else {
                return new PathCalculationResult(PathCalculationResult.Type.SUCCESS_SEGMENT, path, stop(), null);
            }
        } catch (RuntimeException e) {
            // Baritone: "Pathing exception" in chat; here the result carries it
            return new PathCalculationResult(PathCalculationResult.Type.EXCEPTION, null, stop(), e.toString());
        } finally {
            // this is run regardless of what exception may or may not be raised by calculate0
            isFinished = true;
        }
    }

    /** Why the search stopped. */
    public abstract Stop stop();

    protected abstract Optional<Path> calculate0(long primaryTimeout, long failureTimeout);

    /**
     * Determines the distance squared from the specified node to the start
     * node. Intended for use in distance comparison, rather than anything that
     * considers the real distance value, hence the "sq".
     *
     * @param n A node
     * @return The distance, squared
     */
    protected double getDistFromStartSq(PathNode n) {
        int xDiff = n.x - startX;
        int yDiff = n.y - startY;
        int zDiff = n.z - startZ;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff;
    }

    /**
     * Attempts to search the block position hashCode long to {@link PathNode} map
     * for the node mapped to the specified pos. If no node is found,
     * a new node is created.
     *
     * @param x        The x position of the node
     * @param y        The y position of the node
     * @param z        The z position of the node
     * @param hashCode The hash code of the node, provided by {@link BetterBlockPos#longHash(int, int, int)}
     * @return The associated node
     * @see <a href="https://github.com/cabaletta/baritone/issues/107">Issue #107</a>
     */
    protected PathNode getNodeAtPosition(int x, int y, int z, long hashCode) {
        PathNode node = map.get(hashCode);
        if (node == null) { // get map entry, create if not present
            node = new PathNode(x, y, z, goal);
            map.put(hashCode, node);
        }
        return node;
    }

    public Optional<Path> pathToMostRecentNodeConsidered() {
        return Optional.ofNullable(mostRecentConsidered).map(node -> Path.fromNodes(realStart, startNode, node, 0, goal, context));
    }

    public Optional<Path> bestPathSoFar() {
        return bestSoFar(0);
    }

    /** Fork: the best-so-far chain as cells and cumulative costs (first-segment walking), or empty under 5 blocks. */
    public Optional<Partial> partialSoFar() {
        if (startNode == null) {
            return Optional.empty();
        }
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            PathNode n = bestSoFar[i];
            if (n != null && getDistFromStartSq(n) > MIN_DIST_PATH * MIN_DIST_PATH) {
                return Optional.ofNullable(partialOf(n, MAX_CHAIN));
            }
        }
        return Optional.empty();
    }

    static Partial partialOf(PathNode end, int maxCells) {
        List<PathNode> chain = new ArrayList<>();
        for (PathNode c = end; c != null; c = c.previous) {
            if (chain.size() >= maxCells) {
                return null;
            }
            chain.add(c);
        }
        if (chain.size() < 2) {
            return null;
        }
        Collections.reverse(chain);
        List<BetterBlockPos> cells = new ArrayList<>(chain.size());
        double[] cum = new double[chain.size()];
        double base = chain.get(0).cost;
        for (int i = 0; i < chain.size(); i++) {
            PathNode c = chain.get(i);
            cells.add(new BetterBlockPos(c.x, c.y, c.z));
            cum[i] = i == 0 ? 0 : Math.max(cum[i - 1], c.cost - base);
        }
        return new Partial(List.copyOf(cells), cum);
    }

    protected Optional<Path> bestSoFar(int numNodes) {
        if (startNode == null) {
            return Optional.empty();
        }
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            if (bestSoFar[i] == null) {
                continue;
            }
            if (getDistFromStartSq(bestSoFar[i]) > MIN_DIST_PATH * MIN_DIST_PATH) { // square the comparison since distFromStartSq is squared
                return Optional.of(Path.fromNodes(realStart, startNode, bestSoFar[i], numNodes, goal, context));
            }
        }
        // instead of returning bestSoFar[0], be less misleading
        // if it actually won't find any path, don't make them think it will by rendering a dark blue that will never actually happen
        return Optional.empty();
    }

    public final boolean isFinished() {
        return isFinished;
    }

    public final Goal getGoal() {
        return goal;
    }

    public BetterBlockPos getStart() {
        return new BetterBlockPos(startX, startY, startZ);
    }

    /** Fork: the cost the search reached a cell for, NaN if it never saw it. */
    public double nodeCost(BetterBlockPos pos) {
        PathNode n = map.get(BetterBlockPos.longHash(pos.x, pos.y, pos.z));
        return n == null ? Double.NaN : n.cost;
    }

    protected int mapSize() {
        return map.size();
    }

    /** Fork: a chain of cells with the cost to reach each from the first. */
    public record Partial(List<BetterBlockPos> cells, double[] cum) {
    }
}
