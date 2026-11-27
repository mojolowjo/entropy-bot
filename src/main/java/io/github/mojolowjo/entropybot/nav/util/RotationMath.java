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
 * Modified for Entropy Bot 2026-11-27: only the pure maths kept (reachable* and the player helpers move to the pilot, step 8); renamed RotationMath
 */

package io.github.mojolowjo.entropybot.nav.util;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;


/**
 * @author Brady
 * @since 9/25/2018
 */
public final class RotationMath {

    /**
     * Constant that a degree value is multiplied by to get the equivalent radian value
     */
    public static final double DEG_TO_RAD = Math.PI / 180.0; // structural: unit conversion
    public static final float DEG_TO_RAD_F = (float) DEG_TO_RAD;

    /**
     * Constant that a radian value is multiplied by to get the equivalent degree value
     */
    public static final double RAD_TO_DEG = 180.0 / Math.PI; // structural: unit conversion
    public static final float RAD_TO_DEG_F = (float) RAD_TO_DEG;

    /**
     * Offsets from the root block position to the center of each side.
     */
    public static final Vec3[] BLOCK_SIDE_MULTIPLIERS = new Vec3[]{
            new Vec3(0.5, 0, 0.5), // Down
            new Vec3(0.5, 1, 0.5), // Up
            new Vec3(0.5, 0.5, 0), // North
            new Vec3(0.5, 0.5, 1), // South
            new Vec3(0, 0.5, 0.5), // West
            new Vec3(1, 0.5, 0.5)  // East
    };

    private RotationMath() {}

    /**
     * Calculates the rotation from BlockPos<sub>dest</sub> to BlockPos<sub>orig</sub>
     *
     * @param orig The origin position
     * @param dest The destination position
     * @return The rotation from the origin to the destination
     */
    public static Rotation calcRotationFromCoords(BlockPos orig, BlockPos dest) {
        return calcRotationFromVec3d(new Vec3(orig.getX(), orig.getY(), orig.getZ()), new Vec3(dest.getX(), dest.getY(), dest.getZ()));
    }

    /**
     * Wraps the target angles to a relative value from the current angles. This is done by
     * subtracting the current from the target, normalizing it, and then adding the current
     * angles back to it.
     *
     * @param current The current angles
     * @param target  The target angles
     * @return The wrapped angles
     */
    public static Rotation wrapAnglesToRelative(Rotation current, Rotation target) {
        if (current.yawIsReallyClose(target)) {
            return new Rotation(current.getYaw(), target.getPitch());
        }
        return target.subtract(current).normalize().add(current);
    }

    /**
     * Calculates the rotation from Vec<sub>dest</sub> to Vec<sub>orig</sub> and makes the
     * return value relative to the specified current rotations.
     *
     * @param orig    The origin position
     * @param dest    The destination position
     * @param current The current rotations
     * @return The rotation from the origin to the destination
     * @see #wrapAnglesToRelative(Rotation, Rotation)
     */
    public static Rotation calcRotationFromVec3d(Vec3 orig, Vec3 dest, Rotation current) {
        return wrapAnglesToRelative(current, calcRotationFromVec3d(orig, dest));
    }

    /**
     * Calculates the rotation from Vec<sub>dest</sub> to Vec<sub>orig</sub>
     *
     * @param orig The origin position
     * @param dest The destination position
     * @return The rotation from the origin to the destination
     */
    private static Rotation calcRotationFromVec3d(Vec3 orig, Vec3 dest) {
        double[] delta = {orig.x - dest.x, orig.y - dest.y, orig.z - dest.z};
        double yaw = Mth.atan2(delta[0], -delta[2]);
        double dist = Math.sqrt(delta[0] * delta[0] + delta[2] * delta[2]);
        double pitch = Mth.atan2(delta[1], dist);
        return new Rotation(
                (float) (yaw * RAD_TO_DEG),
                (float) (pitch * RAD_TO_DEG)
        );
    }

    /**
     * Calculates the look vector for the specified yaw/pitch rotations.
     *
     * @param rotation The input rotation
     * @return Look vector for the rotation
     */
    public static Vec3 calcLookDirectionFromRotation(Rotation rotation) {
        float flatZ = Mth.cos((-rotation.getYaw() * DEG_TO_RAD_F) - (float) Math.PI);
        float flatX = Mth.sin((-rotation.getYaw() * DEG_TO_RAD_F) - (float) Math.PI);
        float pitchBase = -Mth.cos(-rotation.getPitch() * DEG_TO_RAD_F);
        float pitchHeight = Mth.sin(-rotation.getPitch() * DEG_TO_RAD_F);
        return new Vec3(flatX * pitchBase, pitchHeight, flatZ * pitchBase);
    }

    @Deprecated
    public static Vec3 calcVec3dFromRotation(Rotation rotation) {
        return calcLookDirectionFromRotation(rotation);
    }
}
