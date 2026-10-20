package io.github.mojolowjo.entropybot.aa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * aa-r2fix (round 2: "couldn't place the white_bed at x y z" and "no room for my bed next to me (2 free blocks in a row)"): the bed
 * went one block east of the bot, whatever stood there. Now the cell is chosen like the chest's ({@link ChestSpot}): a free,
 * standable cell whose 2-cell footprint (the foot here, the head one block on, in any of the four directions) is free, inside
 * my areas, ranked by cost: the walk from the anchor (the table / base / me), plus a penalty for each block it lies off the
 * table or chest, plus a small one for each direction the head could not go (the game picks the head by the way the bot faces,
 * so more free directions means less risk of a refusal). A cell the game refused is remembered by the caller and skipped. Pure;
 * loader notes: none.
 */
public final class BedSpot {
    private BedSpot() {}

    /** One candidate: the foot cell, how many of the four head directions are free, its cost and why. */
    public record Spot(int[] cell, int heads, double cost, String why) {
        public String key() { return cell[0] + " " + cell[1] + " " + cell[2]; }
    }

    private static final java.util.regex.Pattern FOUND_NEAR = java.util.regex.Pattern.compile("^found (-?\\d+) (-?\\d+) (-?\\d+) ");
    private static final java.util.regex.Pattern FOUND_FAR = java.util.regex.Pattern.compile("^(?:found|remembered|known) \\S+ at (-?\\d+) (-?\\d+) (-?\\d+)\\b");

    /**
     * Round 4 fix 3 (live, 4x: {@code NumberFormatException: "white_bed"}): the bed cell in a {@code findBlock} answer. The
     * near form is "found x y z <id> dist N"; the far one (Baritone's scanner or memory) is "found white_bed at x y z, N
     * blocks" - the old parse took "white_bed" for x. Null when the answer names no bed.
     */
    public static int[] foundAt(String reply) {
        if (reply == null) return null;
        for (java.util.regex.Pattern p : new java.util.regex.Pattern[]{FOUND_NEAR, FOUND_FAR}) {
            java.util.regex.Matcher m = p.matcher(reply);
            if (m.find()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        }
        return null;
    }

    /** Blocks of cost per block the bed lies away from being next to the table or chest. */
    public static final double OFF_CAMP = 1.5;
    /** Cost of each head direction that is blocked. */
    public static final double BLOCKED_HEAD = 0.5;
    /** How far from the anchor a bed may stand: the near ring, then the wider one (a night with no room near the camp). */
    public static final int NEAR = 4, FAR = 10;

    private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    /**
     * The free footprints within {@code radius} of {@code anchor} (feet level), cheapest first.
     * @param table the table's cell, null unknown
     * @param chest the chest's cell, null unknown
     * @param me    the bot's cell, never a candidate or a head (a block on the bot is refused)
     * @param free  the game can put a block in the cell now (replaceable, a full block under it, no fluid)
     * @param inside the cell lies inside my areas
     */
    public static List<Spot> rank(int[] anchor, int[] table, int[] chest, int[] me, int radius, Predicate<int[]> free, Predicate<int[]> inside) {
        List<Spot> out = new ArrayList<>();
        if (anchor == null) return out;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int[] c = {anchor[0] + dx, anchor[1], anchor[2] + dz};
            if (same(c, me) || same(c, table) || same(c, chest)) continue;
            if (!free.test(c) || !inside.test(c)) continue;
            int heads = 0;
            for (int[] d : DIRS) {
                int[] h = {c[0] + d[0], c[1], c[2] + d[1]};
                if (!same(h, me) && !same(h, table) && !same(h, chest) && free.test(h) && inside.test(h)) heads++;
            }
            if (heads == 0) continue;
            double walk = Math.sqrt(dx * dx + dz * dz), off = 0;
            StringBuilder why = new StringBuilder("walk ").append(Math.round(walk * 10) / 10.0).append(", ").append(heads).append(" free head side").append(heads == 1 ? "" : "s");
            int near = Integer.MAX_VALUE;
            if (table != null) near = Math.min(near, cheb(c, table));
            if (chest != null) near = Math.min(near, cheb(c, chest));
            if (near != Integer.MAX_VALUE) {
                off = Math.max(0, near - 1) * OFF_CAMP;
                why.append(near <= 1 ? ", next to the camp" : ", " + near + " from the camp");
            }
            out.add(new Spot(c, heads, walk + off + (4 - heads) * BLOCKED_HEAD, why.toString()));
        }
        out.sort(Comparator.comparingDouble(Spot::cost).thenComparingInt(s -> s.cell()[0]).thenComparingInt(s -> s.cell()[2]));
        return out;
    }

    /** The first ranked spot the game has not refused, else null. */
    public static Spot pick(List<Spot> ranked, Set<String> failed) {
        for (Spot s : ranked) if (failed == null || !failed.contains(s.key())) return s;
        return null;
    }

    /** The near ring first; when it holds nothing unrefused, the wider ring (the bed is put down elsewhere, as a shelter step). */
    public static Spot choose(int[] anchor, int[] table, int[] chest, int[] me, Predicate<int[]> free, Predicate<int[]> inside, Set<String> failed) {
        Spot s = pick(rank(anchor, table, chest, me, NEAR, free, inside), failed);
        return s != null ? s : pick(rank(anchor, table, chest, me, FAR, free, inside), failed);
    }

    private static int cheb(int[] a, int[] b) { return Math.max(Math.abs(a[0] - b[0]), Math.abs(a[2] - b[2])); }

    private static boolean same(int[] a, int[] b) { return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }
}
