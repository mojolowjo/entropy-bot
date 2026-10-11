package io.github.mojolowjo.entropybot.motor;

import io.github.mojolowjo.entropybot.threat.ReachGrid;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.27.0 motor: the named short moves, planned over the threat grid ({@link ReachGrid}) in world coordinates. Pure
 * (JUnit on text grids); loader notes: none. A plan is the list of cells to walk through (the bot's own cell excluded),
 * or a failure with a reason a person can read.
 */
public final class MotorPlan {
    private MotorPlan() {}

    public enum Kind { BACK_OFF, STRAFE, STEP_TO, CLIMB, OUT_OF_WATER, HOLD }

    public static final int STEP_RADIUS = 4, STEP_MAX = 8, WATER_RADIUS = 6, MAX_CELLS = 6;

    /** cells: world {x, y, z} in walking order; ok false = nothing to do, why says why; short = stopped early (why). */
    public record Plan(Kind kind, List<int[]> cells, boolean ok, String why, String name) {
        public int[] end() { return cells.isEmpty() ? null : cells.get(cells.size() - 1); }
    }

    static Plan fail(Kind k, String name, String why) { return new Plan(k, List.of(), false, why, name); }

    /** The 8 directions, from an angle: the nearest of them to (dx, dz). {0, 0} for a zero vector. */
    public static int[] dir8(double dx, double dz) {
        if (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9) return new int[]{0, 0};
        double a = Math.atan2(dz, dx);
        int oct = (int) Math.round(a / (Math.PI / 4));
        double r = oct * Math.PI / 4;
        return new int[]{(int) Math.round(Math.cos(r)), (int) Math.round(Math.sin(r))};
    }

    /**
     * Walk n cells along (sx, sz) (each -1..1), level or one down only. Stops before a wall, a drop of 2+, lava, or a
     * corner it would clip. Used by back off and strafe.
     */
    static Plan line(Kind k, String name, ReachGrid g, int bx, int by, int bz, int sx, int sz, int n) {
        if (g == null) return fail(k, name, "no grid");
        if (sx == 0 && sz == 0) return fail(k, name, "no direction");
        n = Math.max(1, Math.min(MAX_CELLS, n));
        List<int[]> cells = new ArrayList<>();
        int x = bx, y = by, z = bz;
        String stop = null;
        for (int i = 0; i < n; i++) {
            int nx = x + sx, nz = z + sz;
            if (sx != 0 && sz != 0 && (!g.open(x + sx, y, z) || !g.open(x, y, z + sz))) {
                stop = "corner";
                break;
            }
            int ny = Integer.MIN_VALUE;
            for (int dy = 0; dy >= -1; dy--) {
                if (g.standable(nx, y + dy, nz) && (sx != 0 && sz != 0 ? dy == 0 || g.open(nx, y, nz) : g.stepEdge(x, y, z, nx, y + dy, nz))) {
                    ny = y + dy;
                    break;
                }
            }
            if (ny == Integer.MIN_VALUE) {
                if (g.codeAt(nx, y - 1, nz) == ReachGrid.LAVA || g.codeAt(nx, y - 2, nz) == ReachGrid.LAVA || g.codeAt(nx, y, nz) == ReachGrid.LAVA) stop = "lava";
                else if (g.open(nx, y, nz)) stop = "drop";
                else stop = "wall";
                break;
            }
            x = nx;
            y = ny;
            z = nz;
            cells.add(new int[]{x, y, z});
        }
        if (cells.isEmpty()) return fail(k, name, "no standable cell (" + stop + " right there)");
        return new Plan(k, cells, true, cells.size() < n ? "stops at a " + stop + " after " + cells.size() : "clear", name);
    }

    /** back off n: away from the mob (mx, mz world, any precision). */
    public static Plan backOff(ReachGrid g, int bx, int by, int bz, double mx, double mz, int n) {
        int[] d = dir8(bx + 0.5 - mx, bz + 0.5 - mz);
        return line(Kind.BACK_OFF, "back off " + n, g, bx, by, bz, d[0], d[1], n);
    }

    /** strafe left|right n around a mob at (mx, mz): left = counter-clockwise seen from above facing the mob. */
    public static Plan strafe(ReachGrid g, int bx, int by, int bz, double mx, double mz, boolean left, int n) {
        double fx = mx - (bx + 0.5), fz = mz - (bz + 0.5);
        // Minecraft: facing (fx, fz), the left hand is (fz, -fx) (x east, z south, yaw clockwise from south)
        int[] d = left ? dir8(fz, -fx) : dir8(-fz, fx);
        return line(Kind.STRAFE, "strafe " + (left ? "left" : "right") + " " + n, g, bx, by, bz, d[0], d[1], n);
    }

    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    /** step to x y z: a path of at most {@link #STEP_MAX} walk steps, every cell within {@link #STEP_RADIUS} of the bot. */
    public static Plan stepTo(ReachGrid g, int bx, int by, int bz, int tx, int ty, int tz) {
        String name = "step to " + tx + " " + ty + " " + tz;
        if (g == null) return fail(Kind.STEP_TO, name, "no grid");
        if (Math.max(Math.abs(tx - bx), Math.max(Math.abs(ty - by), Math.abs(tz - bz))) > STEP_RADIUS)
            return fail(Kind.STEP_TO, name, "further than " + STEP_RADIUS + " blocks");
        if (!g.standable(tx, ty, tz)) return fail(Kind.STEP_TO, name, "no standable cell there");
        if (tx == bx && ty == by && tz == bz) return new Plan(Kind.STEP_TO, List.of(), true, "already there", name);
        List<int[]> path = search(g, bx, by, bz, STEP_RADIUS, STEP_MAX, c -> c[0] == tx && c[1] == ty && c[2] == tz, false);
        if (path == null) return fail(Kind.STEP_TO, name, "no walk there within " + STEP_MAX + " steps");
        return new Plan(Kind.STEP_TO, path, true, path.size() + " steps", name);
    }

    /** climb: the cell ahead (sx, sz: one axis) is one up and a walker can jump onto it. */
    public static Plan climb(ReachGrid g, int bx, int by, int bz, int sx, int sz) {
        if (g == null) return fail(Kind.CLIMB, "climb", "no grid");
        if (Math.abs(sx) + Math.abs(sz) != 1) return fail(Kind.CLIMB, "climb", "no direction");
        int ax = bx + sx, az = bz + sz;
        if (!g.standable(ax, by + 1, az)) return fail(Kind.CLIMB, "climb", "no block to stand on ahead");
        if (g.codeAt(ax, by, az) == ReachGrid.TALL) return fail(Kind.CLIMB, "climb", "a fence or wall ahead (1.5 high)");
        if (!g.stepEdge(bx, by, bz, ax, by + 1, az)) return fail(Kind.CLIMB, "climb", "no room to jump");
        return new Plan(Kind.CLIMB, List.of(new int[]{ax, by + 1, az}), true, "one up", "climb");
    }

    /** out of water: to the nearest dry standable cell within {@link #WATER_RADIUS}, else swim up (cells: the surface). */
    public static Plan outOfWater(ReachGrid g, int bx, int by, int bz) {
        if (g == null) return fail(Kind.OUT_OF_WATER, "out of water", "no grid");
        if (g.codeAt(bx, by, bz) != ReachGrid.WATER) return fail(Kind.OUT_OF_WATER, "out of water", "not in water");
        List<int[]> path = search(g, bx, by, bz, WATER_RADIUS, 2 * WATER_RADIUS,
                c -> g.codeAt(c[0], c[1], c[2]) != ReachGrid.WATER && g.standable(c[0], c[1], c[2]), true);
        if (path != null) return new Plan(Kind.OUT_OF_WATER, path, true, "to the bank, " + path.size() + " steps", "out of water");
        int y = by;
        while (y < by + 8 && g.codeAt(bx, y + 1, bz) == ReachGrid.WATER) y++;
        if (y == by && g.codeAt(bx, by + 1, bz) != ReachGrid.AIR) return fail(Kind.OUT_OF_WATER, "out of water", "no bank and no air above");
        return new Plan(Kind.OUT_OF_WATER, List.of(new int[]{bx, y + 1, bz}), true, "no bank near: swim up", "out of water");
    }

    public static Plan hold(String target) {
        return new Plan(Kind.HOLD, List.of(), true, "stand and face " + target, "hold");
    }

    interface Goal { boolean at(int[] c); }

    /** BFS over walk steps (in water also swimming: up one, any water neighbour). The path excludes the start. */
    static List<int[]> search(ReachGrid g, int bx, int by, int bz, int radius, int maxSteps, Goal goal, boolean swim) {
        Map<Long, long[]> prev = new HashMap<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        long start = key(bx, by, bz);
        prev.put(start, null);
        q.add(new int[]{bx, by, bz, 0});
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (c[3] > 0 && goal.at(c)) {
                List<int[]> out = new ArrayList<>();
                long k = key(c[0], c[1], c[2]);
                while (k != start) {
                    out.add(0, unkey(k));
                    k = prev.get(k)[0];
                }
                return out;
            }
            if (c[3] >= maxSteps) continue;
            for (int i = 0; i < 4; i++) {
                for (int dy = 1; dy >= -3; dy--) {
                    int nx = c[0] + DX[i], ny = c[1] + dy, nz = c[2] + DZ[i];
                    if (Math.abs(nx - bx) > radius || Math.abs(nz - bz) > radius || Math.abs(ny - by) > radius) continue;
                    boolean ok = g.stepEdge(c[0], c[1], c[2], nx, ny, nz)
                            || (swim && dy >= 0 && dy <= 1 && g.codeAt(c[0], c[1], c[2]) == ReachGrid.WATER && g.open(nx, ny, nz)
                                && (g.codeAt(nx, ny, nz) == ReachGrid.WATER || g.standable(nx, ny, nz)));
                    if (!ok) continue;
                    long k = key(nx, ny, nz);
                    if (prev.containsKey(k)) continue;
                    prev.put(k, new long[]{key(c[0], c[1], c[2])});
                    q.add(new int[]{nx, ny, nz, c[3] + 1});
                    break;          // one landing per column (the highest that works)
                }
            }
            if (swim && g.codeAt(c[0], c[1], c[2]) == ReachGrid.WATER && g.open(c[0], c[1] + 1, c[2])) {
                long k = key(c[0], c[1] + 1, c[2]);
                if (!prev.containsKey(k) && Math.abs(c[1] + 1 - by) <= radius) {
                    prev.put(k, new long[]{key(c[0], c[1], c[2])});
                    q.add(new int[]{c[0], c[1] + 1, c[2], c[3] + 1});
                }
            }
        }
        return null;
    }

    static long key(int x, int y, int z) { return ((x + 0x2000000L) << 38) | ((y + 0x800L) << 26) | (z + 0x2000000L); }

    static int[] unkey(long k) {
        return new int[]{(int) (k >>> 38) - 0x2000000, (int) ((k >>> 26) & 0xFFF) - 0x800, (int) (k & 0x3FFFFFF) - 0x2000000};
    }
}
