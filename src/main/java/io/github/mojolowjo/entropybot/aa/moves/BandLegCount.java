package io.github.mojolowjo.entropybot.aa.moves;

/**
 * Job-tunnel2 (run JAT 05:14:13: "dig down to y 16 - reached y 16 (1 levels, 1 s, 1 blocks broken)" after a dig of 46
 * levels in 6 min: the re-plans restarted the band leg and its end counted only the last segment): the dig leg's own
 * counters over the whole leg. A leg restarted toward the same target Y within band.legCarryS of the last segment counts
 * on from the first start - its start Y, its start time and the blocks the earlier segments broke; anything else starts
 * fresh. Pure (JUnit: JobTunnel2Test). Loader notes: none.
 */
public final class BandLegCount {
    private BandLegCount() {}

    /** The leg: its first start Y and time, the blocks earlier segments broke, the target Y, the last segment's start time. */
    public record Leg(int fromY, long ms0, int broken, int targetY, long segMs) {}

    /**
     * The leg a segment starting now at {@code startY} toward {@code targetY} counts on: {@code prev} carried (its blocks plus
     * {@code prevSegBroken}, the last segment's own) when it was toward the same Y within {@code carryS} seconds, else fresh.
     */
    public static Leg start(Leg prev, int prevSegBroken, int startY, int targetY, long nowMs, double carryS) {
        if (prev != null && prev.targetY() == targetY && nowMs - prev.segMs() <= carryS * 1000)     // structural: s to ms
            return new Leg(prev.fromY(), prev.ms0(), prev.broken() + Math.max(0, prevSegBroken), targetY, nowMs);
        return new Leg(startY, nowMs, 0, targetY, nowMs);
    }

    /** The end words over the whole leg (BandEnd.reached): levels from its first start Y, seconds since its first start. */
    public static String reached(Leg leg, int y, long nowMs, int segBroken) {
        return BandEnd.reached(leg.fromY(), y, (nowMs - leg.ms0()) / 1000.0, leg.broken() + segBroken);     // structural: ms to s
    }
}
