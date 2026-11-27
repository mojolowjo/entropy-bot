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
 * Modified for Entropy Bot 2026-11-27: Baritone's Path, CutoffPath, SplicedPath and PathBase in one class over Steps
 * (no Movement objects); the cutoff settings passed in; the fork's fromCells
 */

package io.github.mojolowjo.entropybot.nav.search;

import io.github.mojolowjo.entropybot.nav.cost.MutableMoveResult;
import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.world.BlockView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * A found route: the cells from the start to the end and the {@link Step}s between them.
 *
 * <p>Entropy Bot (nav step 6): Baritone's {@code Path} (built from the search's nodes, then each step found again
 * among the catalog's moves: "postProcess"), with {@code CutoffPath} ({@link #cutoff}), {@code SplicedPath}
 * ({@link #trySplice}) and {@code PathBase}'s two cutoffs ({@link #cutoffAtLoadedChunks}, {@link #staticCutoff}).
 * Immutable once built. {@code SearchMatchForkTest} checks the cells and the step prices against the fork's paths.
 *
 * <p>Pure (JUnit: SearchMatchForkTest, PathTest). Loader notes: none.
 */
public final class Path {

    private final List<BetterBlockPos> path;
    private final List<Step> steps;
    private final Goal goal;
    private final int numNodes;

    private Path(List<BetterBlockPos> path, List<Step> steps, Goal goal, int numNodes) {
        this.path = List.copyOf(path);
        this.steps = List.copyOf(steps);
        this.goal = goal;
        this.numNodes = numNodes;
        sanityCheck();
    }

    /**
     * Baritone's {@code new Path(realStart, start, end, numNodes, goal, context).postProcess()}: walk the node chain
     * back from {@code end}; if the search never left the start node but the real start differs, the path is the real
     * start to the start node; then find each step's move again. A step no move explains any more ends the path there.
     */
    static Path fromNodes(BetterBlockPos realStart, PathNode start, PathNode end, int numNodes, Goal goal, SearchContext context) {
        List<BetterBlockPos> cells = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        for (PathNode current = end; current != null; current = current.previous) {
            cells.add(new BetterBlockPos(current.x, current.y, current.z));
            costs.add(current.cost);
        }
        BetterBlockPos startNodePos = new BetterBlockPos(start.x, start.y, start.z);
        if (!realStart.equals(startNodePos) && start.equals(end)) {
            cells.add(realStart);
            costs.add(0.0);
        }
        Collections.reverse(cells);
        Collections.reverse(costs);
        return assemble(cells, costs, goal, numNodes, context);
    }

    /** A path from given cells and steps (sanity checked as any path): the pricing's tests and checks build their own. */
    public static Path of(List<BetterBlockPos> cells, List<Step> steps, Goal goal) {
        return new Path(cells, steps, goal, 0);
    }

    /** The fork's {@code Path.fromCells}: rebuild a path from kept cells and their cumulative costs. */
    public static Path fromCells(List<BetterBlockPos> cells, double[] cum, Goal goal, SearchContext context) {
        List<Double> costs = new ArrayList<>(cum.length);
        for (double c : cum) costs.add(c);
        return assemble(cells, costs, goal, 0, context);
    }

    private static Path assemble(List<BetterBlockPos> cells, List<Double> costs, Goal goal, int numNodes, SearchContext context) {
        if (cells.isEmpty()) {
            throw new IllegalStateException("Path must not be empty");
        }
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < cells.size() - 1; i++) {
            Step s = runBackwards(cells.get(i), cells.get(i + 1), costs.get(i + 1) - costs.get(i), context);
            if (s == null) {
                // Baritone: "Movement became impossible during calculation", the path is cut before this step
                return new Path(cells.subList(0, i + 1), steps, goal, numNodes);
            }
            steps.add(s);
        }
        return new Path(cells, steps, goal, numNodes);
    }

    /** The first catalog move whose destination from src is dest (Baritone's apply0 destinations), priced as Baritone. */
    private static Step runBackwards(BetterBlockPos src, BetterBlockPos dest, double cost, SearchContext context) {
        MutableMoveResult res = new MutableMoveResult();
        for (MoveCatalog m : MoveCatalog.values()) {
            res.reset();
            m.apply(context, src.x, src.y, src.z, res);
            if (destOf(m, src, res).equals(dest)) {
                return new Step(src, dest, m, Math.min(res.cost, cost));
            }
        }
        return null;
    }

    /** Where Baritone's {@code Moves.apply0} puts the move's end, given the move's result from src. */
    static BetterBlockPos destOf(MoveCatalog m, BetterBlockPos src, MutableMoveResult res) {
        if (!m.dynamicXZ && !m.dynamicY) {
            return new BetterBlockPos(src.x + m.xOffset, src.y + m.yOffset, src.z + m.zOffset);
        }
        if (m.dynamicXZ) { // parkour: MovementParkour(src, dist, dir, ascend)
            int dx = Integer.signum(m.xOffset);
            int dz = Integer.signum(m.zOffset);
            int dist = Math.abs(res.x - src.x) + Math.abs(res.z - src.z);
            return new BetterBlockPos(src.x + dx * dist, src.y + (res.y > src.y ? 1 : 0), src.z + dz * dist);
        }
        if (m.yOffset == -1) { // descend or fall: the result cell
            return new BetterBlockPos(res.x, res.y, res.z);
        }
        // diagonal: the offset in x and z, the result's height
        return new BetterBlockPos(src.x + m.xOffset, src.y + (res.y - src.y), src.z + m.zOffset);
    }

    /** Baritone's CutoffPath: cells first..last (inclusive) and the steps between. */
    public Path cutoff(int firstPositionToInclude, int lastPositionToInclude) {
        return new Path(path.subList(firstPositionToInclude, lastPositionToInclude + 1), steps.subList(firstPositionToInclude, lastPositionToInclude),
                goal, numNodes);
    }

    public Path cutoff(int lastPositionToInclude) {
        return cutoff(0, lastPositionToInclude);
    }

    /** PathBase: with {@code cutoffAtLoadBoundary}, end the path before the first cell whose chunk is not loaded. */
    public Path cutoffAtLoadedChunks(BlockView bsi, boolean cutoffAtLoadBoundary) {
        if (!cutoffAtLoadBoundary) {
            return this;
        }
        for (int i = 0; i < path.size(); i++) {
            BetterBlockPos pos = path.get(i);
            if (!bsi.worldContainsLoadedChunk(pos.x, pos.z)) {
                return cutoff(i);
            }
        }
        return this;
    }

    /** PathBase: a path that does not reach the goal is cut to {@code pathCutoffFactor} of its length past the minimum. */
    public Path staticCutoff(Goal destination, int pathCutoffMinimumLength, double pathCutoffFactor) {
        int min = pathCutoffMinimumLength;
        if (length() < min) {
            return this;
        }
        if (destination == null || destination.isInGoal(getDest())) {
            return this;
        }
        int newLength = (int) ((length() - min) * pathCutoffFactor) + min - 1;
        return cutoff(newLength);
    }

    /** Baritone's SplicedPath.trySplice: the first path, then the second from where it joins (empty if they do not). */
    public static Optional<Path> trySplice(Path first, Path second, boolean allowOverlapCutoff) {
        if (second == null || first == null) {
            return Optional.empty();
        }
        if (!first.getDest().equals(second.getSrc())) {
            return Optional.empty();
        }
        HashSet<BetterBlockPos> secondPos = new HashSet<>(second.positions());
        int firstPositionInSecond = -1;
        for (int i = 0; i < first.length() - 1; i++) { // overlap in the very last element is fine (and required) so only go up to first.length() - 1
            if (secondPos.contains(first.positions().get(i))) {
                firstPositionInSecond = i;
                break;
            }
        }
        if (firstPositionInSecond != -1) {
            if (!allowOverlapCutoff) {
                return Optional.empty();
            }
        } else {
            firstPositionInSecond = first.length() - 1;
        }
        int positionInSecond = second.positions().indexOf(first.positions().get(firstPositionInSecond));
        if (!allowOverlapCutoff && positionInSecond != 0) {
            throw new IllegalStateException("Paths to be spliced are overlapping incorrectly");
        }
        List<BetterBlockPos> positions = new ArrayList<>(first.positions().subList(0, firstPositionInSecond + 1));
        List<Step> steps = new ArrayList<>(first.steps().subList(0, firstPositionInSecond));
        positions.addAll(second.positions().subList(positionInSecond + 1, second.length()));
        steps.addAll(second.steps().subList(positionInSecond, second.length() - 1));
        return Optional.of(new Path(positions, steps, first.getGoal(), first.getNumNodesConsidered() + second.getNumNodesConsidered()));
    }

    public List<BetterBlockPos> positions() {
        return path;
    }

    public List<Step> steps() {
        return steps;
    }

    public int length() {
        return path.size();
    }

    public Goal getGoal() {
        return goal;
    }

    public int getNumNodesConsidered() {
        return numNodes;
    }

    public BetterBlockPos getSrc() {
        return path.get(0);
    }

    public BetterBlockPos getDest() {
        return path.get(path.size() - 1);
    }

    /** Ticks from cell {@code pathPosition} to the end: the sum of the remaining steps' prices. */
    public double ticksRemainingFrom(int pathPosition) {
        double sum = 0;
        for (int i = pathPosition; i < steps.size(); i++) {
            sum += steps.get(i).cost();
        }
        return sum;
    }

    /** IPath.sanityCheck: cells and steps line up, and the path never visits a cell twice. */
    private void sanityCheck() {
        if (path.size() != steps.size() + 1) {
            throw new IllegalStateException("Size of path array is unexpected");
        }
        HashSet<BetterBlockPos> seenSoFar = new HashSet<>();
        for (int i = 0; i < path.size() - 1; i++) {
            BetterBlockPos src = path.get(i);
            Step step = steps.get(i);
            if (!src.equals(step.src())) {
                throw new IllegalStateException("Path source is not equal to the movement source");
            }
            if (!path.get(i + 1).equals(step.dest())) {
                throw new IllegalStateException("Path destination is not equal to the movement destination");
            }
            if (!seenSoFar.add(src)) {
                throw new IllegalStateException("Path doubles back on itself, making a loop");
            }
        }
    }
}
