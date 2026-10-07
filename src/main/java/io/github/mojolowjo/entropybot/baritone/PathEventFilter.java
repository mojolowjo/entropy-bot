package io.github.mojolowjo.entropybot.baritone;

/**
 * 0.23.1: Baritone's PathingControlManager calls cancelSegmentIfSafe() on every tick no process is in control, and each
 * call queues a CANCELED path event, so an idle bot filled the event ring with 20 CANCELED a second. The repeats carry
 * nothing (the first CANCELED after a walk is what TripMeter and the jobs read): a CANCELED right after a CANCELED is
 * dropped, counted, and summed up in one line at most once a minute. Pure, one instance per hook (client thread).
 */
public final class PathEventFilter {
    public static final long SUMMARY_MS = 60_000;

    private String last;
    private long dropped, droppedTotal, lastSummaryMs = Long.MIN_VALUE / 2;

    /** What to do with an event: PASS it on, DROP it, or pass a SUMMARY line ({@link #summary}) and drop it. */
    public enum Action { PASS, DROP, SUMMARY }

    public Action offer(String event, long nowMs) {
        boolean repeat = "CANCELED".equals(event) && "CANCELED".equals(last);
        last = event;
        if (!repeat) return Action.PASS;
        dropped++;
        droppedTotal++;
        if (nowMs - lastSummaryMs >= SUMMARY_MS) {
            lastSummaryMs = nowMs;
            return Action.SUMMARY;
        }
        return Action.DROP;
    }

    /** The line for a SUMMARY: how many repeats were dropped since the last one; resets the count. */
    public String summary() {
        String s = "CANCELED x" + dropped + " while idle (Baritone's idle tick; repeats dropped, one line a minute)";
        dropped = 0;
        return s;
    }

    public long droppedTotal() { return droppedTotal; }
}
