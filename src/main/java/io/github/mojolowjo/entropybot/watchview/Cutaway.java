package io.github.mojolowjo.entropybot.watchview;

/**
 * The tunnel view's cutaway ({@code watch tunnel cut}, 0.16.1; the owner, 2026-10-04: "the only things I don't want
 * drawn are things in between the camera and the character"): a cylinder of radius r along the line from the camera
 * (A) to the middle of the bot's box (B). A face fragment whose projection on that line falls between A and B
 * (0 <= t <= 1) and lies closer than r to the line is not drawn. Nothing beyond the bot (t > 1) is cut, so the floor
 * under it and the walls behind it stay. The fragment shader {@code watch_cut_*.fsh} does exactly this per pixel; this
 * class is the same maths in Java, for JUnit and the radius words. Pure.
 *
 * <p>Why the bot is never hidden: every line of sight from the camera to a point of the bot's box stays within the
 * box's half-diagonal (about 0.95 blocks for a player) of the axis, less near the camera, so with r >= {@link #MIN_RADIUS}
 * every face fragment in front of the bot is cut (JUnit: {@code CutawayTest}).
 */
public final class Cutaway {
    public static final double DEFAULT_RADIUS = 1.5, MIN_RADIUS = 1.0, MAX_RADIUS = 6.0;

    private Cutaway() {}

    /** Where point P projects on the segment A-B: 0 at A, 1 at B (A == B: 0). */
    public static double along(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz) {
        double abx = bx - ax, aby = by - ay, abz = bz - az;
        double len2 = abx * abx + aby * aby + abz * abz;
        if (len2 < 1e-12) return 0;
        return ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / len2;
    }

    /** Distance from P to the segment A-B (the nearest point on it, ends included). */
    public static double toSegment(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz) {
        double t = Math.max(0, Math.min(1, along(px, py, pz, ax, ay, az, bx, by, bz)));
        double qx = ax + (bx - ax) * t - px, qy = ay + (by - ay) * t - py, qz = az + (bz - az) * t - pz;
        return Math.sqrt(qx * qx + qy * qy + qz * qz);
    }

    /** Is P cut away (not drawn)? The shader's rule: 0 <= t <= 1 and closer than r to the line. r <= 0: nothing is cut. */
    public static boolean cuts(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz, double r) {
        if (!(r > 0)) return false;
        double t = along(px, py, pz, ax, ay, az, bx, by, bz);
        if (t < 0 || t > 1) return false;
        return toSegment(px, py, pz, ax, ay, az, bx, by, bz) < r;
    }

    /** {@code watch tunnel cut radius N}: N in 1-6, or -1 when it isn't. */
    public static double parseRadius(String s) {
        try {
            double d = Double.parseDouble(s == null ? "" : s.trim());
            return d >= MIN_RADIUS && d <= MAX_RADIUS ? d : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** "cut", "cut on", "cut off" -> the new state given the old one (a bare word toggles), or null when not understood. */
    public static Boolean parseCut(String a, boolean now) {
        String t = a == null ? "" : a.trim();
        if (t.equals("cut")) return !now;
        if (t.equals("cut on")) return true;
        if (t.equals("cut off")) return false;
        return null;
    }
}
