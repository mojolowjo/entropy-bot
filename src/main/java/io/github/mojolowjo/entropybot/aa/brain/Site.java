package io.github.mojolowjo.entropybot.aa.brain;

/**
 * AA_ROUND3 2: where a desire's work is done (its option's target cell, the furnace, the base). {@code dim} null = the
 * bot's own dimension. Pure.
 */
public record Site(String dim, int x, int y, int z, int r) {
    /** Travel speed for the sequencer (blocks per second, a walk). */
    public static final double SPEED = 4.3;
    /** A site this near another counts as the same place: no travel between them. */
    public static final double SAME = 16;

    public static Site at(int x, int y, int z) { return new Site(null, x, y, z, 0); }

    public static Site of(int[] p) { return p == null || p.length < 3 ? null : at(p[0], p[1], p[2]); }

    public double dist(Site o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Same dimension (null matches any). */
    public boolean sameDim(Site o) { return dim == null || o.dim == null || dim.equals(o.dim); }

    /** Within {@link #SAME} (plus the radii), same dimension. */
    public boolean near(Site o) { return o != null && sameDim(o) && dist(o) <= SAME + r + o.r; }

    /** Seconds to walk from here to {@code o}: 0 when near, infinite in another dimension or unknown. */
    public double travelS(Site o) {
        if (o == null || !sameDim(o)) return Double.POSITIVE_INFINITY;
        return near(o) ? 0 : dist(o) / SPEED;
    }

    public String text() { return x + " " + y + " " + z; }
}
