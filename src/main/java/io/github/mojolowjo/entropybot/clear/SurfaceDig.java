package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.surface.SurfaceColumns;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.19.5: {@code dig x1 z1 x2 z2 down|up N}. Per column of the rectangle the surface is the first real block from the
 * top down, the same rule as {@link SurfaceColumns} (air, leaves and plants without collision or snow layers are
 * skipped; water counts as the surface). {@code down N}: that block and N-1 below it ({@code down 1}: just the surface
 * block). {@code up N}: the N blocks above it (leaves count there; plants and air don't). Liquid blocks are never in
 * the list (they can't be dug; the dig engine leaves blocks next to them alone anyway). The list goes to the same
 * careful clear as a box ({@code ClearJob.Options.only}). Game-free: JUnit on a fake column source.
 * Loader notes: none (plain Java).
 */
public final class SurfaceDig {
    public static final int MAX_N = 64, MAX_COLUMNS = 64 * 64, MAX_BLOCKS = 20000;

    private SurfaceDig() {}

    /** The world as the builder sees it (kinds as {@link SurfaceColumns}). */
    public interface Source {
        /** SurfaceColumns.AIR, PLANT, LEAVES or GROUND (water is GROUND). */
        int kind(int x, int y, int z);

        /** A liquid block (water, lava): the surface, never dug. */
        boolean liquid(int x, int y, int z);

        /** The first y to look at (at or above the column's highest block). */
        int top(int x, int z);
    }

    /** blocks: what to dig; columns: the columns that had a surface; error: why not (blocks empty then). */
    public record Plan(List<Pos> blocks, int columns, int noSurface, String error) {
        static Plan fail(String why) { return new Plan(List.of(), 0, 0, "error: " + why); }
    }

    public static int clampN(int n) {
        return Math.max(1, Math.min(MAX_N, n));
    }

    /** The surface y of one column, or Integer.MIN_VALUE when none (an unloaded or empty column). */
    public static int surface(Source s, int x, int z, int minY) {
        for (int y = s.top(x, z); y >= minY; y--) {
            int k = s.kind(x, y, z);
            if (k == SurfaceColumns.GROUND) return y;
        }
        return Integer.MIN_VALUE;
    }

    public static Plan build(Source s, int x1, int z1, int x2, int z2, boolean up, int n, int minY, int maxY) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        long cols = (long) (bx - ax + 1) * (bz - az + 1);
        if (cols > MAX_COLUMNS) return Plan.fail("that rectangle is too big (" + MAX_COLUMNS + " columns max)");
        int depth = clampN(n);
        List<Pos> out = new ArrayList<>();
        int columns = 0, none = 0;
        for (int x = ax; x <= bx; x++)
            for (int z = az; z <= bz; z++) {
                int g = surface(s, x, z, minY);
                if (g == Integer.MIN_VALUE) {
                    none++;
                    continue;
                }
                columns++;
                if (up) {
                    for (int y = g + 1; y <= g + depth && y <= maxY; y++) {
                        int k = s.kind(x, y, z);
                        if ((k == SurfaceColumns.GROUND || k == SurfaceColumns.LEAVES) && !s.liquid(x, y, z)) out.add(new Pos(x, y, z));
                    }
                } else {
                    for (int y = g; y > g - depth && y >= minY; y--) {
                        int k = s.kind(x, y, z);
                        if (k != SurfaceColumns.AIR && !s.liquid(x, y, z)) out.add(new Pos(x, y, z));
                    }
                }
                if (out.size() > MAX_BLOCKS) return Plan.fail("that is too many blocks (" + MAX_BLOCKS + " max)");
            }
        return new Plan(out, columns, none, null);
    }

    /** The bounding box of the list (for the area check and the summary), or null when empty. */
    public static ClearBox bounds(List<Pos> blocks) {
        if (blocks.isEmpty()) return null;
        int ax = Integer.MAX_VALUE, ay = ax, az = ax, bx = Integer.MIN_VALUE, by = bx, bz = bx;
        for (Pos p : blocks) {
            ax = Math.min(ax, p.x());
            ay = Math.min(ay, p.y());
            az = Math.min(az, p.z());
            bx = Math.max(bx, p.x());
            by = Math.max(by, p.y());
            bz = Math.max(bz, p.z());
        }
        return ClearBox.of(ax, ay, az, bx, by, bz);
    }
}
