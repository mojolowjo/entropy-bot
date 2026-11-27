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
 * Modified for Entropy Bot 2026-11-27: no settings (heuristic constant, positions printed as they are), no render interface
 */

package io.github.mojolowjo.entropybot.nav.search.goals;

import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Useful for long-range goals that don't have a specific Y level.
 *
 * @author leijurv
 */
public class GoalXZ implements Goal {

    /**
     * Baritone's {@code costHeuristic} default (fork jar 1.11.3-entropy: 3.563), the ticks per block the heuristic
     * assumes. Baritone reads its global setting; our goals use this fixed value, and the search refuses a config whose
     * costHeuristic differs (a per-search heuristic would need every goal to carry it: not needed yet).
     */
    public static final double COST_HEURISTIC = 3.563; // structural: Baritone's default, checked by NavConfigTest


    private static final double SQRT_2 = Math.sqrt(2); // structural: geometry

    /**
     * The X block position of this goal
     */
    private final int x;

    /**
     * The Z block position of this goal
     */
    private final int z;

    public GoalXZ(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public GoalXZ(BetterBlockPos pos) {
        this.x = pos.x;
        this.z = pos.z;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {//mostly copied from GoalBlock
        int xDiff = x - this.x;
        int zDiff = z - this.z;
        return calculate(xDiff, zDiff);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        GoalXZ goal = (GoalXZ) o;
        return x == goal.x && z == goal.z;
    }

    @Override
    public int hashCode() {
        int hash = 1791873246;
        hash = hash * 222601791 + x;
        hash = hash * -1331679453 + z;
        return hash;
    }

    @Override
    public String toString() {
        return String.format(
                "GoalXZ{x=%s,z=%s}",
                x,
                z
        );
    }

    public static double calculate(double xDiff, double zDiff) {
        //This is a combination of pythagorean and manhattan distance
        //It takes into account the fact that pathing can either walk diagonally or forwards

        //It's not possible to walk forward 1 and right 2 in sqrt(5) time
        //It's really 1+sqrt(2) because it'll walk forward 1 then diagonally 1
        double x = Math.abs(xDiff);
        double z = Math.abs(zDiff);
        double straight;
        double diagonal;
        if (x < z) {
            straight = z - x;
            diagonal = x;
        } else {
            straight = x - z;
            diagonal = z;
        }
        diagonal *= SQRT_2;
        return (diagonal + straight) * COST_HEURISTIC; // big TODO tune
    }

    public static GoalXZ fromDirection(Vec3 origin, float yaw, double distance) {
        float theta = (float) Math.toRadians(yaw);
        double x = origin.x - Mth.sin(theta) * distance;
        double z = origin.z + Mth.cos(theta) * distance;
        return new GoalXZ(Mth.floor(x), Mth.floor(z));
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }
}
