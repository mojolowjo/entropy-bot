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
 * Entropy Bot 2026-11-27: the fork's ClimbGoal (fork-only class, written from the 1.11.3-entropy jar), over SearchContext
 */

package io.github.mojolowjo.entropybot.nav.search.goals;

import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import net.minecraft.core.BlockPos;

/**
 * The fork's climb steering: for a goal 8 or more blocks above the start, the heuristic adds the extra ticks each
 * level costs over walking (a pillar's place and jump), so A* does not wander the flat looking for stairs that are
 * not there. Wraps the goal; isInGoal is the inner goal's.
 *
 * <p>Pure (JUnit: GoalsTest). Loader notes: BlockPos (common side, Mojang names).
 */
public final class ClimbGoal implements Goal {
    private final Goal inner;
    private final int goalY;
    private final double extraPerLevel;
    private final boolean pillar;
    static final int MAX_COLUMN = 384; // structural: the overworld's height, the longest column worth reading
    public static final int MIN_CLIMB = 8; // structural: the fork's threshold

    ClimbGoal(Goal inner, int goalY, double extraPerLevel, boolean pillar) {
        this.inner = inner;
        this.goalY = goalY;
        this.extraPerLevel = extraPerLevel;
        this.pillar = pillar;
    }

    public static double perLevel(double placeCost, double jumpPenalty) {
        return placeCost >= 0 && placeCost < ActionCosts.COST_INF
                ? ActionCosts.JUMP_ONE_BLOCK_COST + placeCost + jumpPenalty
                : Math.max(ActionCosts.JUMP_ONE_BLOCK_COST, ActionCosts.WALK_ONE_BLOCK_COST) + jumpPenalty;
    }

    public static double extraPerLevel(double perLevel) {
        return Math.max(0, perLevel - ActionCosts.JUMP_ONE_BLOCK_COST);
    }

    static double placeCost(BlockPos start, int goalY, SearchContext context) {
        if (!context.hasThrowaway) {
            return ActionCosts.COST_INF;
        }
        double best = ActionCosts.COST_INF;
        int top = Math.min(goalY, start.getY() + MAX_COLUMN);
        for (int y = start.getY(); y < top; y++) {
            double c = context.costOfPlacingAt(start.getX(), y, start.getZ(), context.get(start.getX(), y, start.getZ()));
            if (c >= 0 && c < best) {
                best = c;
            }
        }
        return best;
    }

    public static Goal of(Goal goal, BlockPos start, SearchContext context) {
        if (!(goal instanceof HasGoalPos rp)) {
            return goal;
        }
        BlockPos g = rp.getGoalPos();
        if (g.getY() - start.getY() < MIN_CLIMB || !context.bsi.worldContainsLoadedChunk(start.getX(), start.getZ())) {
            return goal;
        }
        double place = placeCost(start, g.getY(), context);
        double extra = extraPerLevel(perLevel(place, context.jumpPenalty));
        return extra > 0 ? new ClimbGoal(goal, g.getY(), extra, place < ActionCosts.COST_INF) : goal;
    }

    /** The fork's PathingBehavior steering: dig-down first, else climb, else the goal as given. */
    public static Goal steer(Goal goal, BlockPos start, SearchContext context) {
        Goal down = DigDownGoal.of(goal, start, context);
        return down != goal ? down : of(goal, start, context);
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return inner.isInGoal(x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return inner.heuristic(x, y, z) + Math.max(0, goalY - y) * extraPerLevel;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClimbGoal c ? inner.equals(c.inner) : inner.equals(o);
    }

    @Override
    public int hashCode() {
        return inner.hashCode();
    }

    @Override
    public String toString() {
        return inner + " (climb: +" + Math.round(extraPerLevel * 10.0) / 10.0 + " ticks a level below y " + goalY
                + (pillar ? ", pillar" : ", walk up: no block to place") + ")";
    }
}
