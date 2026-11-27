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
 * Entropy Bot 2026-11-27: the fork's DigDownGoal (fork-only class, written from the 1.11.3-entropy jar), over SearchContext and BlockRules
 */

package io.github.mojolowjo.entropybot.nav.search.goals;

import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * The fork's dig-down steering: for a goal below the start, the heuristic adds the extra ticks a block of digging
 * down costs over falling (the median dig time of the column above the goal, or of the ring around it), so A* digs
 * straight down instead of searching for a cave. Wraps the goal; isInGoal is the inner goal's.
 *
 * <p>Pure (JUnit: GoalsTest). Loader notes: BlockPos, BlockState (common side, Mojang names).
 */
public final class DigDownGoal implements Goal {
    private final Goal inner;
    private final int goalY;
    private final double extraPerBlock;
    static final int MAX_COLUMN = 384; // structural: the overworld's height

    DigDownGoal(Goal inner, int goalY, double extraPerBlock) {
        this.inner = inner;
        this.goalY = goalY;
        this.extraPerBlock = extraPerBlock;
    }

    public static double extraPerBlock(double digTicks, double fallOne, double halfFallTwo) {
        return digTicks >= 0 && !(digTicks >= ActionCosts.COST_INF) ? Math.max(0, fallOne + digTicks - halfFallTwo) : 0;
    }

    public static Goal of(Goal goal, BlockPos start, SearchContext context) {
        if (!(goal instanceof HasGoalPos rp)) {
            return goal;
        }
        BlockPos g = rp.getGoalPos();
        if (g.getY() >= start.getY() || !context.bsi.worldContainsLoadedChunk(g.getX(), g.getZ())) {
            return goal;
        }
        int top = Math.min(start.getY() - 1, g.getY() + MAX_COLUMN);
        double dig = typicalDig(column(context, g.getX(), g.getZ(), top, g.getY()));
        if (Double.isNaN(dig)) {
            BlockState state = context.get(g.getX(), g.getY(), g.getZ());
            dig = BlockRules.getMiningDurationTicks(context, g.getX(), g.getY(), g.getZ(), state, false);
            if (!(dig < ActionCosts.COST_INF)) {
                double around = typicalDig(ring(context, g.getX(), g.getZ(), top, g.getY()));
                if (!Double.isNaN(around)) {
                    dig = around;
                }
            }
        }
        double extra = extraPerBlock(dig, ActionCosts.FALL_N_BLOCKS_COST[1], ActionCosts.FALL_N_BLOCKS_COST[2] / 2);
        return extra > 0 ? new DigDownGoal(goal, g.getY(), extra) : goal;
    }

    private static double[] column(SearchContext context, int x, int z, int top, int bottom) {
        double[] digs = new double[Math.max(0, top - bottom + 1)];
        int i = 0;
        for (int y = top; y >= bottom; y--) {
            digs[i++] = BlockRules.getMiningDurationTicks(context, x, y, z, context.get(x, y, z), false);
        }
        return digs;
    }

    private static double[] ring(SearchContext context, int x, int z, int top, int bottom) {
        int levels = Math.max(0, top - bottom + 1);
        double[] digs = new double[8 * levels];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && context.bsi.worldContainsLoadedChunk(x + dx, z + dz)) {
                    double[] c = column(context, x + dx, z + dz, top, bottom);
                    System.arraycopy(c, 0, digs, i, c.length);
                    i += c.length;
                }
            }
        }
        return Arrays.copyOf(digs, i);
    }

    /** The median of the finite, positive dig times; NaN when there are none. */
    public static double typicalDig(double[] digs) {
        double[] ok = new double[digs.length];
        int n = 0;
        for (double d : digs) {
            if (d > 0 && d < ActionCosts.COST_INF) {
                ok[n++] = d;
            }
        }
        if (n == 0) {
            return Double.NaN;
        }
        Arrays.sort(ok, 0, n);
        return n % 2 == 1 ? ok[n / 2] : (ok[n / 2 - 1] + ok[n / 2]) / 2;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return inner.isInGoal(x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return inner.heuristic(x, y, z) + Math.max(0, y - goalY) * extraPerBlock;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DigDownGoal d ? inner.equals(d.inner) : inner.equals(o);
    }

    @Override
    public int hashCode() {
        return inner.hashCode();
    }

    @Override
    public String toString() {
        return inner + " (dig down: +" + Math.round(extraPerBlock * 10.0) / 10.0 + " ticks a block above y " + goalY + ")";
    }
}
