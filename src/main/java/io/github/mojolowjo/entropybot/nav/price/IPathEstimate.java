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
 * Entropy Bot 2026-11-27: the fork's IPathEstimate (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import net.minecraft.core.BlockPos;

/**
 * Entropy J3-fork (z76 part 2; rule 18 "rank rough, walk exact"): Baritone's own goal heuristic from a start cell to a goal -
 * the A* search's estimate of the remaining cost in ticks ({@link Goal#heuristic(int, int, int)}), with NO search: no world
 * read, no cost of blocks, breaks or places, so a wall or a cliff is not in it. For ranking ways before they are priced;
 * never a price (a walk's price is {@link IPricedPath#price}'s, rule 15). Pure, any thread.
 */
public interface IPathEstimate {

    /** The goal's heuristic at {@code start} (ticks, Baritone's units); NaN for a null goal or start. */
    double estimate(Goal goal, BlockPos start);

    /** The estimate: {@code goal.heuristic(start)} as the A* search reads it at its start node. */
    IPathEstimate HEURISTIC = (goal, start) -> goal == null || start == null ? Double.NaN
            : goal.heuristic(start.getX(), start.getY(), start.getZ());
}
