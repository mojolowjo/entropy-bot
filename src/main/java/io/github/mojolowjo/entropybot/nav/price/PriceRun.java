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
 * Entropy Bot 2026-11-27: the fork's PricedPath.Run (one running price), moved out of PricedPath; over our search.
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.PathBuild.Seen;
import io.github.mojolowjo.entropybot.nav.price.PathBuild.SegInfo;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.search.AStarPathFinder;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.PathCalculationResult;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One running price: its goal, context, segments so far and the segment search in flight; or a JOIN search (D11: one
 * bounded segment from the feet onto the goal's kept path, spliced to the kept suffix when it lands). Game thread but
 * {@link #search}, which runs on the world's executor. Pure data (JUnit: PriceQueueTest). Loader notes: none.
 */
final class PriceRun {
    final Goal goal;
    final SearchContext context;
    final BetterBlockPos start;
    final int maxSegments;
    /** Entropy z57: where, in which world and with what the price was asked (kept with the answer). */
    final PathMemory.Cell startCell;
    final Object world;
    final PathMemory.Stamp stamp;
    /** Entropy z55 review 2 / z63: a segment search of this price used a DigDownGoal or a ClimbGoal. */
    boolean steered;
    /** Entropy z58: started by the queue (no asker waits on it): its landing is counted for the mod's re-price. */
    boolean fromQueue;
    final List<Path> segments = new ArrayList<>();
    /** Entropy z57: per segment, the search's cost from the segment's start to each of its positions (ticks). */
    final List<double[]> along = new ArrayList<>();
    /** Entropy z57 review 4: per segment, its watched cells read when THAT segment landed. */
    final List<Map<PathMemory.Cell, Seen>> snaps = new ArrayList<>();
    final CompletableFuture<IPathCost.Answer> out = new CompletableFuture<>();
    double cost;
    /** Entropy CPU item 2: the hard stop the running segment search was given (the timed-out answer names it). */
    long failureMs;
    AStarPathFinder finder;
    BetterBlockPos segStart, firstStart;
    CompletableFuture<PathCalculationResult> search;
    /** Entropy D11: a JOIN search toward {@code keptPath}'s cells from {@code joinIndex} ({@code joinGoal}). */
    boolean join;
    GoalOnPath joinGoal;
    int joinIndex;
    PathMemory.Path<Seen> keptPath;
    SegInfo keptInfo;

    PriceRun(Goal goal, SearchContext context, BetterBlockPos start, int maxSegments, PathMemory.Cell startCell, Object world,
             PathMemory.Stamp stamp) {
        this.goal = goal;
        this.context = context;
        this.start = start;
        this.maxSegments = maxSegments;
        this.startCell = startCell;
        this.world = world;
        this.stamp = stamp;
    }
}
