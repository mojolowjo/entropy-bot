package io.github.mojolowjo.entropybot.watchview;

/**
 * The tunnel view's cutaway ({@code watch tunnel cut}; the owner, 2026-10-04: "the only things I don't want drawn are
 * things in between the camera and the character"). The fragment shaders {@code watch_cut_*.fsh} do exactly this per
 * pixel; this class is the same maths in Java, for JUnit and the words. Pure.
 *
 * <p><b>0.17.1: the bot's silhouette, not a cylinder.</b> 0.16.1 cut every fragment within 1.5 blocks of the line from
 * the camera to the bot's middle. With the camera only 4-6 blocks away, the ground and trunks near that line fell inside
 * the cylinder although they hid nothing: a plane meeting a cylinder at a shallow angle is a long ellipse, so the floor
 * showed big dark crescents and discs (the cut faces had nothing recorded behind them), and a trunk near the camera lost
 * a curved bite. Now a fragment at P is cut only when the ray from the camera through P goes on to hit the bot's box
 * (grown by a margin at the sides and top, never below its feet) and P lies before the point where the ray enters the
 * box: i.e. P covers part of the bot (or the margin round it) on the screen and is nearer than the bot. The hole is the
 * bot's projected outline plus the margin, at any angle; the floor the bot stands on and everything behind it stay.
 *
 * <p>The edge: the shader picks the margin per pixel between {@code inner} and {@code outer} from a 4x4 ordered dither
 * on the screen position, so the hole's rim is a short stipple instead of a hard line (cheap: no blending needed, it
 * works with the dollhouse's opaque depth-tested faces).
 */
public final class Cutaway {
    /** The margin round the bot's box in blocks ({@code watch tunnel cut radius N}). */
    public static final double DEFAULT_RADIUS = 0.6, MIN_RADIUS = 0.0, MAX_RADIUS = 3.0;
    /** The inner (always cut) margin is this share of the outer one; between them the dither. */
    public static final double INNER_SHARE = 0.5;
    /** The box's bottom is raised this much: the ground under the bot's feet (drawn 0.004 above the block) is never cut. */
    public static final double FEET_LIFT = 0.1;
    /** A fragment this close in front of the box is kept (rounding). */
    public static final double EPS = 0.02;

    private Cutaway() {}

    /**
     * The distance along the unit ray (o + t d) at which it enters the box lo..hi, or -1 when it misses the box or the
     * box lies behind (the origin inside the box counts as a miss: the camera is never inside the bot).
     */
    public static double enter(double ox, double oy, double oz, double dx, double dy, double dz,
                               double lx, double ly, double lz, double hx, double hy, double hz) {
        double tn = Double.NEGATIVE_INFINITY, tf = Double.POSITIVE_INFINITY;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {lx, ly, lz}, hi = {hx, hy, hz};
        for (int a = 0; a < 3; a++) {
            double dd = Math.abs(d[a]) < 1e-9 ? (d[a] < 0 ? -1e-9 : 1e-9) : d[a];
            double t0 = (lo[a] - o[a]) / dd, t1 = (hi[a] - o[a]) / dd;
            tn = Math.max(tn, Math.min(t0, t1));
            tf = Math.min(tf, Math.max(t0, t1));
        }
        if (tf < Math.max(tn, 0) || tn < 0) return -1;
        return tn;
    }

    /**
     * Is the fragment at P cut away? cam = the camera; the bot's box min/max (its real box: the feet lift and the margin
     * are added here, as the shader does); margin = the margin for this pixel (between inner and outer); margin < 0: no
     * cut at all.
     */
    public static boolean cuts(double px, double py, double pz, double cx, double cy, double cz,
                               double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double margin) {
        if (margin < 0) return false;
        double vx = px - cx, vy = py - cy, vz = pz - cz;
        double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (dist < 1e-4) return false;
        double dx = vx / dist, dy = vy / dist, dz = vz / dist;
        double ly = minY + FEET_LIFT;
        double grown = enter(cx, cy, cz, dx, dy, dz, minX - margin, ly, minZ - margin, maxX + margin, maxY + margin, maxZ + margin);
        if (grown < 0) return false;
        double real = enter(cx, cy, cz, dx, dy, dz, minX, ly, minZ, maxX, maxY, maxZ);
        double limit = real >= 0 ? real : grown;
        return dist < limit - EPS;
    }

    /** The shader's 4x4 ordered dither (Bayer) at pixel x y: 0..1. */
    public static double dither(int x, int y) {
        int[] m = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
        return (m[(Math.floorMod(x, 4)) + 4 * Math.floorMod(y, 4)] + 0.5) / 16.0;
    }

    /** The margin for a pixel: from the inner to the outer margin by the dither. */
    public static double marginAt(double outer, int x, int y) {
        double inner = outer * INNER_SHARE;
        return inner + (outer - inner) * dither(x, y);
    }

    /** {@code watch tunnel cut radius N}: the margin round the bot in blocks, 0-3, or -1 when it isn't. */
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
