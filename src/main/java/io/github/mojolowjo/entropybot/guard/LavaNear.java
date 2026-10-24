package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.List;

/**
 * Lava distance (0.39.z40, owner 2026-10-23: "turn up the lava avoidance so it stays away from lava better"): a fact and a
 * price (rule 1), never a ban on walking. The mod's one price on Baritone's cells (the CostAdvisor hook, z26's pattern):
 * <ul>
 * <li>{@link #ticks}: a move ending with the feet at a cell within Tuning {@code lava.nearBlocks} of a lava block (source or
 *     flowing; feet to head height plus the reach above and below) costs Tuning {@code lava.nearPenalty} ticks more.
 *     Baritone's cost scale: ticks, one block walked = 20 / 4.317 = 4.63 ticks (ActionCosts.WALK_ONE_BLOCK_COST), sprinted
 *     3.56. The default 10 ticks is about 2 blocks of walking per cell in reach: passing one lava block at 1 block puts some
 *     5 cells in reach (+50 ticks, 2.5 s, about 11 blocks of walk), so a detour of a few blocks wins and a lava-side goal
 *     stays reachable (dearer, never forbidden).</li>
 * <li>{@link #opensLava}: a break of a cell with lava in it or at one of its six faces opens lava onto the bot (the fork
 *     burrow's own test, BurrowProcess.lavaAt: the cell and its six neighbours); the hook refuses that break. Baritone
 *     already refuses four sides and above (MovementHelper.avoidAdjacentBreaking); this adds below.</li>
 * </ul>
 * Pure (JUnit: LavaNearTest). Loader notes: none (the hook supplies the lava test over Baritone's BlockStateInterface).
 */
public final class LavaNear {
    private LavaNear() {}

    /** Is the cell lava (source or flowing)? */
    @FunctionalInterface
    public interface Lava {
        boolean at(int x, int y, int z);
    }

    public static int reach() { return Tuning.i("lava.nearBlocks"); }

    public static double penalty() { return Tuning.get("lava.nearPenalty"); }

    /** A lava block within {@code reach} of the cell's feet or head (the box x/z +-reach, y -reach..1+reach). */
    public static boolean near(Lava l, int x, int y, int z, int reach) {
        if (l == null || reach < 0) return false;
        for (int dx = -reach; dx <= reach; dx++)
            for (int dz = -reach; dz <= reach; dz++)
                for (int dy = -reach; dy <= 1 + reach; dy++)                // structural: 1 = the head cell above the feet
                    if (l.at(x + dx, y + dy, z + dz)) return true;
        return false;
    }

    /** The extra ticks of a move ending with the feet at the cell: {@code penalty} when lava is near, else 0. */
    public static double ticks(Lava l, int x, int y, int z, int reach, double penalty) {
        return penalty > 0 && near(l, x, y, z, reach) ? penalty : 0;
    }

    /** {@link #ticks} at the Tuning values. */
    public static double ticks(Lava l, int x, int y, int z) { return ticks(l, x, y, z, reach(), penalty()); }

    /** The extra ticks along a path (its cells after the start, as Baritone charges each move at its end). */
    public static double pathTicks(Lava l, List<int[]> cells, int reach, double penalty) {
        double t = 0;
        if (cells == null) return 0;
        for (int i = 1; i < cells.size(); i++) {
            int[] c = cells.get(i);
            t += ticks(l, c[0], c[1], c[2], reach, penalty);
        }
        return t;
    }

    /** The six faces. */
    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Breaking the cell opens lava: lava in it or at one of its six faces (BurrowProcess.lavaAt's rule). */
    public static boolean opensLava(Lava l, int x, int y, int z) {
        if (l == null) return false;
        if (l.at(x, y, z)) return true;
        for (int[] d : FACES) if (l.at(x + d[0], y + d[1], z + d[2])) return true;
        return false;
    }

    /** aa why / aa debug: " (+3.0 s lava detour)" for a walk whose path paid lava ticks, else "". */
    public static String note(double lavaTicks, double ticksPerSecond) {
        if (!(lavaTicks > 0) || ticksPerSecond <= 0) return "";
        return " (+" + String.format(java.util.Locale.ROOT, "%.1f", lavaTicks / ticksPerSecond) + " s lava detour)";
    }
}
