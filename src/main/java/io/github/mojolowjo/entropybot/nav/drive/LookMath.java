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
 * Entropy Bot 2026-11-27 (nav step 8): LookBehavior's AimProcessor mouse-step maths (calculateMouseMove, angleToMouse,
 * mouseToAngle) and RotationUtils.calcRotationFromVec3d, with a turn-rate cap of ours.
 */

package io.github.mojolowjo.entropybot.nav.drive;

/**
 * The look's maths: the yaw and pitch from the eyes to a point, the shortest turn, one tick's turn (capped, then rounded
 * to whole mouse steps as the fork's AimProcessor does, so every turn is one a mouse could make), and whether a look has
 * arrived. The fork turned to the target in one tick; ours turns at most {@code maxTurn} degrees a tick (a player's head
 * does not snap), the cap from {@link DriveConfig}. No random offsets (the fork's randomLooking: a later choice). Pure
 * (JUnit: LookMathTest). Loader notes: none (the game's sensitivity is passed in).
 */
public final class LookMath {
    private LookMath() {}

    /** A yaw and pitch in degrees. */
    public record Turn(float yaw, float pitch) {}

    /** Mth.wrapDegrees: into [-180, 180). */
    public static float wrap(float deg) {
        float d = deg % 360f;                                  // structural: a full turn
        if (d >= 180f) d -= 360f;                              // structural: half a turn
        if (d < -180f) d += 360f;                              // structural: half a turn
        return d;
    }

    /** The fork's mouseToAngle: the degrees one mouse count turns at this sensitivity (vanilla's turn formula). */
    public static float mouseToAngle(double mouseDelta, double sensitivity) {
        final double f = sensitivity * (double) 0.6f + (double) 0.2f;     // structural: vanilla's sensitivity curve
        return (float) (mouseDelta * f * f * f * 8.0d) * 0.15f;            // structural: vanilla's turn scale
    }

    /** The fork's calculateMouseMove: the angle reached after the whole mouse counts nearest to {@code target - current}. */
    public static float mouseMove(float current, float target, double sensitivity) {
        float step = mouseToAngle(1, sensitivity);
        double counts = Math.round((target - current) / step);
        return current + mouseToAngle(counts, sensitivity);
    }

    /** RotationUtils.calcRotationFromVec3d: yaw and pitch from the eyes to a point (vanilla's angle convention). */
    public static Turn toward(double eyeX, double eyeY, double eyeZ, double x, double y, double z) {
        double dx = x - eyeX, dy = y - eyeY, dz = z - eyeZ;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);                // the fork's atan2(orig - dest) form
        float pitch = (float) -(Math.atan2(dy, flat) * 180.0 / Math.PI);
        return new Turn(wrap(yaw), pitch);
    }

    /**
     * One tick's turn from (yaw, pitch) toward (tYaw, tPitch): the shortest way round, at most {@code maxTurn} degrees on
     * each axis, rounded to whole mouse steps, the pitch kept in [-90, 90]. The yaw stays continuous (no wrap of the
     * result), as the player's own yaw does.
     */
    public static Turn step(float yaw, float pitch, float tYaw, float tPitch, float maxTurn, double sensitivity) {
        float dy = wrap(tYaw - yaw);
        float dp = Math.max(-90f, Math.min(90f, tPitch)) - pitch;               // structural: the pitch range
        dy = Math.max(-maxTurn, Math.min(maxTurn, dy));
        dp = Math.max(-maxTurn, Math.min(maxTurn, dp));
        float ny = mouseMove(yaw, yaw + dy, sensitivity);
        float np = mouseMove(pitch, pitch + dp, sensitivity);
        return new Turn(ny, Math.max(-90f, Math.min(90f, np)));                // structural: the pitch range
    }

    /** True when (yaw, pitch) is within {@code within} degrees of the target on both axes (the shortest way round). */
    public static boolean reached(float yaw, float pitch, float tYaw, float tPitch, float within) {
        return Math.abs(wrap(tYaw - yaw)) <= within && Math.abs(Math.max(-90f, Math.min(90f, tPitch)) - pitch) <= within;
    }
}
