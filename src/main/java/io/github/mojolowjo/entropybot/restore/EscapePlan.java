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
        return plan(g, feet, dest, towardGoal, false);
    }

    /** requireBreak: only ways out that dig at least one block (the fallback below: never the free step back into the pocket). */
    private static Escape plan(Grid g, int[] feet, int[] dest, boolean towardGoal, boolean requireBreak) {
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
                if (!ok || breaks.size() > MAX_BREAKS || (requireBreak && breaks.isEmpty())) continue;
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
        // 0.27.3 (live, run1 pit): a goal straight up (or any goal no side step gets closer to) left toward with nothing,
        // although a 2-break way out existed: fall back to the fewest-breaks plan rather than report "no safe dig-out"
        if (best == null && toward) return plan(g, feet, dest, false, true);
        return best;
    }

    /**
     * 0.27.3 (d3): the side {dx, dz} to start a staircase up from feet (sides nearest dest first, else east, west,
     * south, north): the first step's feet and head cells (y+1, y+2 beside) are open or breakable, a solid cell under it
     * (y beside), and the cell over the bot's head (y+2) is not blocked (an own roof is broken with force). Null if none.
     */
    public static int[] stairSide(Grid g, int[] feet, int[] dest) {
        List<int[]> order = new ArrayList<>(List.of(SIDES));
        if (dest != null) order.sort(java.util.Comparator.comparingDouble(s -> sq(feet[0] + s[0] - dest[0]) + sq(feet[2] + s[1] - dest[2])));
        if (g.at(feet[0], feet[1] + 2, feet[2]) == Kind.BLOCKED) return null;
        for (int[] s : order) {
            int tx = feet[0] + s[0], tz = feet[2] + s[1];
            Kind under = g.at(tx, feet[1], tz), a = g.at(tx, feet[1] + 1, tz), b = g.at(tx, feet[1] + 2, tz);
            boolean solid = under == Kind.BREAKABLE || under == Kind.FLOOR_ONLY;
            if (solid && (a == Kind.OPEN || a == Kind.BREAKABLE) && (b == Kind.OPEN || b == Kind.BREAKABLE)) return s.clone();
        }
        return null;
    }

    /**
     * 0.27.3 (d2: "no leaves, plants or own blocks to open" named no cell): why plan found nothing, per level side:
     * the first cell that may not be broken (what names it) or how many breaks it would take. "east: persistent
     * leaves at 1 71 0; north: 4 breaks (max 3); ..."
     */
    public static String explain(Grid g, java.util.function.Function<int[], String> what, int[] feet) {
        String[] names = {"east", "west", "south", "north"};
        List<String> out = new ArrayList<>();
        for (int i = 0; i < SIDES.length; i++) {
            int tx = feet[0] + SIDES[i][0], ty = feet[1], tz = feet[2] + SIDES[i][1];
            Kind floor = g.at(tx, ty - 1, tz);
            if (floor != Kind.BREAKABLE && floor != Kind.FLOOR_ONLY) {
                out.add(names[i] + ": no floor at " + tx + " " + (ty - 1) + " " + tz + " (" + what.apply(new int[]{tx, ty - 1, tz}) + ")");
                continue;
            }
            int breaks = 0;
            String bad = null;
            for (int[] c : new int[][]{{tx, ty + 1, tz}, {tx, ty, tz}}) {
                Kind k = g.at(c[0], c[1], c[2]);
                if (k == Kind.OPEN) continue;
                if (k != Kind.BREAKABLE) {
                    bad = what.apply(c) + " at " + c[0] + " " + c[1] + " " + c[2];
                    break;
                }
                breaks++;
            }
            out.add(names[i] + ": " + (bad != null ? bad : breaks > MAX_BREAKS ? breaks + " breaks (max " + MAX_BREAKS + ")" : "open with " + breaks + " break(s)"));
        }
        return String.join("; ", out);
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
