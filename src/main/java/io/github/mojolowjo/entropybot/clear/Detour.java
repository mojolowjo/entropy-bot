package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Water plan, "going around" (docs/WATER_PLAN.md). Game-free and NOT wired into the dig yet: it needs the fence to count
 * the detour as the job's space (TO-LOOK-AT-LATER 28a/28b, changed elsewhere) and a live test of Baritone walking it.
 *
 * <p>When a tunnel is blocked by something it can't seal, a bypass: a passage 2 high (the box's two lowest rows) from the
 * last slice it dug, around the obstacle, to the first slice past it, at most {@code detour} blocks out from the box's
 * sides (default {@link #DEFAULT}). The passage never breaks a block next to water or lava, never touches liquid, a block
 * entity or a protect box. Outside the box every block it breaks must be natural (not on the built list), unless
 * put-back is allowed: then a built block may go, and the bypass lists it (cell and block id) so the same block can be
 * placed again afterwards.
 */
public final class Detour {
    private Detour() {}

    public static final int DEFAULT = 3;

    /** cells: the passage's cells to open, in walking order (both rows); putBack: built blocks it breaks and their ids; why: when there is none. */
    public record Bypass(List<Pos> cells, Map<Pos, String> putBack, String why) {
        public boolean found() { return why == null; }
    }

    /**
     * A bypass from slice {@code from} to slice {@code to} (along the box's axis {@code a}) or why not.
     *
     * @param protect  a protect box at a cell (null: none)
     * @param putBack  built blocks outside the box may be broken when they are put back afterwards
     */
    public static Bypass plan(ClearWorld w, ClearBox box, WaterShell.Axis a, int from, int to, int detour, boolean putBack, Predicate<Pos> protect) {
        int y0 = box.y1();
        int uLo = a.uMin(box) - detour, uHi = a.uMax(box) + detour;
        int tLo = Math.min(from, to), tHi = Math.max(from, to);
        // BFS over (t, u): a column of 2 cells at y0, y0 + 1
        Map<Long, Long> prev = new HashMap<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        String lastWhy = "no way around within " + detour + " block" + (detour == 1 ? "" : "s") + " of the dig";
        for (int u = a.uMin(box); u <= a.uMax(box); u++) {
            if (!column(w, box, a, from, u, y0, putBack, protect)) continue;
            prev.put(key(from, u), Long.MIN_VALUE);
            q.add(new int[]{from, u});
        }
        int[] goal = null;
        int step = to >= from ? 1 : -1;
        while (!q.isEmpty() && goal == null) {
            int[] c = q.poll();
            int[][] next = { { c[0] + step, c[1] }, { c[0], c[1] + 1 }, { c[0], c[1] - 1 }, { c[0] - step, c[1] } };
            for (int[] n : next) {
                if (n[0] < tLo || n[0] > tHi || n[1] < uLo || n[1] > uHi || prev.containsKey(key(n[0], n[1]))) continue;
                if (!column(w, box, a, n[0], n[1], y0, putBack, protect)) continue;
                prev.put(key(n[0], n[1]), key(c[0], c[1]));
                if (n[0] == to && n[1] >= a.uMin(box) && n[1] <= a.uMax(box)) {
                    goal = n;
                    break;
                }
                q.add(n);
            }
        }
        if (goal == null) return new Bypass(List.of(), Map.of(), lastWhy);
        List<int[]> path = new ArrayList<>();
        for (long k = key(goal[0], goal[1]); k != Long.MIN_VALUE; k = prev.get(k)) path.add(0, new int[]{(int) (k >> 32), (int) k});
        List<Pos> cells = new ArrayList<>();
        Map<Pos, String> back = new LinkedHashMap<>();
        for (int[] tu : path) {
            for (int dy = 0; dy <= 1; dy++) {
                Pos p = a.at(tu[0], tu[1], y0 + dy);
                cells.add(p);
                if (!box.contains(p.x(), p.y(), p.z()) && !w.air(p.x(), p.y(), p.z()) && w.builtBlock(p.x(), p.y(), p.z())) back.put(p, w.id(p.x(), p.y(), p.z()));
            }
        }
        return new Bypass(cells, back, null);
    }

    /** The 2-high column at (t, u) can be opened: each cell air, or breakable by the rules above. */
    static boolean column(ClearWorld w, ClearBox box, WaterShell.Axis a, int t, int u, int y0, boolean putBack, Predicate<Pos> protect) {
        for (int dy = 0; dy <= 1; dy++) {
            Pos p = a.at(t, u, y0 + dy);
            if (!open(w, box, p, putBack, protect)) return false;
        }
        // something to stand on (not liquid, not air: the passage is walked)
        Pos below = a.at(t, u, y0 - 1);
        return !w.air(below.x(), below.y(), below.z()) && !w.fluid(below.x(), below.y(), below.z()) && !w.noCollision(below.x(), below.y(), below.z());
    }

    /** One cell of the passage may be opened. */
    public static boolean open(ClearWorld w, ClearBox box, Pos p, boolean putBack, Predicate<Pos> protect) {
        int x = p.x(), y = p.y(), z = p.z();
        if (protect != null && protect.test(p)) return false;
        if (w.fluid(x, y, z)) return false;
        if (w.air(x, y, z)) return true;                                               // already open (walking past water is fine)
        if (ClearEngine.nextToLiquid(w, x, y, z)) return false;                        // breaking it would let liquid in
        if (w.blockEntity(x, y, z) || w.unbreakable(x, y, z) || w.avoided(x, y, z)) return false;
        if (!w.builtBlock(x, y, z)) return true;                                       // natural
        return putBack && !box.contains(x, y, z);                                      // built, outside the box: only when put back after
    }

    private static long key(int t, int u) {
        return ((long) t << 32) | (u & 0xffffffffL);
    }
}
