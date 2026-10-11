package io.github.mojolowjo.entropybot.motor;

/**
 * 0.27.0 motor: the key vector for a world direction. Vanilla {@code Entity.getInputVector} turns (x = leftImpulse,
 * z = forwardImpulse) at yaw r into world (x cos r - z sin r, z cos r + x sin r): forward = (-sin r, cos r), left =
 * (cos r, sin r). Pure (JUnit); loader notes: none.
 */
public final class MotorSteer {
    private MotorSteer() {}

    /** {forward, left} in -1..1 that walk the world direction (dx, dz) at this yaw (degrees). Zero vector: {0, 0}. */
    public static float[] impulses(float yawDeg, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return new float[]{0f, 0f};
        dx /= len;
        dz /= len;
        double r = Math.toRadians(yawDeg);
        double fwd = dx * -Math.sin(r) + dz * Math.cos(r);
        double left = dx * Math.cos(r) + dz * Math.sin(r);
        double m = Math.max(Math.abs(fwd), Math.abs(left));      // scale so the larger key is a full press
        return new float[]{(float) (fwd / m), (float) (left / m)};
    }

    /** The yaw (degrees) that faces from (fx, fz) toward (tx, tz). */
    public static float yawTo(double fx, double fz, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
    }
}
