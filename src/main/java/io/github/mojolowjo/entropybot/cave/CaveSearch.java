package io.github.mojolowjo.entropybot.cave;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The cave search (B4, docs/BOT_PLAN.md 5.5): from where the bot stands, a breadth-first walk over the cells it
 * can stand in without breaking anything (step up 1, drop up to 3), bounded by {@link #MAX_NODES} and by the
 * distance from the cave's entrance. It returns the nearest "frontier" (a dark cell not visited yet: block light 0,
 * no sky light, not next to lava or water) and the exposed ores of the list next to the cells it reached. Pure
 * Java over a {@link World}, so JUnit can drive it with a made-up cave.
 */
public final class CaveSearch {
    public static final int MAX_NODES = 12000, MAX_REACH = 48, MAX_ORES = 24, MIN_FRONTIER = 6;

    /** What the search needs to know about a cell. */
    public interface World {
        boolean loaded(int x, int y, int z);
        /** Something solid to stand on (a collision shape). */
        boolean solid(int x, int y, int z);
        /** The bot fits through: no collision, no liquid. */
        boolean open(int x, int y, int z);
        boolean liquid(int x, int y, int z);
        boolean lava(int x, int y, int z);
        int blockLight(int x, int y, int z);
        int skyLight(int x, int y, int z);
        /** The block's id ("minecraft:iron_ore"), for the ore list. */
        String block(int x, int y, int z);
    }

    public record Cell(int x, int y, int z, int dist) {}

    public record Ore(int x, int y, int z, String id, int standX, int standY, int standZ, int dist) {}

    public record Result(Cell frontier, List<Ore> ores, int reached, boolean dark) {}

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
    }

    /** The coarse cell (4x4x4) a position belongs to, for the visited set. */
    public static long coarse(int x, int y, int z) {
        return key(x >> 2, y >> 2, z >> 2);
    }

    /** Can the bot stand with its feet in x y z? */
    static boolean standable(World w, int x, int y, int z) {
        return w.loaded(x, y, z) && w.open(x, y, z) && w.open(x, y + 1, z) && w.solid(x, y - 1, z) && !w.lava(x, y - 1, z);
    }

    /**
     * @param visited coarse cells already explored (see {@link #coarse})
     * @param isWanted the ore list: true for a block id to mine
     * @param ex, ey, ez the cave's entrance; cells further than maxFromEntrance from it are not entered
     */
    public static Result search(World w, int sx, int sy, int sz, Set<Long> visited, Predicate<String> isWanted,
                                int ex, int ey, int ez, int maxFromEntrance) {
        return search(w, sx, sy, sz, visited, isWanted, ex, ey, ez, maxFromEntrance, null);
    }

    /** A cell the search may walk through but never offers as a target (S1: inside or near a protect box). */
    @FunctionalInterface
    public interface OffLimits {
        boolean test(int x, int y, int z);
    }

    /** As above; off (may be null): ores and frontier cells it names are never returned (the walk may still pass them). */
    public static Result search(World w, int sx, int sy, int sz, Set<Long> visited, Predicate<String> isWanted,
                                int ex, int ey, int ez, int maxFromEntrance, OffLimits off) {
        Map<Long, Integer> dist = new HashMap<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        List<Ore> ores = new ArrayList<>();
        java.util.Set<Long> oreSeen = new java.util.HashSet<>();
        // start on the cell the bot stands on (or just above, on slabs and paths)
        int y0 = standable(w, sx, sy, sz) ? sy : standable(w, sx, sy + 1, sz) ? sy + 1 : sy;
        queue.add(new int[] { sx, y0, sz });
        dist.put(key(sx, y0, sz), 0);
        Cell frontier = null;
        int[][] sides = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        int[][] around = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        while (!queue.isEmpty() && dist.size() < MAX_NODES) {
            int[] c = queue.poll();
            int x = c[0], y = c[1], z = c[2], d = dist.get(key(x, y, z));
            // ores next to the feet or head cell
            if (ores.size() < MAX_ORES) {
                for (int h = 0; h <= 1; h++) {
                    for (int[] a : around) {
                        int ox = x + a[0], oy = y + h + a[1], oz = z + a[2];
                        long ok = key(ox, oy, oz);
                        if (oreSeen.contains(ok) || !w.loaded(ox, oy, oz)) continue;
                        String id = w.block(ox, oy, oz);
                        if (id == null || !isWanted.test(id) || (off != null && off.test(ox, oy, oz))) continue;
                        oreSeen.add(ok);
                        ores.add(new Ore(ox, oy, oz, id, x, y, z, d));
                    }
                }
            }
            // the frontier: dark, under ground, not visited, a few steps away, away from liquids
            if (frontier == null && d >= MIN_FRONTIER && !visited.contains(coarse(x, y, z)) && w.blockLight(x, y, z) == 0
                    && w.skyLight(x, y, z) == 0 && !nearLiquid(w, x, y, z) && (off == null || !off.test(x, y, z))) {
                frontier = new Cell(x, y, z, d);
            }
            if (d >= MAX_REACH * 2) continue;
            for (int[] s : sides) {
                int nx = x + s[0], nz = z + s[1];
                if (Math.abs(nx - ex) > maxFromEntrance || Math.abs(nz - ez) > maxFromEntrance) continue;
                // level, one up (head room above the bot first), or a drop of up to 3
                int ny = Integer.MIN_VALUE;
                if (standable(w, nx, y, nz)) ny = y;
                else if (w.open(x, y + 2, z) && standable(w, nx, y + 1, nz)) ny = y + 1;
                else if (w.open(nx, y, nz) && w.open(nx, y + 1, nz)) {
                    for (int drop = 1; drop <= 3; drop++) {
                        if (standable(w, nx, y - drop, nz)) { ny = y - drop; break; }
                        if (!w.open(nx, y - drop, nz)) break;
                    }
                }
                if (ny == Integer.MIN_VALUE || Math.abs(ny - ey) > maxFromEntrance) continue;
                long nk = key(nx, ny, nz);
                if (dist.containsKey(nk)) continue;
                dist.put(nk, d + 1);
                queue.add(new int[] { nx, ny, nz });
            }
        }
        ores.sort(java.util.Comparator.comparingInt(Ore::dist));
        boolean dark = w.blockLight(sx, y0, sz) <= 1 && w.skyLight(sx, y0, sz) == 0;
        return new Result(frontier, ores, dist.size(), dark);
    }

    static boolean nearLiquid(World w, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -2; dz <= 2; dz++) {
            if (w.lava(x + dx, y + dy, z + dz)) return true;
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy <= 0 && w.liquid(x + dx, y + dy, z + dz)) return true;
        }
        return false;
    }
}
