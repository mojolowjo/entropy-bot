package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 0.39.z57 (path memory; owner 2026-10-25: "while doing stuff it should not forget the path that was already planned"): the
 * pure parts of {@link LivePathPrice}'s ask over the fork's path memory.
 * <ul>
 * <li>{@link #notYet}: the fork's "queued" (pathCost.maxPending searches run) is not a path answer: the path is not
 *     known yet (rule 15: COMPUTING, never NONE or UNREACHABLE).</li>
 * <li>{@link LavaKept}: the lava part of a walk (LavaNear per path cell, z40) is read once per LANDING of the fork's memory
 *     (review 9: tied to the landing, IPricedPath.landingOf): a memory answer of that same landing (exact or suffix: that
 *     path's tail) sums the kept cells' part; a new landing - a re-search, or the old one aged out - reads it again, even
 *     when its cells look the same.</li>
 * </ul>
 * Pure (JUnit: Z57PathMemoryTest). Loader notes: none.
 */
public final class PricedAnswers {
    private PricedAnswers() {}

    /**
     * The chain's answer for a fork reason that is no answer yet, else null: {@code queued} -> "queued: pathCost.maxPending N
     * searches in flight" (computing). Review 2: no search is cancelled for a move any more, so there is no "left the start".
     * 0.39.z58 item 2 (run Q 02:28-02:35): the fork's re-search wait ({@code researchWait}: the bot moved off the goal's last
     * answer, the next search waits pathCost.researchMinS) -> computing with the fork's words (rule 15: not known yet; rule
     * 18: never the old price). A queued ask now waits in the fork's priority queue, which starts it when a slot frees.
     */
    public static PathPrice.Answer notYet(String reason, String queued, String researchWait, int maxPending) {
        return notYet(reason, queued, researchWait, null, maxPending);
    }

    /**
     * As above; 0.39.z76 J3-mod item 2 (rule 15): the fork's {@code withdrawn} (IPricedPath.WITHDRAWN: the mod's ask was
     * cancelled - a way dropped, or the bot moved off its start) is no path answer either: "path not known yet", asked again
     * on the next price - never "no path", so it never drops a source.
     */
    public static PathPrice.Answer notYet(String reason, String queued, String researchWait, String withdrawn, int maxPending) {
        if (reason == null) return null;
        if (reason.equals(withdrawn)) return PathPrice.Answer.computing(withdrawn + ": asked again on the next price (rule 15: not known, never no path)");
        if (reason.equals(queued)) return PathPrice.Answer.computing("queued: pathCost.maxPending " + maxPending + " searches in flight (the fork's priority queue starts it when a slot frees)");
        if (reason.equals(researchWait)) return PathPrice.Answer.computing(researchWait + " (pathCost.researchMinS)");
        return null;
    }

    /** One cell as a number (26 bits x, 12 bits y, 26 bits z, as the game packs block positions). */
    public static long cell(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);   // structural: BlockPos packing
    }

    /** The cells of a path as numbers (x y z triples). */
    public static long[] cells(List<int[]> path) {
        long[] out = new long[path.size()];
        for (int i = 0; i < out.length; i++) out[i] = cell(path.get(i)[0], path.get(i)[1], path.get(i)[2]);
        return out;
    }

    /** Per goal key, the lava part read for the memory's landing: its cells and each cell's lava ticks. */
    public static final class LavaKept {
        private record Entry(long landing, long[] cells, double[] ticks) {}

        private final Map<String, Entry> kept = new HashMap<>();
        private final int max;

        /** {@code max}: goal keys kept (past it all are read again: the memory itself keeps the goals asked lately). */
        public LavaKept(int max) { this.max = max; }

        /**
         * The lava ticks (cells after the first: Baritone charges a move at its end) of a walk along {@code ask}, an answer of
         * the memory's landing {@code landing} for {@code key} (-1: no landing, e.g. 0 at the goal: read, not kept): summed
         * from that landing's read when {@code ask} is its tail; else {@code read} (each ask cell's ticks) is called and kept.
         */
        public double lava(String key, long landing, long[] ask, Supplier<double[]> read) {
            Entry e = kept.get(key);
            if (landing >= 0 && e != null && e.landing() == landing) {
                double t = tail(e, ask);
                if (!Double.isNaN(t)) return t;
            }
            double[] ticks = read.get();
            if (landing >= 0) {
                if (kept.size() >= max && !kept.containsKey(key)) kept.clear();
                kept.put(key, new Entry(landing, ask.clone(), ticks.clone()));
            }
            double t = 0;
            for (int i = 1; i < ticks.length; i++) t += ticks[i];
            return t;
        }

        private static double tail(Entry e, long[] ask) {
            if (ask == null || ask.length == 0 || ask.length > e.cells().length) return Double.NaN;
            int off = e.cells().length - ask.length;
            for (int i = 0; i < ask.length; i++) if (e.cells()[off + i] != ask[i]) return Double.NaN;
            double t = 0;
            for (int i = off + 1; i < e.cells().length; i++) t += e.ticks()[i];
            return t;
        }
    }
}
