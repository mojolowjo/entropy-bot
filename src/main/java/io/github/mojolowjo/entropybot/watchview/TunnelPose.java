package io.github.mojolowjo.entropybot.watchview;

/**
 * Camera v2: where the tunnel view's camera sits, and where it looks. Fixed above and behind the bot (a fixed yaw,
 * no orbit), following it. The camera must never sit inside rock: a camera inside a solid block sees through every
 * face around it (the classic clip x-ray). So the camera's cell, and every cell its near plane can touch (the point
 * plus or minus {@link #NEAR} on each axis), must be "ok": open and either sky-lit or known air (a camera in a cave
 * the bot never opened would show that cave). Order: the wanted spot; else straight up from it, a block at a time,
 * up to maxClimb (usually out of the ground into the open sky); else pulled in along the line to the bot, like vanilla
 * third person, stopping before the first cell that is not ok. Pure maths (JUnit: TunnelPoseTest).
 */
public final class TunnelPose {
    /** Half the size of the box around the camera point that must be clear (covers the near plane, 0.05 ahead). */
    public static final double NEAR = 0.3;

    public interface CellOk {
        boolean ok(int x, int y, int z);
    }

    /** The camera point, the angles (Minecraft degrees: yaw 0 = south, pitch positive = down) and how it got there. */
    public record Pose(double x, double y, double z, float yaw, float pitch, String how) {}

    private TunnelPose() {}

    /** True when every cell the camera box at (x,y,z) touches is ok. */
    public static boolean boxOk(double x, double y, double z, CellOk ok) {
        int x0 = Sight.floor(x - NEAR), x1 = Sight.floor(x + NEAR);
        int y0 = Sight.floor(y - NEAR), y1 = Sight.floor(y + NEAR);
        int z0 = Sight.floor(z - NEAR), z1 = Sight.floor(z + NEAR);
        for (int a = x0; a <= x1; a++)
            for (int b = y0; b <= y1; b++)
                for (int c = z0; c <= z1; c++)
                    if (!ok.ok(a, b, c)) return false;
        return true;
    }

    /** The yaw and pitch that look from the camera at the target. */
    public static float[] lookAt(double cx, double cy, double cz, double tx, double ty, double tz) {
        double dx = tx - cx, dy = ty - cy, dz = tz - cz;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = h < 1e-6 ? 0f : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.max(h, 1e-6)));
        return new float[]{yaw, pitch};
    }

    /**
     * The pose for a target (the bot's eye) at tx ty tz: wanted spot {@code height} above and {@code back} behind
     * along {@code yaw}.
     */
    public static Pose place(double tx, double ty, double tz, float yaw, double height, double back, int maxClimb, CellOk ok) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);           // Minecraft's forward for that yaw
        double wx = tx - fx * back, wy = ty + height, wz = tz - fz * back;
        if (boxOk(wx, wy, wz, ok)) return pose(wx, wy, wz, tx, ty, tz, "above");
        for (int k = 1; k <= maxClimb; k++)
            if (boxOk(wx, wy + k, wz, ok)) return pose(wx, wy + k, wz, tx, ty, tz, "raised " + k);
        return pulledIn(tx, ty, tz, wx, wy, wz, ok);
    }

    /** Along the line from the target out to the wanted spot, the furthest point reached without a bad cell. */
    static Pose pulledIn(double tx, double ty, double tz, double wx, double wy, double wz, CellOk ok) {
        double dx = wx - tx, dy = wy - ty, dz = wz - tz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / 0.1));
        double bx = tx, by = ty, bz = tz;
        boolean any = false;
        for (int i = 0; i <= steps; i++) {
            double f = (double) i / steps;
            double px = tx + dx * f, py = ty + dy * f, pz = tz + dz * f;
            if (!boxOk(px, py, pz, ok)) break;
            bx = px;
            by = py;
            bz = pz;
            any = true;
        }
        Pose p = pose(bx, by, bz, tx, ty, tz, any ? "pulled in" : "at the bot");
        if (!any) {
            // nothing around the eye is ok (shouldn't happen: the bot stands in air): keep the angles, the eye
            return new Pose(tx, ty, tz, p.yaw(), p.pitch(), "at the bot");
        }
        return p;
    }

    private static Pose pose(double x, double y, double z, double tx, double ty, double tz, String how) {
        float[] a = lookAt(x, y, z, tx, ty, tz);
        return new Pose(x, y, z, a[0], a[1], how);
    }

    /**
     * Smoothing between frames: a step of {@code alpha} from prev towards the new pose; the step is used only when its
     * box is ok (else the new pose itself), so smoothing never puts the camera inside rock. Angles re-aimed at the target.
     */
    public static Pose follow(Pose prev, Pose next, double alpha, double tx, double ty, double tz, CellOk ok) {
        if (prev == null) return next;
        double dx = next.x() - prev.x(), dy = next.y() - prev.y(), dz = next.z() - prev.z();
        if (dx * dx + dy * dy + dz * dz > 16 * 16) return next;      // a teleport: jump
        double x = prev.x() + dx * alpha, y = prev.y() + dy * alpha, z = prev.z() + dz * alpha;
        if (!boxOk(x, y, z, ok)) return next;
        return pose(x, y, z, tx, ty, tz, next.how());
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
}
