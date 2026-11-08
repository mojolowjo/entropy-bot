package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.brain.GameTimes;

import java.util.HashMap;
import java.util.Map;

/**
 * job-stand2 (run JAS 04:38-04:52: 371 "still standing" lines, 6 stands of 30 s; rule 18 as amended, rule 22): a price ask
 * the fork answered "not known: the search ran out of its N ms" (IPricedPath.TIMED_OUT) is a fact. The walk is then taken by
 * Baritone's estimate at once (ESTIMATED, ranked not priced) and not asked again until {@code pathCost.notKnownHoldS} has passed
 * or the bot has moved {@code pathCost.notKnownMoveM} blocks from where it timed out: no ask, time out, re-price, ask loop.
 * <p>jbf-3 (run JBF-T2 10:41-10:50: 133 re-plans flipping "dig down until stone is seen" and "stand while paths compute",
 * every walk "COMPUTING (re-search waiting (the bot moved off the last path))" and never landing): a walk answered the
 * re-search wait {@code pathCost.researchHoldAsks} times in a row ({@link #researchAgain}) is held the same way, with its
 * own words - the plan takes its estimate and stands no more on it. Any other answer for that walk ends the count.
 * Pure (JUnit: Stand2Test). Loader notes: none (ticks and cells are passed in).
 */
final class NotKnownHold {
    private record Held(long at, int[] from, String why) {}

    /** The words of the timed-out hold (job-stand2). */
    static final String TIMED_OUT = "timed out";
    /** jbf-3: the words of the re-search hold. */
    static final String RESEARCH = "re-search waiting again and again";

    private final Map<String, Held> held = new HashMap<>();
    private final Map<String, Integer> research = new HashMap<>();
    private final int max;

    NotKnownHold(int max) { this.max = max; }

    /** The fork's reason says the ask timed out (its own constant, the prefix of its words). */
    static boolean timedOut(String reason, String timedOutPrefix) {
        return reason != null && timedOutPrefix != null && reason.startsWith(timedOutPrefix);
    }

    /** {@code key} timed out at tick {@code now} with the bot at {@code from}. */
    void hold(String key, long now, int[] from) { hold(key, now, from, TIMED_OUT); }

    /** jbf-3: {@code key} is held from tick {@code now} for {@code why} (the words of the hold's answers). */
    void hold(String key, long now, int[] from, String why) {
        if (held.size() >= max) held.clear();
        held.put(key, new Held(now, from == null ? null : from.clone(), why == null ? TIMED_OUT : why));
        research.remove(key);
    }

    /**
     * jbf-3: {@code key} was answered the re-search wait once more; true when it has been {@code asks} times in a row (the
     * caller holds it). {@code asks} 0 or less: never.
     */
    boolean researchAgain(String key, int asks) {
        if (asks <= 0) return false;
        if (research.size() >= max) research.clear();
        int n = research.merge(key, 1, Integer::sum);
        return n >= asks;
    }

    /** jbf-3: {@code key} got another answer than the re-search wait: its count starts over. */
    void researchEnded(String key) { research.remove(key); }

    /**
     * The estimate answer while {@code key} is held (null: not held, or the hold ended - then it is dropped and asked again).
     * {@code estimateS}: Baritone's estimate from the feet (NaN: none - the way drops, rule 15).
     */
    PathPrice.Answer answer(String key, long now, int[] feet, double holdS, double moveM, double estimateS) {
        Held h = held.get(key);
        if (h == null) return null;
        long ticks = now - h.at();
        if (ticks >= (long) Math.ceil(holdS * GameTimes.TPS) || moved(h.from(), feet) >= moveM) {
            held.remove(key);
            return null;
        }
        String words = words(ticks, holdS, moveM, h.why());
        if (!(estimateS >= 0) || Double.isInfinite(estimateS))
            return PathPrice.Answer.none(PathPrice.NO_ESTIMATE + " (" + words + "; Baritone's estimate not known)");
        return PathPrice.Answer.estimated(new PathPrice.Walk(estimateS), words);
    }

    boolean held(String key) { return held.containsKey(key); }

    void clear() { held.clear(); research.clear(); }

    static String words(long ticksAgo, double holdS, double moveM) { return words(ticksAgo, holdS, moveM, TIMED_OUT); }

    /** The hold's words: "not known (<why> N s ago): priced by the estimate until ...". */
    static String words(long ticksAgo, double holdS, double moveM, String why) {
        long ago = Math.round(ticksAgo / (double) GameTimes.TPS);
        long left = Math.max(0, Math.round(holdS - ago));
        return "not known (" + (why == null ? TIMED_OUT : why) + " " + ago + " s ago): priced by the estimate until " + left + " s from now or a move of "
                + Math.round(moveM) + " blocks (pathCost.notKnownHoldS / notKnownMoveM; ranked, not priced; rule 18 as amended)";
    }

    private static double moved(int[] a, int[] b) {
        if (a == null || b == null || a.length < 3 || b.length < 3) return 0;     // structural: x y z
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
