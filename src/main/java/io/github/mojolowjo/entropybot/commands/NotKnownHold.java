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
 * Pure (JUnit: Stand2Test). Loader notes: none (ticks and cells are passed in).
 */
final class NotKnownHold {
    private record Held(long at, int[] from) {}

    private final Map<String, Held> held = new HashMap<>();
    private final int max;

    NotKnownHold(int max) { this.max = max; }

    /** The fork's reason says the ask timed out (its own constant, the prefix of its words). */
    static boolean timedOut(String reason, String timedOutPrefix) {
        return reason != null && timedOutPrefix != null && reason.startsWith(timedOutPrefix);
    }

    /** {@code key} timed out at tick {@code now} with the bot at {@code from}. */
    void hold(String key, long now, int[] from) {
        if (held.size() >= max) held.clear();
        held.put(key, new Held(now, from == null ? null : from.clone()));
    }

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
        String words = words(ticks, holdS, moveM);
        if (!(estimateS >= 0) || Double.isInfinite(estimateS))
            return PathPrice.Answer.none(PathPrice.NO_ESTIMATE + " (" + words + "; Baritone's estimate not known)");
        return PathPrice.Answer.estimated(new PathPrice.Walk(estimateS), words);
    }

    boolean held(String key) { return held.containsKey(key); }

    void clear() { held.clear(); }

    static String words(long ticksAgo, double holdS, double moveM) {
        long ago = Math.round(ticksAgo / (double) GameTimes.TPS);
        long left = Math.max(0, Math.round(holdS - ago));
        return "not known (timed out " + ago + " s ago): priced by the estimate until " + left + " s from now or a move of "
                + Math.round(moveM) + " blocks (pathCost.notKnownHoldS / notKnownMoveM; ranked, not priced; rule 18 as amended)";
    }

    private static double moved(int[] a, int[] b) {
        if (a == null || b == null || a.length < 3 || b.length < 3) return 0;     // structural: x y z
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
