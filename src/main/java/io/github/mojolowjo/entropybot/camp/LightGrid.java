package io.github.mojolowjo.entropybot.camp;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C4: {@code light here <r>} / {@code light x1 z1 x2 z2}: torches on a grid every {@link #STEP} blocks. Pure (the
 * grid, the argument parsing and the end line); the game side (CampCommands) finds the ground of each spot, leaves out
 * water, air, spots the policy refuses (keep-out / no-break) and spots that already have light, and places each torch with its own place
 * lease (protect boxes are refused there by the guard).
 *
 * <p>Loader notes: none.
 */
public final class LightGrid {
    private LightGrid() {}

    public static int step() { return Tuning.i("lightGrid.step"); }
    public static int maxR() { return Tuning.i("lightGrid.maxR"); }
    public static int maxSpots() { return Tuning.i("lightGrid.maxSpots"); }
    public static final String USAGE = "usage: light here <r> | light x1 z1 x2 z2 (torches every " + step() + " blocks)";

    /** x1 z1 x2 z2 (sorted), or null for a bad line. here: the bot's x z. */
    public static int[] parse(String rest, int[] here) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        Matcher m = Pattern.compile("^here(?:\\s+(\\d+))?$").matcher(t);
        if (m.find()) {
            int r = m.group(1) == null ? 8 : Math.min(Integer.parseInt(m.group(1)), maxR());
            return new int[]{here[0] - r, here[1] - r, here[0] + r, here[1] + r};
        }
        m = Pattern.compile("^(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)$").matcher(t);
        if (!m.find()) return null;
        int x1 = Integer.parseInt(m.group(1)), z1 = Integer.parseInt(m.group(2)), x2 = Integer.parseInt(m.group(3)), z2 = Integer.parseInt(m.group(4));
        if (Math.abs(x2 - x1) > 2 * maxR() || Math.abs(z2 - z1) > 2 * maxR()) return null;
        return new int[]{Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)};
    }

    /** The grid's spots {x, z}: every STEP blocks from the box's corner, centred so the edges get the same margin. */
    public static List<int[]> spots(int x1, int z1, int x2, int z2) {
        List<int[]> out = new ArrayList<>();
        int ox = x1 + ((x2 - x1) % step()) / 2, oz = z1 + ((z2 - z1) % step()) / 2;
        for (int x = ox; x <= x2; x += step()) for (int z = oz; z <= z2; z += step()) if (out.size() < maxSpots()) out.add(new int[]{x, z});
        return out;
    }

    // ---- 0.23.1: where the torches come from ----

    public enum Source { ENOUGH, FETCH, CRAFT, PARTIAL, NONE }

    /**
     * How light gets its torches. torches: what I carry; need: the spots; stored: torches in the chests/RS; fuel: coal and
     * charcoal (bag + storage); logs: logs (bag + storage); furnaceNear: a furnace within reach (charcoal from logs).
     * count: the torches to fetch or craft (CRAFT: at most 4 a coal or charcoal; from logs 4 a log), or for PARTIAL/NONE 0.
     */
    public record Supply(Source source, int count, String text) {}

    public static final String NEXT = "give me coal or charcoal (or logs, with a furnace near), or torches";

    public static Supply supply(int torches, int need, int stored, int fuel, int logs, boolean furnaceNear) {
        if (torches >= need) return new Supply(Source.ENOUGH, 0, null);
        int missing = need - torches;
        if (stored >= missing) return new Supply(Source.FETCH, missing, "get torch " + missing);
        // coal or charcoal first, and then only what it makes (no furnace trip that a busy furnace can sink, live 0.23.1);
        // logs (charcoal at a furnace) only when there is no coal at all
        int canMake = fuel > 0 ? fuel * 4 : furnaceNear ? logs * 4 : 0;
        if (canMake > 0) {
            int n = Math.min(missing, canMake);
            return new Supply(Source.CRAFT, n, "craft torch " + n + (fuel == 0 ? " (charcoal from my logs first)" : ""));
        }
        String why = logs > 0 ? "I have logs but no furnace near for charcoal" : "no coal, charcoal or logs";
        if (torches > 0) return new Supply(Source.PARTIAL, 0, "only " + torches + " torch" + (torches == 1 ? "" : "es") + " and nothing to make more from (" + why + ") - next: " + NEXT);
        return new Supply(Source.NONE, 0, "error: no torches and nothing to make them from (" + why + ") - next: " + NEXT);
    }

    /** The end line: "placed 14 torches, 3 spots skipped (water/air)". */
    public static String report(int placed, int skipped, String why) {
        return "placed " + placed + " torch" + (placed == 1 ? "" : "es") + (skipped > 0 ? ", " + skipped + " spot" + (skipped == 1 ? "" : "s") + " skipped (" + why + ")" : "");
    }
}
