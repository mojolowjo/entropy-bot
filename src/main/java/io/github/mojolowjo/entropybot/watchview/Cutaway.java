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

    // ---- 0.19.0: the cone cut (watch cut mode cone, the default) -----------------------------------------------------

    /** The cone's radius at the bot ({@code watch cut radius N} in cone mode). */
    public static final double DEFAULT_CONE_RADIUS = 2.5, MIN_CONE_RADIUS = 1.0, MAX_CONE_RADIUS = 6.0;
    /** The cone is never thinner than this (near the camera, t near 0). */
    public static final double CONE_MIN_RADIUS = 0.3;
    /** The step past the fragment along the ray that picks the cell the ray enters there. */
    public static final double CELL_STEP = 0.01;

    /** The cell (block) the camera ray enters at P: floor(P + dir * 0.01); null when P is at the camera. */
    public static int[] cellEntered(double px, double py, double pz, double cx, double cy, double cz) {
        double vx = px - cx, vy = py - cy, vz = pz - cz;
        double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (dist < 1e-4) return null;
        return new int[]{(int) Math.floor(px + vx / dist * CELL_STEP), (int) Math.floor(py + vy / dist * CELL_STEP),
                (int) Math.floor(pz + vz / dist * CELL_STEP)};
    }

    /**
     * Is the cell at x y z cut by the cone? Its centre Q, t = its distance along the axis camera -> the bot's middle
     * (length L): cut when 0 < t < L - 0.5, Q within max(0.3, R t / L) of the axis, and the cell's top above the bot's
     * feet (y + 1 > minY + 0.1). The shaders' cutCone() (CutCone = R).
     */
    public static boolean coneCell(int x, int y, int z, double cx, double cy, double cz,
                                   double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double r) {
        if (r <= 0) return false;
        if (y + 1.0 <= minY + FEET_LIFT) return false;
        double ax = (minX + maxX) * 0.5 - cx, ay = (minY + maxY) * 0.5 - cy, az = (minZ + maxZ) * 0.5 - cz;
        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-4) return false;
        ax /= len; ay /= len; az /= len;
        double qx = x + 0.5 - cx, qy = y + 0.5 - cy, qz = z + 0.5 - cz;
        double t = qx * ax + qy * ay + qz * az;
        if (t <= 0 || t >= len - 0.5) return false;
        double rx = qx - ax * t, ry = qy - ay * t, rz = qz - az * t;
        return Math.sqrt(rx * rx + ry * ry + rz * rz) < Math.max(CONE_MIN_RADIUS, r * t / len);
    }

    /** The cone rule for the fragment at P: the cell its ray enters there, then {@link #coneCell}. */
    public static boolean coneCuts(double px, double py, double pz, double cx, double cy, double cz,
                                   double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double r) {
        int[] c = cellEntered(px, py, pz, cx, cy, cz);
        return c != null && coneCell(c[0], c[1], c[2], cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ, r);
    }

    /** {@code watch cut radius N} for a mode: cone 1-6, outline 0-3; -1 when out of range or not a number. */
    public static double parseRadius(String s, boolean cone) {
        try {
            double d = Double.parseDouble(s == null ? "" : s.trim());
            double lo = cone ? MIN_CONE_RADIUS : MIN_RADIUS, hi = cone ? MAX_CONE_RADIUS : MAX_RADIUS;
            return d >= lo && d <= hi ? d : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** "cut mode cone|outline" -> true (cone) / false (outline), null when not understood (0.19.0 words; shadow -> null). */
    public static Boolean parseMode(String a) {
        String m = parseModeName(a);
        return m == null || m.equals(SHADOW) ? null : m.equals(CONE);
    }

    // ---- 0.19.1: the shadow cut (watch cut mode shadow, the new default) ---------------------------------------------
    //
    // The cone measured "near the camera-bot line", so a spruce trunk standing beside the bot, slightly nearer than its
    // middle, was cut although it never covers the bot. Shadow asks "in the way": the ray from the camera through the
    // cell's centre must go on to hit the bot's box (grown by the margin m at the sides and top), and the cell must be at
    // least half a block nearer than where that ray enters the grown box. Block-granular like the cone, hard edges.

    public static final String SHADOW = "shadow", CONE = "cone", OUTLINE = "outline";
    /** The shadow mode's margin round the bot's box ({@code watch cut radius N} in shadow mode). */
    public static final double DEFAULT_SHADOW_MARGIN = 0.5, MIN_SHADOW_MARGIN = 0.0, MAX_SHADOW_MARGIN = 2.0;
    /** A cell is cut only when its centre is at least this much nearer than where its ray enters the grown box. */
    public static final double SHADOW_DEPTH = 0.5;

    /**
     * Is the cell at x y z cut by the shadow rule? Q = its centre; the ray camera -> Q must enter the box grown by m
     * (sides and top; bottom = feet + 0.1) at a distance enter with |Q - cam| < enter - 0.5; cells whose top is at or
     * below the feet + 0.1 are never cut. m < 0: no cut. The shaders' cutShadow() (CutShadow = m).
     */
    public static boolean shadowCell(int x, int y, int z, double cx, double cy, double cz,
                                     double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double m) {
        if (m < 0) return false;
        if (y + 1.0 <= minY + FEET_LIFT) return false;
        double qx = x + 0.5 - cx, qy = y + 0.5 - cy, qz = z + 0.5 - cz;
        double dist = Math.sqrt(qx * qx + qy * qy + qz * qz);
        if (dist < 1e-4) return false;
        double e = enter(cx, cy, cz, qx / dist, qy / dist, qz / dist, minX - m, minY + FEET_LIFT, minZ - m, maxX + m, maxY + m, maxZ + m);
        if (e < 0) return false;
        return dist < e - SHADOW_DEPTH;
    }

    /** The shadow rule for the fragment at P: the cell its ray enters there, then {@link #shadowCell}. */
    public static boolean shadowCuts(double px, double py, double pz, double cx, double cy, double cz,
                                     double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double m) {
        int[] c = cellEntered(px, py, pz, cx, cy, cz);
        return c != null && shadowCell(c[0], c[1], c[2], cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ, m);
    }

    /** "cut mode shadow|cone|outline" -> the mode's name, null when not understood. */
    public static String parseModeName(String a) {
        String t = a == null ? "" : a.trim();
        for (String m : new String[]{SHADOW, CONE, OUTLINE}) if (t.equals("cut mode " + m)) return m;
        return null;
    }

    /** {@code watch cut radius N} for a mode by name: shadow 0-2, cone 1-6, outline 0-3; -1 when not. */
    public static double parseRadius(String s, String mode) {
        if (!SHADOW.equals(mode)) return parseRadius(s, CONE.equals(mode));
        try {
            double d = Double.parseDouble(s == null ? "" : s.trim());
            return d >= MIN_SHADOW_MARGIN && d <= MAX_SHADOW_MARGIN ? d : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** "cut", "cut on", "cut off" ->the new state given the old one (a bare word toggles), or null when not understood. */
    public static Boolean parseCut(String a, boolean now) {
        String t = a == null ? "" : a.trim();
        if (t.equals("cut")) return !now;
        if (t.equals("cut on")) return true;
        if (t.equals("cut off")) return false;
        return null;
    }
}
