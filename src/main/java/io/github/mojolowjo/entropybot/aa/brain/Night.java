package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

/**
 * BRAIN_PLAN 2.10 (B3): day and night. Minecraft time: sunset at 12000, beds work from 12542 to 23459, so a night
 * desire rises from {@link #DUSK} to 12542 and holds until morning. Night desires and the night shift are off in
 * Peaceful (no monsters; the adapter's {@code hungerDrains} is false there). Pure.
 */
public final class Night {
    private Night() {}

    public static final long DUSK = 11_500, BED_FROM = 12_542, BED_TO = 23_460;

    /** 0 by day, rising 0..1 at dusk, 1 at night; unknown time ({@code dayTime < 0}): the night flag. */
    public static double dusk(long dayTime, boolean nightFlag) {
        if (dayTime < 0) return nightFlag ? 1 : 0;
        long t = dayTime % 24_000;
        if (t >= BED_FROM && t < BED_TO) return 1;
        if (t >= DUSK && t < BED_FROM) return (t - DUSK) / (double) (BED_FROM - DUSK);
        return 0;
    }

    /** Monsters spawn now: night (or dusk) and not Peaceful. */
    public static boolean hostile(Blackboard bb) {
        return bb.in.hungerDrains && dusk(bb.in.dayTime, bb.in.night) >= 1;
    }
}
