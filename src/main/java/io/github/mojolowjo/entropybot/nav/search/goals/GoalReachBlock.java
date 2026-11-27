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
 * Entropy Bot 2026-11-27: the fork's GoalReachBlock (fork-only class, written from the 1.11.3-entropy jar)
 */

package io.github.mojolowjo.entropybot.nav.search.goals;

import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * The fork's reach goal: stand in any cell whose eyes are within {@code reach} of the block's centre, where the
 * caller's tests say the body fits ({@code stand}) and a face of the block is in sight ({@code seesFace}); the cells
 * are worked out once. The heuristic is GoalBlock's to the block.
 *
 * <p>Pure (JUnit: GoalsTest). Loader notes: BlockPos (common side, Mojang names).
 */
public class GoalReachBlock implements Goal, HasGoalPos {
    public final int x;
    public final int y;
    public final int z;
    public final double reach;
    private final Set<Long> cells = new HashSet<>();

    public GoalReachBlock(BlockPos block, double reach, double eyeHeight, CellTest stand, CellTest seesFace) {
        this.x = block.getX();
        this.y = block.getY();
        this.z = block.getZ();
        this.reach = reach;
        int r = (int) Math.ceil(reach) + 1;
        int up = (int) Math.ceil(eyeHeight) + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r - up; dy <= r; dy++) {
                    int cx = x + dx;
                    int cy = y + dy;
                    int cz = z + dz;
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) {
                        continue; // inside the block, or the head in it
                    }
                    double ex = cx + 0.5 - (x + 0.5);
                    double ey = cy + eyeHeight - (y + 0.5);
                    double ez = cz + 0.5 - (z + 0.5);
                    if (!(ex * ex + ey * ey + ez * ez > reach * reach) && stand.test(cx, cy, cz) && seesFace.test(cx, cy, cz)) {
                        cells.add(BetterBlockPos.longHash(cx, cy, cz));
                    }
                }
            }
        }
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    public int size() {
        return cells.size();
    }

    @Override
    public BlockPos getGoalPos() {
        return new BlockPos(x, y, z);
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return cells.contains(BetterBlockPos.longHash(x, y, z));
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return GoalBlock.calculate(x - this.x, y - this.y, z - this.z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GoalReachBlock g = (GoalReachBlock) o;
        return x == g.x && y == g.y && z == g.z && Double.compare(reach, g.reach) == 0 && cells.equals(g.cells);
    }

    @Override
    public int hashCode() {
        return (int) BetterBlockPos.longHash(x, y, z) * 1374271 + cells.size();
    }

    @Override
    public String toString() {
        return String.format("GoalReachBlock{x=%s,y=%s,z=%s,reach=%s,cells=%s}", x, y, z, reach, cells.size());
    }

    @FunctionalInterface
    public interface CellTest {
        boolean test(int x, int y, int z);
    }
}
