package io.github.mojolowjo.entropybot.engine;

import java.util.ArrayDeque;

/** When {@link Reconnect} tries again: after 1, 5 and 15 minutes out of a world, at most 3 times in any hour. Pure. */
public final class ReconnectRules {
    private ReconnectRules() {}

    public static final long[] WAITS = { 1200, 6000, 18000 };
    public static final int PER_HOUR = 3;

    /** The wait for this try is over and fewer than PER_HOUR tries happened in the last hour (old ones are dropped). */
    public static boolean due(long waited, int tries, ArrayDeque<Long> recent, long nowMs) {
        if (tries >= WAITS.length || waited < WAITS[tries]) return false;
        while (!recent.isEmpty() && nowMs - recent.peekFirst() > 3_600_000L) recent.pollFirst();
        return recent.size() < PER_HOUR;
    }
}
