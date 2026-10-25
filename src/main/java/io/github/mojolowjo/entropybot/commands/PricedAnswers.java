package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;

import java.util.List;

/**
 * 0.39.z57 (path memory; owner 2026-10-25: "while doing stuff it should not forget the path that was already planned"): the
 * pure parts of {@link LivePathPrice}'s ask over the fork's path memory.
 * <ul>
 * <li>{@link #notYet}: the fork's "queued" (pathCost.maxPending searches run) and "cancelled: the bot left the search's
 *     start" are not path answers: the path is not known yet (rule 15: COMPUTING, never NONE or UNREACHABLE).</li>
 * <li>{@link Lava}: the lava part of a walk (LavaNear per path cell, z40) is read once per landed path; a memory answer
 *     (exact or suffix: the same path's tail) sums the kept cells' part instead of reading the level again for every ask
 *     (run P: 406 prices in 4 minutes).</li>
 * </ul>
 * Pure (JUnit: Z57PathMemoryTest). Loader notes: none.
 */
public final class PricedAnswers {
    private PricedAnswers() {}

    /**
     * The chain's answer for a fork reason that is no answer yet, else null: {@code queued} -> "queued: pathCost.maxPending N
     * searches in flight", {@code leftStart} -> computing (the newer ask's own search answers).
     */
    public static PathPrice.Answer notYet(String reason, String queued, String leftStart, int maxPending) {
        if (reason == null) return null;
        if (reason.equals(queued)) return PathPrice.Answer.computing("queued: pathCost.maxPending " + maxPending + " searches in flight");
        if (reason.equals(leftStart)) return PathPrice.Answer.computing("the search from a cell the bot left was cancelled; the new one runs");
        return null;
    }

    /** One cell as a number (26 bits x, 12 bits y, 26 bits z, as the game packs block positions). */
    public static long cell(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);   // structural: BlockPos packing
    }

    /** One landed path's cells and each cell's lava ticks (the start cell's part is never charged: Baritone charges a move at its end). */
    public record Lava(long[] cells, double[] ticks) {
        /** The lava ticks of a walk along {@code ask} when it is this path's tail (exact or suffix), else NaN (read it fresh). */
        public double suffix(long[] ask) {
            if (ask == null || ask.length == 0 || ask.length > cells.length) return Double.NaN;
            int off = cells.length - ask.length;
            for (int i = 0; i < ask.length; i++) if (cells[off + i] != ask[i]) return Double.NaN;
            double t = 0;
            for (int i = off + 1; i < cells.length; i++) t += ticks[i];
            return t;
        }

        /** The whole path's lava ticks (cells after the start). */
        public double total() { return suffix(cells); }
    }

    /** The cells of a path as numbers (x y z triples). */
    public static long[] cells(List<int[]> path) {
        long[] out = new long[path.size()];
        for (int i = 0; i < out.length; i++) out[i] = cell(path.get(i)[0], path.get(i)[1], path.get(i)[2]);
        return out;
    }
}
