package io.github.mojolowjo.entropybot.surface;

/**
 * 0.19.3: one chunk's surface for the dashboard's RTS view (pure, JUnit on a fake column source). Per column, walking
 * down from the top: the first LEAVES block is the canopy, PLANT blocks (grass, flowers, saplings, vines, small
 * mushrooms, snow layers, torches) are skipped, the first GROUND block is the ground (water: its surface). Index =
 * (z &amp; 15) * 16 + (x &amp; 15). File format v1: {@code {"v":1,"dim":..,"cx":..,"cz":..,"t":ms,"g":[256],"f":[256],"c":[256],"l":[256]}}.
 * Format v2 (0.19.4) adds u (the top ground run's underside), g2/f2/u2 (the next ground run below it: floating islands,
 * overhangs; -1 = none), each [256], {@code "v":2}. A run that reaches the world's min build height has its underside = that height.
 * Loader notes: none (plain Java).
 */
public final class SurfaceColumns {
    public static final int AIR = 0, PLANT = 1, LEAVES = 2, GROUND = 3;

    /** The world as the scan sees it (the game side reads the client level). */
    public interface Source {
        /** AIR, PLANT, LEAVES or GROUND. */
        int kind(int x, int y, int z);

        /** The SurfaceFamily index of a GROUND block. */
        int family(int x, int y, int z);

        /** The first y to look at (at or above the column's highest block). */
        int top(int x, int z);
    }

    /** What a column scan asks to log (a throw: the column becomes -1). */
    public interface ErrorSink {
        void error(int x, int z, Throwable t);
    }

    public final int[] g = new int[256], f = new int[256], c = new int[256], l = new int[256];
    /** v2: the top run's underside; the next run below it (top, family, underside); -1 = none. */
    public final int[] u = new int[256], g2 = new int[256], f2 = new int[256], u2 = new int[256];

    public static int index(int x, int z) { return (z & 15) * 16 + (x & 15); }

    /** Scans the 256 columns of chunk cx cz; minY: the world's bottom. Never throws: a failing column is -1/0/-1/0. */
    public static SurfaceColumns scan(Source src, int cx, int cz, int minY, ErrorSink errs) {
        SurfaceColumns s = new SurfaceColumns();
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = (cx << 4) + lx, z = (cz << 4) + lz, i = index(x, z);
                s.g[i] = -1;
                s.c[i] = -1;
                s.u[i] = -1;
                s.g2[i] = -1;
                s.u2[i] = -1;
                try {
                    int canopy = -1;
                    for (int y = src.top(x, z); y >= minY; y--) {
                        int k = src.kind(x, y, z);
                        if (k == LEAVES) {
                            if (canopy < 0) canopy = y;
                        } else if (k == GROUND) {
                            int fam = src.family(x, y, z);
                            s.g[i] = y;
                            s.f[i] = fam;
                            s.l[i] = fam == SurfaceFamily.LOG ? 1 : 0;
                            s.c[i] = canopy;
                            break;
                        }
                    }
                    if (s.g[i] < 0) s.c[i] = canopy;
                    else runsBelow(src, x, z, s.g[i], minY, s, i);
                } catch (RuntimeException e) {
                    s.g[i] = -1;
                    s.f[i] = 0;
                    s.c[i] = -1;
                    s.l[i] = 0;
                    s.u[i] = -1;
                    s.g2[i] = -1;
                    s.f2[i] = 0;
                    s.u2[i] = -1;
                    if (errs != null) errs.error(x, z, e);
                }
            }
        return s;
    }

    /**
     * P4 (0.19.10, the fast channel's GET /column): one column by the same rule as {@link #scan}: {ground y, family,
     * canopy y}; ground and canopy -1 when there is none. A throwing source gives {-1, 0, -1}.
     */
    public static int[] column(Source src, int x, int z, int minY) {
        int canopy = -1;
        try {
            for (int y = src.top(x, z); y >= minY; y--) {
                int k = src.kind(x, y, z);
                if (k == LEAVES) {
                    if (canopy < 0) canopy = y;
                } else if (k == GROUND) {
                    return new int[] {y, src.family(x, y, z), canopy};
                }
            }
            return new int[] {-1, 0, canopy};
        } catch (RuntimeException e) {
            return new int[] {-1, 0, -1};
        }
    }

    /**
     * v2: from the top ground block gy, walks down the GROUND run to its underside u (water and the solid under it are one
     * run), then across AIR, PLANT and LEAVES to the next GROUND block (g2, f2) and down its run to u2. A run that reaches
     * minY (the world's min build height) has its underside AT minY: the page draws it from the floor. One walk of the
     * column at most (gy - minY steps); deeper runs are ignored.
     */
    private static void runsBelow(Source src, int x, int z, int gy, int minY, SurfaceColumns s, int i) {
        int y = gy;
        while (y - 1 >= minY && src.kind(x, y - 1, z) == GROUND) y--;
        s.u[i] = y;
        y--;
        while (y >= minY && src.kind(x, y, z) != GROUND) y--;
        if (y < minY) return;
        s.g2[i] = y;
        s.f2[i] = src.family(x, y, z);
        while (y - 1 >= minY && src.kind(x, y - 1, z) == GROUND) y--;
        s.u2[i] = y;
    }

    /** The file's JSON (format v2: v1 plus u, g2, f2, u2). */
    public String toJson(String dim, int cx, int cz, long t) {
        StringBuilder sb = new StringBuilder(6144);
        sb.append("{\"v\":2,\"dim\":\"").append(escape(dim)).append("\",\"cx\":").append(cx).append(",\"cz\":").append(cz).append(",\"t\":").append(t);
        arr(sb, "g", g);
        arr(sb, "f", f);
        arr(sb, "c", c);
        arr(sb, "l", l);
        arr(sb, "u", u);
        arr(sb, "g2", g2);
        arr(sb, "f2", f2);
        arr(sb, "u2", u2);
        return sb.append('}').toString();
    }

    private static void arr(StringBuilder sb, String name, int[] a) {
        sb.append(",\"").append(name).append("\":[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        sb.append(']');
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** The file name of a chunk: {@code <cx>.<cz>.json}. */
    public static String fileName(int cx, int cz) { return cx + "." + cz + ".json"; }

    /** The dimension's folder name: ':' and '/' as '_' (like the map tiles). */
    public static String dimFolder(String dim) { return dim.replace(':', '_').replace('/', '_'); }
}
