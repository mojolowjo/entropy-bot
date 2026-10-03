package io.github.mojolowjo.entropybot.clear;

import java.util.Collection;

/** A box of blocks, corners inclusive, always normalised (x1 <= x2 ...). The bridge's { x1, y1, z1, x2, y2, z2 }. */
public record ClearBox(int x1, int y1, int z1, int x2, int y2, int z2) {
    public ClearBox {
        if (x1 > x2 || y1 > y2 || z1 > z2) throw new IllegalArgumentException("box corners out of order (use ClearBox.of)");
    }

    /** A box from any two corners. */
    public static ClearBox of(int ax, int ay, int az, int bx, int by, int bz) {
        return new ClearBox(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
    }

    /** The bounding box of some cells (startClear's box for an "only" list). */
    public static ClearBox around(Collection<Pos> cells) {
        int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE;
        for (Pos p : cells) {
            x1 = Math.min(x1, p.x()); y1 = Math.min(y1, p.y()); z1 = Math.min(z1, p.z());
            x2 = Math.max(x2, p.x()); y2 = Math.max(y2, p.y()); z2 = Math.max(z2, p.z());
        }
        if (cells.isEmpty()) throw new IllegalArgumentException("no cells");
        return new ClearBox(x1, y1, z1, x2, y2, z2);
    }

    public boolean contains(int x, int y, int z) {
        return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    /** The bridge's distToBox: how far a point (a player's position) is from the box, 0 inside. */
    public double distTo(double x, double y, double z) {
        double dx = Math.max(Math.max(x1 - x, 0), x - x2 - 1);
        double dy = Math.max(Math.max(y1 - y, 0), y - y2 - 1);
        double dz = Math.max(Math.max(z1 - z, 0), z - z2 - 1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public ClearBox grow(int n) {
        return new ClearBox(x1 - n, y1 - n, z1 - n, x2 + n, y2 + n, z2 + n);
    }

    public long volume() {
        return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    }

    @Override
    public String toString() {
        return x1 + " " + y1 + " " + z1 + " to " + x2 + " " + y2 + " " + z2;
    }
}
