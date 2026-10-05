package io.github.mojolowjo.entropybot.watchview;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Camera v2: the air the bot saw from where it stands. A flood through open cells from its eye, within a radius,
 * keeping only cells with a clear line of sight from the eye to the cell's centre (every cell the line passes through
 * is open; where it passes exactly along an edge or corner, all the cells touching it must be open, so no diagonal
 * crack lets it see through). The flood only goes on through cells it keeps, so a pocket behind a wall, or air round
 * a corner it can't see, never gets in. Pure Java (JUnit: SightTest).
 */
public final class Sight {
    public interface Allow {
        boolean ok(int x, int y, int z);
    }

    private Sight() {}

    /**
     * The open cells seen from the eye point (ex, ey, ez), at most radius blocks away and at most max cells, that
     * {@code allow} accepts (e.g. no sky above: the surface is no tunnel). Lookups are cached for the call.
     */
    public static List<Long> seen(double ex, double ey, double ez, int radius, int max, Shell.World world, Allow allow) {
        Map<Long, Integer> cache = new HashMap<>();
        Shell.World w = (x, y, z) -> cache.computeIfAbsent(CellKey.of(x, y, z), k -> world.kind(x, y, z));
        int sx = floor(ex), sy = floor(ey), sz = floor(ez);
        List<Long> out = new ArrayList<>();
        if (w.kind(sx, sy, sz) != Shell.OPEN) return out;
        Set<Long> visited = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{sx, sy, sz});
        visited.add(CellKey.of(sx, sy, sz));
        double r2 = (double) radius * radius;
        while (!queue.isEmpty() && out.size() < max) {
            long[] c = queue.poll();
            int x = (int) c[0], y = (int) c[1], z = (int) c[2];
            if (allow.ok(x, y, z)) out.add(CellKey.of(x, y, z));
            for (int[] o : Shell.OFF) {
                int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                long k = CellKey.of(nx, ny, nz);
                if (visited.contains(k)) continue;
                visited.add(k);
                double dx = nx + 0.5 - ex, dy = ny + 0.5 - ey, dz = nz + 0.5 - ez;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                if (w.kind(nx, ny, nz) != Shell.OPEN) continue;
                if (!clear(ex, ey, ez, nx + 0.5, ny + 0.5, nz + 0.5, w)) continue;
                queue.add(new long[]{nx, ny, nz});
            }
        }
        return out;
    }

    static int floor(double v) { return (int) Math.floor(v); }

    /**
     * True when every cell the segment from (x0,y0,z0) to (x1,y1,z1) passes through is open (voxel walk, Amanatides
     * and Woo). Where the segment crosses an edge or corner exactly, every cell touching that point must be open.
     */
    public static boolean clear(double x0, double y0, double z0, double x1, double y1, double z1, Shell.World w) {
        int x = floor(x0), y = floor(y0), z = floor(z0);
        int tx = floor(x1), ty = floor(y1), tz = floor(z1);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0, stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0, stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double inf = Double.POSITIVE_INFINITY;
        double tMaxX = stepX > 0 ? (x + 1 - x0) / dx : stepX < 0 ? (x0 - x) / -dx : inf;
        double tMaxY = stepY > 0 ? (y + 1 - y0) / dy : stepY < 0 ? (y0 - y) / -dy : inf;
        double tMaxZ = stepZ > 0 ? (z + 1 - z0) / dz : stepZ < 0 ? (z0 - z) / -dz : inf;
        double tdX = stepX != 0 ? 1 / Math.abs(dx) : inf, tdY = stepY != 0 ? 1 / Math.abs(dy) : inf, tdZ = stepZ != 0 ? 1 / Math.abs(dz) : inf;
        final double eps = 1e-9;
        for (int guard = 0; guard < 4096; guard++) {
            if (x == tx && y == ty && z == tz) return true;
            double t = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (t > 1 + eps) return true;                 // the end point is in this cell (rounding)
            boolean bx = tMaxX - t < eps, by = tMaxY - t < eps, bz = tMaxZ - t < eps;
            int n = (bx ? 1 : 0) + (by ? 1 : 0) + (bz ? 1 : 0);
            if (n > 1) {
                // an edge or corner: the cells stepping on only some of the axes touch it too
                for (int m = 1; m < 7; m++) {
                    boolean mx = (m & 1) != 0, my = (m & 2) != 0, mz = (m & 4) != 0;
                    if ((mx && !bx) || (my && !by) || (mz && !bz)) continue;
                    int cnt = (mx ? 1 : 0) + (my ? 1 : 0) + (mz ? 1 : 0);
                    if (cnt == n) continue;                // the full step is checked below
                    if (w.kind(x + (mx ? stepX : 0), y + (my ? stepY : 0), z + (mz ? stepZ : 0)) != Shell.OPEN) return false;
                }
            }
            if (bx) { x += stepX; tMaxX += tdX; }
            if (by) { y += stepY; tMaxY += tdY; }
            if (bz) { z += stepZ; tMaxZ += tdZ; }
            if (w.kind(x, y, z) != Shell.OPEN) return false;
        }
        return false;
    }
}
