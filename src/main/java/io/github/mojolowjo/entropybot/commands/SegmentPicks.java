package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.HashMap;
import java.util.Map;

/** job-segpick (run JAR 04:09:41): the stand's segment pick, pure (LivePathPrice reads the game; this class does not). */
public final class SegmentPicks {
    private SegmentPicks() {}

    /** Of the keys, the one cheapest by {@code wayS} (NaN: not known, ranked last, still a candidate); null for none. */
    public static String cheapest(java.util.Collection<String> keys, java.util.function.ToDoubleFunction<String> wayS) {
        String best = null;
        double bestS = Double.POSITIVE_INFINITY;
        for (String k : keys) {
            double s = wayS.applyAsDouble(k);
            if (Double.isNaN(s)) s = Double.MAX_VALUE;
            if (best == null || s < bestS) { best = k; bestS = s; }
        }
        return best;
    }

    /**
     * The mod's estimate of an ESTIMATED way, as the chain prices it (NeedChain, job-est): Baritone's estimate plus a stone
     * break per level under the feet past estimate.freeDropLevels (NeedChain.wayDownS). NaN when the estimate is not known.
     */
    public static double wayS(double baritoneS, int feetY, int spotY, String pickItem, int pickTier) {
        if (Double.isNaN(baritoneS)) return Double.NaN;
        return baritoneS + io.github.mojolowjo.entropybot.aa.NeedChain.wayDownS(feetY, spotY, pickItem, pickTier);
    }

    /** {@code pathCost.firstSegmentMaxS} in Baritone ticks; a segment past it is not walked. */
    public static boolean segmentTooLong(double ticks) {
        return ticks > Tuning.get("pathCost.firstSegmentMaxS") * GameTimes.TPS;
    }

    /**
     * Run JAR 04:09:42 (the segment's price ask timed out and the walk was abandoned): ways whose segment walk ended on a
     * no-path landing are not walked as a segment again for {@code pathCost.segmentRetryS} (game ticks).
     */
    public static final class SegmentHold {
        private final Map<String, Long> until = new HashMap<>();

        public void hold(String key, long now) {
            until.put(key, now + (long) Math.ceil(Tuning.get("pathCost.segmentRetryS") * GameTimes.TPS));
        }

        public boolean held(String key, long now) {
            until.values().removeIf(t -> t <= now);
            return until.containsKey(key);
        }
    }
}
