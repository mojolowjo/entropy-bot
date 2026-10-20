package io.github.mojolowjo.entropybot.aa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * aa-r1fix (round 1: 2x "failed TABLE_FURNACE: stopped at step N (place chest x y z): the game refused placing"): the camp chest
 * was placed at one block east of the base, whatever stood there. Now the cell is chosen like the table's and furnace's:
 * free (the game can put a block there, air above), inside my areas, not the cell the bot or the base stands on, ranked by cost
 * (the walk from the base, plus a penalty for each block it lies off the table). The ranked list holds the fallbacks: a cell
 * the game refused is remembered ({@link #noteFailure}) and the next one is used. Pure; loader notes: none.
 */
public final class ChestSpot {
    private ChestSpot() {}

    /** One candidate: the cell, its cost (walk blocks + off-table penalty) and why. */
    public record Spot(int[] cell, double cost, String why) {
        public String key() { return cell[0] + " " + cell[1] + " " + cell[2]; }
    }

    /** Blocks of cost per block the chest lies away from being next to the table. */
    public static final double OFF_TABLE = 2;
    /** How far from the anchor (the base, else the bot) a chest may stand. */
    public static final int RADIUS = 3;

    /**
     * The free cells around {@code anchor} (feet level), cheapest first.
     * @param table the table's cell, null unknown (no table penalty)
     * @param me    the bot's cell, never a candidate (the game refuses a block on the bot)
     * @param free  the game can put a block in the cell now and the cell above it is free
     * @param inside the cell lies inside my areas
     */
    public static List<Spot> rank(int[] anchor, int[] table, int[] me, Predicate<int[]> free, Predicate<int[]> inside) {
        List<Spot> out = new ArrayList<>();
        if (anchor == null) return out;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            if (dx == 0 && dz == 0) continue;
            int[] c = {anchor[0] + dx, anchor[1], anchor[2] + dz};
            if (same(c, me) || same(c, table)) continue;
            if (!free.test(c) || !inside.test(c)) continue;
            double walk = Math.sqrt(dx * dx + dz * dz), off = 0;
            String why = "walk " + Math.round(walk * 10) / 10.0;
            if (table != null) {
                int beside = Math.max(Math.abs(c[0] - table[0]), Math.abs(c[2] - table[2]));
                off = Math.max(0, beside - 1) * OFF_TABLE;
                why += beside <= 1 ? ", next to the table" : ", " + beside + " from the table";
            }
            out.add(new Spot(c, walk + off, why));
        }
        out.sort(Comparator.comparingDouble(Spot::cost).thenComparingInt(s -> s.cell()[0]).thenComparingInt(s -> s.cell()[2]));
        return out;
    }

    /** The first ranked spot the game has not refused, else null. */
    public static Spot pick(List<Spot> ranked, Set<String> failed) {
        for (Spot s : ranked) if (failed == null || !failed.contains(s.key())) return s;
        return null;
    }

    private static final Pattern PLACE_CHEST = Pattern.compile("place chest (-?\\d+) (-?\\d+) (-?\\d+)");

    /** The cell key ("x y z") of a failed "place chest x y z" step in a task's end text, else null. */
    public static String failedCell(String end) {
        if (end == null) return null;
        Matcher m = PLACE_CHEST.matcher(end);
        return m.find() ? m.group(1) + " " + m.group(2) + " " + m.group(3) : null;
    }

    /** Adds the refused cell of {@code end} (if it was a chest placement) to {@code failed}. */
    public static void noteFailure(Set<String> failed, String end) {
        String k = failedCell(end);
        if (k != null) failed.add(k);
    }

    private static boolean same(int[] a, int[] b) { return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }
}
