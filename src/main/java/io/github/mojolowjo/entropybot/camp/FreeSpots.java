package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 0.24.3: free spots for the camp's table, furnace and chest. A free spot is a feet-level cell within reach (rings 1 to 3
 * around the bot, never its own cell) that can take a block (air or a plant, solid ground under it) with air above it
 * (so the bot can still reach past it). Ring 2 behind the quarry first (the old order), then ring 1 (a 3x3 pit has only
 * ring 1), then ring 3; the quarry's side last, never left out. clearable: a cell holding leaves or a plant that
 * clearing would free (used when nothing is free). Pure; loader notes: none.
 */
public final class FreeSpots {
    private FreeSpots() {}

    /** placeable: the cell can take a block now; airAbove: the cell above it is free. */
    public static List<int[]> find(int[] f, int[] dir, Predicate<int[]> placeable, Predicate<int[]> airAbove) {
        List<int[]> out = new ArrayList<>();
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int[] c = {f[0] + dx, f[1], f[2] + dz};
                if (placeable.test(c) && airAbove.test(c)) out.add(c);
            }
        }
        out.sort(Comparator.comparingInt((int[] c) -> quarrySide(f, dir, c) ? 1 : 0)
                .thenComparingInt(c -> rank(Math.max(Math.abs(c[0] - f[0]), Math.abs(c[2] - f[2]))))
                .thenComparingInt(c -> (c[0] - f[0]) * dir[0] + (c[2] - f[2]) * dir[1]));
        return out;
    }

    static boolean quarrySide(int[] f, int[] dir, int[] c) {
        return (c[0] - f[0]) * dir[0] + (c[2] - f[2]) * dir[1] > 0;
    }

    private static int rank(int ring) {
        return ring == 2 ? 0 : ring == 1 ? 1 : 2;
    }

    /** The cells to clear (leaves, plants) to make n free spots when find() gave fewer; ring 1 first. */
    public static List<int[]> toClear(int[] f, Predicate<int[]> clearable, int n) {
        List<int[]> out = new ArrayList<>();
        for (int r = 1; r <= 2 && out.size() < n; r++)
            for (int dx = -r; dx <= r && out.size() < n; dx++) for (int dz = -r; dz <= r && out.size() < n; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int[] c = {f[0] + dx, f[1], f[2] + dz};
                if (clearable.test(c)) out.add(c);
            }
        return out;
    }
}
