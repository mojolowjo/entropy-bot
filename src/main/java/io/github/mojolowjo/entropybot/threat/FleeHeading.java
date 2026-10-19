package io.github.mojolowjo.entropybot.threat;

import java.util.List;

/**
 * Live aa376 (Easy, mobs): the flee ran straight away from the nearest mob and into the others. This weighs every
 * nearby hostile (closer = heavier) over {@link #HEADINGS} headings and picks the one with the least threat at the
 * run's end, never one whose straight run passes within {@link #CLEAR} blocks of a mob. Null = cornered (no safe
 * heading): the caller fights the closest instead. The run itself stays Baritone's (drops, lava, water: the run guard).
 * Pure; loader notes: none.
 */
public final class FleeHeading {
    private FleeHeading() {}

    public static final int HEADINGS = 16;
    public static final double CLEAR = 2.5;

    /** A hostile at (x, z) with a weight (1 = an ordinary mob; a creeper or a strong one more). */
    public record Mob(double x, double z, double weight) {}

    /** {x, z} of the run's end (floored), or null when every heading runs through or next to a mob. */
    public static int[] choose(double px, double pz, List<Mob> mobs, double dist) {
        double[] d = direction(px, pz, mobs, dist);
        return d == null ? null : new int[]{(int) Math.floor(px + d[0] * dist), (int) Math.floor(pz + d[1] * dist)};
    }

    /** The unit {dx, dz} of the heading with the least threat, or null when cornered (the creeper sprint's "away"). */
    public static double[] direction(double px, double pz, List<Mob> mobs, double dist) {
        if (mobs == null || mobs.isEmpty()) return null;
        double best = Double.MAX_VALUE;
        double[] out = null;
        for (int h = 0; h < HEADINGS; h++) {
            double a = 2 * Math.PI * h / HEADINGS, dx = Math.cos(a), dz = Math.sin(a);
            double threat = threat(px, pz, mobs, dx, dz, dist);
            if (threat < best) { best = threat; out = new double[]{dx, dz}; }
        }
        return out;
    }

    /** Whether a straight run of {@code dist} along unit (dx, dz) passes within {@link #CLEAR} of a mob ahead. */
    public static boolean blocked(double px, double pz, List<Mob> mobs, double dx, double dz, double dist) {
        return mobs != null && threat(px, pz, mobs, dx, dz, dist) == Double.MAX_VALUE;
    }

    /** The heading's threat at the run's end, MAX_VALUE = blocked. */
    private static double threat(double px, double pz, List<Mob> mobs, double dx, double dz, double dist) {
        double ex = px + dx * dist, ez = pz + dz * dist, threat = 0;
        for (Mob m : mobs) {
            double mx = m.x() - px, mz = m.z() - pz, md = Math.sqrt(mx * mx + mz * mz);
            double along = mx * dx + mz * dz;              // the mob's position along this heading
            if (along > 0) {
                // closest approach of the straight run to this mob (a mob already adjacent and ahead blocks it too)
                double t = Math.min(along, dist);
                double cx = mx - dx * t, cz = mz - dz * t;
                if (Math.sqrt(cx * cx + cz * cz) < CLEAR) return Double.MAX_VALUE;
            }
            double ed = Math.hypot(m.x() - ex, m.z() - ez);
            threat += m.weight() / Math.max(1, ed) + m.weight() / Math.max(1, md) * Math.max(0, along / Math.max(md, 0.1));
        }
        return threat;
    }
}
