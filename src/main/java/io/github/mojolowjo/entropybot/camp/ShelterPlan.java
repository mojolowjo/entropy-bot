package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 0.24.3 {@code shelter}: a 3x3 shell (interior 1x2) around the bot's own cell. Pure rules, JUnit-tested: which cells
 * make the shell, which block to build it from, which of its blocks the bot may break again (only its own), where it
 * opens to leave, and the stage machine (build, wait for day or the timer, open, step out, close when kept).
 * Loader notes: none (the game side is commands/ShelterJob: vanilla useItemOn / startDestroyBlock).
 */
public final class ShelterPlan {
    private ShelterPlan() {}

    /** Sides in the order they are tried for the way out: north, east, south, west (dx, dz). */
    public static final int[][] SIDES = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    /** Materials in the owner's order: any planks, then cobblestone, then dirt. */
    public static final List<String> ORDER = List.of("planks", "minecraft:cobblestone", "minecraft:dirt");
    /** Blocks the full shell takes (4 at the feet, 4 at the head, the roof's support, the roof). */
    public static final int FULL = 10;

    /**
     * The shell around feet f, in build order: the four feet-level sides, the four head-level sides, a support above the north wall, the roof last
     * (sealed from the inside). Cells that are already solid are left out. solid: the cell holds a full block now.
     */
    public static List<int[]> shell(int[] f, Predicate<int[]> solid) {
        List<int[]> out = new ArrayList<>();
        for (int dy = 0; dy <= 1; dy++)
            for (int[] d : SIDES) add(out, new int[]{f[0] + d[0], f[1] + dy, f[2] + d[1]}, solid);
        add(out, new int[]{f[0] + SIDES[0][0], f[1] + 2, f[2] + SIDES[0][1]}, solid);     // the roof's support (above the north wall)
        add(out, new int[]{f[0], f[1] + 2, f[2]}, solid);
        return out;
    }

    private static void add(List<int[]> out, int[] c, Predicate<int[]> solid) {
        if (solid == null || !solid.test(c)) out.add(c);
    }

    /** Every cell of the shell (solid or not): what must be closed for the bot to be sealed in. */
    public static List<int[]> allCells(int[] f) {
        return shell(f, null);
    }

    /** The block to build with: the first of planks (any), cobblestone, dirt the bag holds at least n of; null if none. */
    public static String material(Map<String, Integer> bag, int n) {
        for (String m : ORDER) {
            if (m.equals("planks")) {
                String best = null;
                int bestN = 0;
                for (Map.Entry<String, Integer> e : bag.entrySet())
                    if (e.getKey().endsWith("_planks") && e.getValue() > bestN) { best = e.getKey(); bestN = e.getValue(); }
                if (best != null && bestN >= n) return best;
            } else if (bag.getOrDefault(m, 0) >= n) return m;
        }
        return null;
    }

    /** What to do first when short: logs in the bag -> craft planks; else cut 8 logs then craft planks. */
    public static String prep(Map<String, Integer> bag) {
        int logs = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) {
            String p = e.getKey().substring(e.getKey().indexOf(':') + 1);
            if ((p.endsWith("_log") || p.endsWith("_stem")) && !p.startsWith("stripped_")) logs += e.getValue();
        }
        return logs >= 3 ? "craft planks " + (logs * 4) : "cut 8 logs then craft planks 32";
    }

    /** The shell blocks (planks, cobblestone, dirt, logs as 4 planks) the bag holds, for the brain's prep. */
    public static int blocks(Map<String, Integer> bag) {
        int n = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT), p = k.substring(k.indexOf(':') + 1);
            if (p.endsWith("_planks") || k.equals("minecraft:cobblestone") || k.equals("minecraft:dirt")) n += e.getValue();
            else if ((p.endsWith("_log") || p.endsWith("_stem")) && !p.startsWith("stripped_")) n += e.getValue() * 4;
        }
        return n;
    }

    public static String key(int[] c) { return c[0] + " " + c[1] + " " + c[2]; }

    /** The own-block rule: the bot may break a shell block only when it placed that block itself. */
    public static boolean mayBreak(int[] pos, Set<String> own) {
        return pos != null && own != null && own.contains(key(pos));
    }

    /** The way out: the first side whose feet and head cells are both the bot's own blocks, {feet, head, outside}; null if none. */
    public static int[][] exit(int[] f, Set<String> own) {
        for (int[] d : SIDES) {
            int[] feet = {f[0] + d[0], f[1], f[2] + d[1]}, head = {feet[0], f[1] + 1, feet[2]};
            if (mayBreak(feet, own) && mayBreak(head, own)) return new int[][]{feet, head, {f[0] + 2 * d[0], f[1], f[2] + 2 * d[1]}};
        }
        return null;
    }

    public enum Stage { BUILD, WAIT, OPEN, OUT, CLOSE, DONE }

    /**
     * One step of the stage machine. night: it is night now; sawNight: it was night at some point since the shelter
     * began; timerUp: a "shelter 5m" ran out (timed: a timer was given); leave: "shelter leave"; done: the current stage's
     * work is done (built, opened, outside, closed); keep: "shelter keep" (close the hole behind it).
     */
    public static Stage next(Stage s, boolean night, boolean sawNight, boolean timed, boolean timerUp, boolean leave, boolean done, boolean keep) {
        return switch (s) {
            case BUILD -> done ? Stage.WAIT : Stage.BUILD;
            case WAIT -> leave || (timed ? timerUp : sawNight && !night) ? Stage.OPEN : Stage.WAIT;
            case OPEN -> done ? Stage.OUT : Stage.OPEN;
            case OUT -> done ? (keep ? Stage.CLOSE : Stage.DONE) : Stage.OUT;
            case CLOSE -> done ? Stage.DONE : Stage.CLOSE;
            case DONE -> Stage.DONE;
        };
    }

    /** "shelter [5m|30s|keep|status|leave]": {minutes-in-ticks or -1, keep 0/1}; null = bad. */
    public static long[] args(String rest) {
        long ticks = -1, keep = 0;
        for (String w : (rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT)).split("\\s+")) {
            if (w.isEmpty()) continue;
            if (w.equals("keep")) keep = 1;
            else if (w.matches("^\\d{1,4}m$")) ticks = Long.parseLong(w.substring(0, w.length() - 1)) * 1200;
            else if (w.matches("^\\d{1,5}s$")) ticks = Long.parseLong(w.substring(0, w.length() - 1)) * 20;
            else return null;
        }
        return new long[]{ticks, keep};
    }
}
