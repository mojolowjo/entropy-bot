package io.github.mojolowjo.entropybot.routing;

/** At most {@code n} events per {@code windowMs} (a sliding window over the last n). Game thread only. */
public final class RateGate {
    private final long[] times;
    private final long windowMs;
    private int next;

    public RateGate(int n, long windowMs) {
        this.times = new long[n];
        this.windowMs = windowMs;
        java.util.Arrays.fill(times, Long.MIN_VALUE / 2);
    }

    /** True when one more event fits now (does not take it). */
    public boolean available(long nowMs) {
        return nowMs - times[next] >= windowMs;
    }

    /** Takes one if it fits. */
    public boolean take(long nowMs) {
        if (!available(nowMs)) return false;
        times[next] = nowMs;
        next = (next + 1) % times.length;
        return true;
    }
}
