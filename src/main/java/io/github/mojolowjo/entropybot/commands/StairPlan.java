package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.24.4 staircases (pure, JUnit). Live (0.24.3): Baritone could neither go down nor climb a sheer 3x3 shaft, and the
 * corpse at its bottom stayed there. {@code dig stairs <dir> <n>}: one step per block, 1 wide, a 3-high cut per column
 * (feet, head, and the head room for stepping down into it), a torch every 6 steps; done as a chain of small digs and
 * walks, so the dig's guard, leases and water rules apply. {@link #shaft} finds a vertical hole (drop over 3) and its
 * rim; a goto into one is refused with this as the next step, and {@code death} digs down by itself.
 * Loader notes: none (the world is read through {@link Cells}).
 */
public final class StairPlan {
    private StairPlan() {}

    public static final int MAX = 40, TORCH_EVERY = 6, SHAFT_DROP = 3, SHAFT_SCAN = 64;

    /**
     * 0.30.0 (P2): the dig steps of a staircase (down, up, climb) stay on my own engine: each is a 2-3 block cut next to
     * where I stand, dug and walked one at a time; Baritone's builder (the dig default now) digs top layer first and
     * would path-dig toward the top of an up-staircase from the bottom.
     */
    public static final String VIA_ENGINE = " via engine";

    public interface Cells {
        /** No collision (air, a torch, water): a body passes. */
        boolean free(int x, int y, int z);
    }

    /** {dx, dz} for north/south/east/west (n s e w), else null. */
    public static int[] dir(String w) {
        if (w == null) return null;
        return switch (w.toLowerCase()) {
            case "north", "n" -> new int[]{0, -1};
            case "south", "s" -> new int[]{0, 1};
            case "east", "e" -> new int[]{1, 0};
            case "west", "w" -> new int[]{-1, 0};
            default -> null;
        };
    }

    public static String dirName(int dx, int dz) {
        return dz < 0 ? "north" : dz > 0 ? "south" : dx > 0 ? "east" : "west";
    }

    /** The cells a staircase from feet (x, y, z) clears: column i = 1..n at (x+dx*i, z+dz*i), y-i .. y-i+2. */
    public static List<int[]> cells(int x, int y, int z, int dx, int dz, int n) {
        List<int[]> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) for (int yy = y - i; yy <= y - i + 2; yy++) out.add(new int[]{x + dx * i, yy, z + dz * i});
        return out;
    }

    /** The torch spots: every 6th step, in the step before's feet cell (its floor is the cut's floor). */
    public static List<int[]> torches(int x, int y, int z, int dx, int dz, int n) {
        List<int[]> out = new ArrayList<>();
        for (int i = TORCH_EVERY; i <= n; i += TORCH_EVERY) out.add(new int[]{x + dx * (i - 1), y - (i - 1), z + dz * (i - 1)});
        return out;
    }

    /** The chain steps: per column a dig of its 3 cells and a walk onto it; the torches after their step. */
    public static List<String> steps(int x, int y, int z, int dx, int dz, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            int cx = x + dx * i, cz = z + dz * i;
            out.add("dig " + cx + " " + (y - i) + " " + cz + " " + cx + " " + (y - i + 2) + " " + cz + VIA_ENGINE);
            out.add("goto " + cx + " " + (y - i) + " " + cz);
            if (i % TORCH_EVERY == 0) out.add("place torch " + (x + dx * (i - 1)) + " " + (y - i + 1) + " " + (z + dz * (i - 1)));
        }
        return out;
    }

    /**
     * 0.27.3 "dig stairs up <dir> <n>" (and the underground escape's way out of a sealed pit): column i = 1..n at
     * (x+dx*i, z+dz*i) with the feet at y+i. Each step digs the 2x2 between the column before and this one at y+i..y+i+1
     * (the new feet and head, and the head room over the step before to jump up) and walks onto it.
     */
    public static List<String> upSteps(int x, int y, int z, int dx, int dz, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            int px = x + dx * (i - 1), pz = z + dz * (i - 1), cx = x + dx * i, cz = z + dz * i;
            out.add("dig " + Math.min(px, cx) + " " + (y + i) + " " + Math.min(pz, cz) + " " + Math.max(px, cx) + " " + (y + i + 1) + " " + Math.max(pz, cz) + VIA_ENGINE);
            out.add("goto " + cx + " " + (y + i) + " " + cz);
        }
        return out;
    }

    /** 0.30.7: a cell test for {@link #withForce}. */
    public interface CellTest {
        boolean test(int x, int y, int z);
    }

    /**
     * 0.30.7 (review of package 8 item 3): the stair side may now run through placed blocks in my own areas
     * (Jobs.digOutAny); the dig refuses built blocks without force, so every "dig x1 y1 z1 x2 y2 z2 ..." step whose box
     * holds a cell with needsForce gets " force" (once). Other steps are kept as they are.
     */
    public static List<String> withForce(List<String> steps, CellTest needsForce) {
        List<String> out = new ArrayList<>();
        for (String s : steps) {
            String[] w = s.split(" ");
            boolean add = false;
            if (w.length >= 7 && w[0].equals("dig") && !s.endsWith(" force")) {
                try {
                    int x1 = Integer.parseInt(w[1]), y1 = Integer.parseInt(w[2]), z1 = Integer.parseInt(w[3]);
                    int x2 = Integer.parseInt(w[4]), y2 = Integer.parseInt(w[5]), z2 = Integer.parseInt(w[6]);
                    for (int x = x1; x <= x2 && !add; x++) for (int y = y1; y <= y2 && !add; y++) for (int z = z1; z <= z2 && !add; z++)
                        add = needsForce.test(x, y, z);
                } catch (NumberFormatException ignored) {
                    // not a box: leave the step as it is
                }
            }
            out.add(add ? s + " force" : s);
        }
        return out;
    }

    /** 0.27.3: the steps up from feet y to the surface (open sky from surface up), at most MAX; 0 when not below it. */
    public static int upCount(int y, int surface) {
        return Math.max(0, Math.min(MAX, surface - y));
    }

    /** A vertical hole above (x, y, z): {rimX, rimY, rimZ, dx, dz, drop} with (dx, dz) from the hole to the rim, or null. */
    public static int[] shaft(Cells c, int x, int y, int z) {
        if (!c.free(x, y, z) || !c.free(x, y + 1, z)) return null;
        int[][] d = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
        for (int yy = y + 1; yy <= y + SHAFT_SCAN; yy++) {
            if (!c.free(x, yy, z)) return null;                         // a roof: no open shaft
            for (int[] k : d) {
                int nx = x + k[0], nz = z + k[1];
                // a walkable rim cell: free feet and head, something under it
                if (c.free(nx, yy, nz) && c.free(nx, yy + 1, nz) && !c.free(nx, yy - 1, nz)) {
                    int drop = yy - y;
                    return drop > SHAFT_DROP ? new int[]{nx, yy, nz, k[0], k[1], drop} : null;
                }
            }
        }
        return null;
    }

    /**
     * The way down to the bottom of a shaft (rim from {@link #shaft}): walk to the rim, the staircase away from the hole,
     * then a 2-high tunnel back along the bottom to the hole's column.
     */
    public static List<String> down(int[] rim, int hx, int hy, int hz) {
        int n = Math.min(MAX, rim[5]);
        int sx = rim[0], sy = rim[1], sz = rim[2], dx = rim[3], dz = rim[4];
        List<String> out = new ArrayList<>();
        out.add("goto " + sx + " " + sy + " " + sz);
        out.addAll(steps(sx, sy, sz, dx, dz, n));
        int bx = sx + dx * n, by = sy - n, bz = sz + dz * n;
        out.add("dig " + Math.min(bx, hx) + " " + by + " " + Math.min(bz, hz) + " " + Math.max(bx, hx) + " " + (by + 1) + " " + Math.max(bz, hz) + VIA_ENGINE);
        out.add("goto " + hx + " " + by + " " + hz);
        return out;
    }
}
