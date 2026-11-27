package io.github.mojolowjo.entropybot.ghost;

/**
 * Dry-run package 1: what the ghost costs. Collects the time of each ghost tick (nanoseconds) and answers the count, the
 * average and the worst, in milliseconds. The plan's first pass marks (m0j, 2026-11-26): 2 ms per game tick on average,
 * never more than 10 ms in one tick; this class only measures, the marks are read where the verdict is made.
 *
 * <p>Pure (JUnit: TickTimesTest). Loader notes: none ({@code System.nanoTime} by the caller).
 */
public final class TickTimes {
    private long count;
    private long totalNs;
    private long worstNs;

    public void add(long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("a tick cannot take " + nanos + " ns");
        count++;
        totalNs += nanos;
        worstNs = Math.max(worstNs, nanos);
    }

    public long count() { return count; }

    /** The average in ms, NaN with no tick yet (not loaded, never 0). */
    public double avgMs() { return count == 0 ? Double.NaN : totalNs / (double) count / 1e6; }

    /** The worst in ms, NaN with no tick yet. */
    public double worstMs() { return count == 0 ? Double.NaN : worstNs / 1e6; }

    public double totalMs() { return totalNs / 1e6; }

    @Override
    public String toString() {
        return count == 0 ? "no ticks timed" : String.format(java.util.Locale.ROOT, "%d ticks, avg %.3f ms, worst %.3f ms", count, avgMs(), worstMs());
    }
}
