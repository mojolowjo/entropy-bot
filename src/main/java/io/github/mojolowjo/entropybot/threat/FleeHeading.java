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
        if (mobs == null || mobs.isEmpty()) return null;
        double best = Double.MAX_VALUE, bx = 0, bz = 0;
        boolean found = false;
        for (int h = 0; h < HEADINGS; h++) {
            double a = 2 * Math.PI * h / HEADINGS, dx = Math.cos(a), dz = Math.sin(a);
            double ex = px + dx * dist, ez = pz + dz * dist;
            boolean blocked = false;
            double threat = 0;
            for (Mob m : mobs) {
                double mx = m.x() - px, mz = m.z() - pz, md = Math.sqrt(mx * mx + mz * mz);
                double along = mx * dx + mz * dz;              // the mob's position along this heading
                if (along > 0) {
                    // closest approach of the straight run to this mob (a mob already adjacent and ahead blocks it too)
                    double t = Math.min(along, dist);
                    double cx = mx - dx * t, cz = mz - dz * t;
                    if (Math.sqrt(cx * cx + cz * cz) < CLEAR) { blocked = true; break; }
                }
                double ed = Math.hypot(m.x() - ex, m.z() - ez);
                threat += m.weight() / Math.max(1, ed) + m.weight() / Math.max(1, md) * Math.max(0, along / Math.max(md, 0.1));
            }
            if (blocked) continue;
            if (threat < best) { best = threat; bx = ex; bz = ez; found = true; }
        }
        return found ? new int[]{(int) Math.floor(bx), (int) Math.floor(bz)} : null;
    }
}
