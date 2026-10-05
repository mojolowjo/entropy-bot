package io.github.mojolowjo.entropybot.watchview;

/**
 * Camera v2: where the tunnel view's camera sits, and where it looks. Since 0.15.4 a <b>noclip</b> camera (the owner's
 * choice, 2026-10-04): {@code height} above and {@code back} behind the bot along the view's yaw, passing through rock,
 * trees and ground like the spectator camera, with no clipping rule at all. That is safe only because the real world is
 * not drawn while the tunnel view is on ({@link WorldVeil}, {@link TunnelView}: the frame is cleared after the level
 * render and only the shell of the bot's known air, the bot and mobs are drawn), so a camera inside rock reveals
 * nothing. Until 0.15.3 the camera had to sit in open sky-lit or known-air cells (the "no x-ray" guard); that guard
 * made it stick on the surface and in tree tops, and is gone with the world itself. Pure maths (JUnit: TunnelPoseTest).
 */
public final class TunnelPose {
    /** The camera point, the angles (Minecraft degrees: yaw 0 = south, pitch positive = down) and how it got there. */
    public record Pose(double x, double y, double z, float yaw, float pitch, String how) {}

    /** A jump larger than this (blocks) is a teleport: the camera follows at once, no smoothing. */
    public static final double JUMP = 16;

    private TunnelPose() {}

    /** The yaw and pitch that look from the camera at the target. */
    public static float[] lookAt(double cx, double cy, double cz, double tx, double ty, double tz) {
        double dx = tx - cx, dy = ty - cy, dz = tz - cz;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = h < 1e-6 ? 0f : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.max(h, 1e-6)));
        return new float[]{yaw, pitch};
    }

    /**
     * The pose for a target (the bot's eye) at tx ty tz: {@code height} above and {@code back} behind along {@code yaw},
     * looking at the target. No block checks: the camera passes through everything.
     */
    public static Pose place(double tx, double ty, double tz, float yaw, double height, double back) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);           // Minecraft's forward for that yaw
        return pose(tx - fx * back, ty + height, tz - fz * back, tx, ty, tz, "free");
    }

    private static Pose pose(double x, double y, double z, double tx, double ty, double tz, String how) {
        float[] a = lookAt(x, y, z, tx, ty, tz);
        return new Pose(x, y, z, a[0], a[1], how);
    }

    /**
     * Smoothing between frames: a step of {@code alpha} from prev towards the new pose (a teleport of more than
     * {@link #JUMP} blocks jumps), the angles re-aimed at the target so the bot stays in the middle of the picture.
     */
    public static Pose follow(Pose prev, Pose next, double alpha, double tx, double ty, double tz) {
        if (prev == null) return next;
        double dx = next.x() - prev.x(), dy = next.y() - prev.y(), dz = next.z() - prev.z();
        if (dx * dx + dy * dy + dz * dz > JUMP * JUMP) return next;
        return pose(prev.x() + dx * alpha, prev.y() + dy * alpha, prev.z() + dz * alpha, tx, ty, tz, next.how());
    }

    /** The yaw turned by a quarter: Minecraft's yaw grows turning right (south 0, west 90), so left is -90. */
    public static float quarter(float yaw, boolean left) {
        float y = yaw + (left ? -90f : 90f);
        y %= 360f;
        if (y > 180f) y -= 360f;
        if (y <= -180f) y += 360f;
        return y;
    }

    /** A yaw snapped to the nearest 45 degrees, for a steady starting view. */
    public static float snap45(float yaw) {
        float y = Math.round(yaw / 45f) * 45f;
        y %= 360f;
        if (y > 180f) y -= 360f;
        if (y <= -180f) y += 360f;
        return y;
    }

    /** Share of the way to the walking direction covered per client tick: slower than v1's 0.12 (about 2 s to settle). */
    public static final float TURN_EASE = 0.04f;
    /** Horizontal speed (blocks per tick) below which the bot counts as standing: the yaw stays. */
    public static final double MOVING = 0.03;

    /** Pure: the next tunnel-view yaw, easing slowly towards the walking direction while the bot moves, staying while it stands. */
    public static float followWalk(float yaw, double dx, double dz) {
        if (Math.hypot(dx, dz) < MOVING) return yaw;
        return yaw + TURN_EASE * io.github.mojolowjo.entropybot.engine.WatchCamera.turn(yaw, io.github.mojolowjo.entropybot.engine.WatchCamera.walkYaw(dx, dz));
    }
}
