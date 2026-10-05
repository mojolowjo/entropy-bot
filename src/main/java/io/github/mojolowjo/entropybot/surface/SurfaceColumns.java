package io.github.mojolowjo.entropybot.surface;

/**
 * 0.19.3: one chunk's surface for the dashboard's RTS view (pure, JUnit on a fake column source). Per column, walking
 * down from the top: the first LEAVES block is the canopy, PLANT blocks (grass, flowers, saplings, vines, small
 * mushrooms, snow layers, torches) are skipped, the first GROUND block is the ground (water: its surface). Index =
 * (z &amp; 15) * 16 + (x &amp; 15). File format v1: {@code {"v":1,"dim":..,"cx":..,"cz":..,"t":ms,"g":[256],"f":[256],"c":[256],"l":[256]}}.
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

    public static int index(int x, int z) { return (z & 15) * 16 + (x & 15); }

    /** Scans the 256 columns of chunk cx cz; minY: the world's bottom. Never throws: a failing column is -1/0/-1/0. */
    public static SurfaceColumns scan(Source src, int cx, int cz, int minY, ErrorSink errs) {
        SurfaceColumns s = new SurfaceColumns();
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = (cx << 4) + lx, z = (cz << 4) + lz, i = index(x, z);
                s.g[i] = -1;
                s.c[i] = -1;
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
                } catch (RuntimeException e) {
                    s.g[i] = -1;
                    s.f[i] = 0;
                    s.c[i] = -1;
                    s.l[i] = 0;
                    if (errs != null) errs.error(x, z, e);
                }
            }
        return s;
    }

    /** The file's JSON (format v1). */
    public String toJson(String dim, int cx, int cz, long t) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"v\":1,\"dim\":\"").append(escape(dim)).append("\",\"cx\":").append(cx).append(",\"cz\":").append(cz).append(",\"t\":").append(t);
        arr(sb, "g", g);
        arr(sb, "f", f);
        arr(sb, "c", c);
        arr(sb, "l", l);
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
