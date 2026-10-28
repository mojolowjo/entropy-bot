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

    /**
     * 0.39.z77 noprogress (run DH 04:39:57: "walk to -22 16 1: UNREACHABLE (no path: Baritone's search made no progress)" ended
     * the pickaxe's order and, a second later, the four armour orders: "no way to get it: playbook: descent to y 16:
     * unreachable"): the chain's answer for a fork no-path reason. Only a search that ran to its end is a no-way:
     * {@code unreachable} is the fork's EXHAUSTED stop (every move from the start tried, the moves the rules refuse among them).
     * The fork's {@code noProgress} is a segment search stopped by its time or node budget with the start still its best node
     * (IPricedPath.Step.of: any stop but EXHAUSTED) - not the search's end, so not known (rule 15), never unreachable:
     * COMPUTING with {@link #NO_PROGRESS_WAIT}, which keeps the first-answer clock ({@link #waits}). The fork keeps that answer
     * for its own start cell only, so the walk is searched again from the next cell the bot stands on; from the same cell,
     * past pathCost.firstAnswerWaitS it is taken by Baritone's estimate ({@link #afterWait}) and walked on Baritone's fresh
     * search (the price's DigDownGoal steering for a descent). {@code chunks}: chunks not loaded. Anything else: no answer,
     * with the fork's reason. 0.39.z78 J6b (job-vein a2d4e1c8): decided from the flags of the fork's last calc of the search
     * ({@code exhausted} / {@code budget}, IPricedPath.SearchCalc; null: no calc kept - not known), never from the reason's
     * words. Pure.
     */
    public static PathPrice.Answer noPath(String reason, Boolean exhausted, Boolean budget, String chunks) {
        // 0.39.z78 J6b (job-vein a2d4e1c8, the manager's ER ruling, rule 15): "no path" ONLY from the fork's flag - the
        // graph exhausted (IPricedPath.SearchCalc#exhausted); a budget stop is not known; never the reason's words
        if (Boolean.TRUE.equals(exhausted)) return PathPrice.Answer.unreachable("no path: the complete search found none");   // the graph exhausted
        if (Boolean.TRUE.equals(budget)) return PathPrice.Answer.computing(NO_PROGRESS_WAIT);
        if (chunks.equals(reason)) return PathPrice.Answer.chunks(chunks);
        if (exhausted == null) return PathPrice.Answer.computing(NO_STOP_KEPT + " (" + reason + ")");
        return PathPrice.Answer.none(reason);
    }

    /** J6b: the search's stop is not known (the fork kept no last calc for the goal) - not known, never "no path" (rule 15). */
    public static final String NO_STOP_KEPT = "not known: the fork kept no stop for this search (rule 15: never 'no path' without the graph exhausted)";

    /** 0.39.z77 noprogress: the words of a search that stopped on its budget with no step past its start (not known). */
    public static final String NO_PROGRESS_WAIT = "no progress: Baritone's search stopped on its budget with no step past the start - not the search's end"
            + " (rule 15: not known, never unreachable); searched again from the next cell, or Baritone's estimate after the first-answer wait";

    /**
     * 0.39.z76 send-back 3 (rule 18 amended, rule 22; run AU 01:57:53-02:02:53: walks "COMPUTING (queued: 4 in flight)" for
     * 5 min): a walk still not known ({@code a} COMPUTING) after {@code waitS} (pathCost.firstAnswerWaitS) of asks from the
     * same start is taken by Baritone's estimate ({@code estimateS}, its goal heuristic, no search) - ESTIMATED, "ranked, not
     * priced"; the walk computes its full path on the way. Before the wait, or any other answer: {@code a} as it is. Past the
     * wait with no usable estimate (NaN, infinite): NONE {@link PathPrice#NO_ESTIMATE} - the way drops (rule 15: no estimate
     * is never a price, and never another stand).
     */
    public static PathPrice.Answer afterWait(PathPrice.Answer a, long waitedTicks, int waitS, double estimateS) {
        if (!waits(a)) return a;
        if (waitS <= 0 || waitedTicks < (long) waitS * io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS) return a;
        // z76 review (rule 15): past the wait with no usable estimate the way drops (never another stand: no NOT_KNOWN prefix)
        if (!(estimateS >= 0) || Double.isInfinite(estimateS))
            return PathPrice.Answer.none(PathPrice.NO_ESTIMATE + " (" + waitS + " s from here, " + (a.reason() == null ? "computing" : a.reason()) + "; Baritone's estimate not known: an older fork, no player, or none for this goal)");
        return PathPrice.Answer.estimated(new PathPrice.Walk(estimateS), "not known after " + waitS + " s from here (" + (a.reason() == null ? "computing" : a.reason())
                + "): Baritone's estimate, ranked, not priced");
    }

    /**
     * 0.39.z77 round fix 1 (run BH 09:31:00-09:34:12, S3: "walk to in:-235 15 -201: CHUNKS (chunks not loaded)" and "-249 56
     * -213: CHUNKS" answered again and again from one cell for 3 min, the fork idle - "0 first answers pending" - so every
     * order stood 30 s, was priced again on the same two answers and stood again): an answer that is not a path yet keeps the
     * first-answer clock - COMPUTING, and CHUNKS (rule 18: a path into chunks Baritone has not loaded is "path not known", the
     * same wait). Was COMPUTING only: a CHUNKS answer reset the clock, the next ask (COMPUTING, after the answer aged out)
     * started it again, so no estimate ever came and no way ever dropped. Pure.
     */
    public static boolean waits(PathPrice.Answer a) {
        return a != null && (a.kind() == PathPrice.Kind.COMPUTING || a.kind() == PathPrice.Kind.CHUNKS);
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
