package io.github.mojolowjo.entropybot.restore;

import java.util.ArrayList;
import java.util.List;

/**
 * P1: the escape dig-out. When unsticking finds no free step, this picks the one step (the four sides; level, one up
 * or one down) that needs the fewest blocks broken, never more than {@link #MAX_BREAKS}, ties going toward the
 * destination. A step needs its feet and head cells open (plus the cell over the bot's head to jump up, or the cell at
 * head height beside it to drop down) and a full block under the new feet, which is never broken. Loader-neutral:
 * the {@link Grid} says what each cell is.
 */
public final class EscapePlan {
    private EscapePlan() {}

    public static final int MAX_BREAKS = 3;

    public enum Kind {
        /** Nothing to bump into, no fluid. */
        OPEN,
        /** A full natural block the escape may break (inside the areas, outside protect boxes, no fluid next to it). */
        BREAKABLE,
        /** A full block that must stay (built, a container, by fluid, in a protect box): only a floor. */
        FLOOR_ONLY,
        /** Anything else (fluid, a partial block, unloaded): neither passed nor stood on. */
        BLOCKED
    }

    public interface Grid {
        Kind at(int x, int y, int z);
    }

    public record Escape(int[] feet, List<int[]> breaks) {}

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** The cheapest step out from feet, or null when every step needs a forbidden block or more than MAX_BREAKS. */
    public static Escape plan(Grid g, int[] feet, int[] dest) {
        return plan(g, feet, dest, false);
    }

    /**
     * towardGoal (0.19.7, a walk that ended short): only steps clearly closer to dest count (never back into the
     * pocket it came from), nearest to dest first, then the fewest breaks; so a sealed pit is climbed toward the goal.
     */
    public static Escape plan(Grid g, int[] feet, int[] dest, boolean towardGoal) {
        boolean toward = towardGoal && dest != null;
        double here = dest == null ? 0 : Math.sqrt(sq(feet[0] - dest[0]) + sq(feet[1] - dest[1]) + sq(feet[2] - dest[2]));
        Escape best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy : new int[]{0, 1, -1}) {
            for (int[] s : SIDES) {
                int tx = feet[0] + s[0], ty = feet[1] + dy, tz = feet[2] + s[1];
                Kind floor = g.at(tx, ty - 1, tz);
                if (floor != Kind.BREAKABLE && floor != Kind.FLOOR_ONLY) continue;
                List<int[]> need = new ArrayList<>();
                // highest first, so nothing is left hanging over a hole the bot steps into
                if (dy == 1) need.add(new int[]{feet[0], feet[1] + 2, feet[2]});
                if (dy == 1) need.add(new int[]{tx, ty + 1, tz});
                if (dy == -1) need.add(new int[]{tx, feet[1] + 1, tz});
                if (dy != 1) need.add(new int[]{tx, ty + 1, tz});
                need.add(new int[]{tx, ty, tz});
                List<int[]> breaks = new ArrayList<>();
                boolean ok = true;
                for (int[] c : need) {
                    Kind k = g.at(c[0], c[1], c[2]);
                    if (k == Kind.OPEN) continue;
                    if (k != Kind.BREAKABLE) {
                        ok = false;
                        break;
                    }
                    if (!contains(breaks, c)) breaks.add(c);
                }
                if (!ok || breaks.size() > MAX_BREAKS) continue;
                double d = dest == null ? 0 : Math.sqrt(sq(tx - dest[0]) + sq(ty - dest[1]) + sq(tz - dest[2]));
                if (toward) {
                    if (d >= here - 0.5) continue;
                    if (best == null || d < bestDist - 1e-9 || (Math.abs(d - bestDist) < 1e-9 && breaks.size() < best.breaks().size())) {
                        best = new Escape(new int[]{tx, ty, tz}, breaks);
                        bestDist = d;
                    }
                    continue;
                }
                if (best == null || breaks.size() < best.breaks().size() || (breaks.size() == best.breaks().size() && d < bestDist - 1e-9)) {
                    best = new Escape(new int[]{tx, ty, tz}, breaks);
                    bestDist = d;
                }
            }
        }
        return best;
    }

    private static double sq(double v) { return v * v; }

    private static boolean contains(List<int[]> l, int[] c) {
        for (int[] o : l) if (o[0] == c[0] && o[1] == c[1] && o[2] == c[2]) return true;
        return false;
    }

    /** "dug out 2 blocks at x y z, put back 2" (put back: -1 = not yet). */
    public static String report(int dug, int[] at, int putBack) {
        String s = "dug out " + dug + (dug == 1 ? " block" : " blocks") + " at " + at[0] + " " + at[1] + " " + at[2];
        return putBack < 0 ? s : s + ", put back " + putBack;
    }
}
