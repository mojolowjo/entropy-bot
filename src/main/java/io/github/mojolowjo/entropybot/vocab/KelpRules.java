package io.github.mojolowjo.entropybot.vocab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * aa-kelpfish: {@code gather kelp <n> [<min>m]}, the food way "kelp from the water" (brain-b1: skipped, no verb). Pure.
 *
 * <p>Game rules (verified in the 1.21.1 sources): a kelp column is one {@code kelp} (the growing tip) over any number
 * of {@code kelp_plant} blocks; breaking a block makes every block above it break too (the plant can't survive without
 * its support) and each one drops a kelp item. So the cut goes one block above the base: the base stays and regrows,
 * the rest of the column drops. Kelp only lives in water (a source or flowing water block holds it), so the bot works
 * with its head in the water: the air reflex owns surfacing, and this verb waits for a full-enough breath
 * ({@link #mayDive}) before diving for the next column. Smelting kelp gives {@code dried_kelp} ({@code cook kelp n}).
 *
 * <p>Loader notes: none here; the game side is {@code commands.Kelping} (vanilla block states, the dig verb's water mode).
 */
public final class KelpRules {
    private KelpRules() {}

    public static final String KELP = "minecraft:kelp", DRIED = "minecraft:dried_kelp";
    /** Block ids that form a column. */
    public static final List<String> BLOCKS = List.of("minecraft:kelp", "minecraft:kelp_plant");
    public static final int RANGE = 24;
    public static final int DEFAULT_N = 8, DEFAULT_MINUTES = 10, MAX_MINUTES = 30;

    /** cook: smelt the kelp into dried kelp at the end (the word cook after the numbers). */
    public record Parsed(int n, int minutes, boolean cook) {}

    /** True when the gather line is about kelp: first word "kelp". */
    public static boolean isKelp(String rest) {
        String[] w = words(rest);
        return w.length > 0 && w[0].equals("kelp");
    }

    private static String[] words(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        return r.isEmpty() ? new String[0] : r.split("\\s+");
    }

    /** "kelp [n] [<min>m] [cook]" or "kelp status": {n, minutes}; null = bad (status is handled by the caller). */
    public static Parsed parse(String rest) {
        String[] w = words(rest);
        if (w.length == 0 || !w[0].equals("kelp")) return null;
        int n = DEFAULT_N, minutes = DEFAULT_MINUTES;
        boolean cook = false;
        for (int i = 1; i < w.length; i++) {
            if (w[i].matches("^\\d{1,3}$") && i == 1) {
                n = Integer.parseInt(w[i]);
                if (n < 1) return null;
            } else if (w[i].matches("^\\d{1,2}m$")) {
                minutes = Integer.parseInt(w[i].substring(0, w[i].length() - 1));
                if (minutes < 1 || minutes > MAX_MINUTES) return null;
            } else if (w[i].equals("cook") && i == w.length - 1) cook = true;
            else return null;
        }
        return new Parsed(n, minutes, cook);
    }

    /** Kelp (plain, not dried) in the bag. Dried kelp counts too: the goal is food, cooked or not. */
    public static int have(Map<String, Integer> bag) {
        return bag.getOrDefault(KELP, 0) + bag.getOrDefault(DRIED, 0);
    }

    public static boolean done(Map<String, Integer> bag, int n) {
        return have(bag) >= n;
    }

    /** One kelp column: x, z, the lowest and highest kelp y. */
    public record Column(int x, int z, int baseY, int topY) {
        /** Kelp items the cut drops: every block above the base. */
        public int yield() { return topY - baseY; }

        /** The block to break: one above the base (the base regrows). */
        public int[] cut() { return new int[]{x, baseY + 1, z}; }
    }

    /** Groups seen kelp blocks ({x,y,z}) into columns. */
    public static List<Column> columns(List<int[]> blocks) {
        Map<Long, int[]> m = new TreeMap<>();
        for (int[] b : blocks) {
            long k = (((long) b[0]) << 32) ^ (b[2] & 0xffffffffL);
            int[] c = m.get(k);
            if (c == null) m.put(k, new int[]{b[0], b[2], b[1], b[1]});
            else { c[2] = Math.min(c[2], b[1]); c[3] = Math.max(c[3], b[1]); }
        }
        List<Column> out = new ArrayList<>();
        for (int[] c : m.values()) out.add(new Column(c[0], c[1], c[2], c[3]));
        return out;
    }

    /**
     * The column to cut next: a column of at least 2 blocks (yield >= 1) whose cut cell passes {@code inAreas}, the
     * nearest first, but a column that yields more beats one that is 6 blocks closer per extra kelp. Null: none.
     */
    public static Column pick(List<Column> cols, int mx, int my, int mz, Predicate<int[]> inAreas) {
        Column best = null;
        double bestS = Double.MAX_VALUE;
        for (Column c : cols) {
            if (c.yield() < 1) continue;
            int[] cut = c.cut();
            if (inAreas != null && !inAreas.test(cut)) continue;
            double d = Math.sqrt(Math.pow(cut[0] - mx, 2) + Math.pow(cut[1] - my, 2) + Math.pow(cut[2] - mz, 2));
            if (d > RANGE) continue;
            double s = d - 6.0 * Math.min(c.yield(), 6);
            if (s < bestS) { bestS = s; best = c; }
        }
        return best;
    }

    /** May the next column be dived for? Dry, or under water with at least two thirds of the breath. The air reflex owns the rest. */
    public static boolean mayDive(boolean underWater, int air, int maxAir) {
        return !underWater || air * 3 >= maxAir * 2;
    }

    /** The dig line for one cut: the dig verb's water mode (a plain dig refuses a cell in water). */
    public static String digLine(Column c) {
        int[] k = c.cut();
        return "dig " + k[0] + " " + k[1] + " " + k[2] + " " + k[0] + " " + k[1] + " " + k[2] + " water";
    }
}

