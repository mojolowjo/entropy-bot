package io.github.mojolowjo.entropycompanion;

/**
 * How long to wait after a post. The normal interval while posts work; after {@link #FAILURES_BEFORE_BACKOFF}
 * failures in a row, every 30 seconds until one works again (a single lost packet doesn't blind the bot, which
 * ignores a position older than 10 s). Reports only the changes between "working" and "failing", so the log gets
 * one line per change, not one per post.
 */
public final class Backoff {
    public static final int FAILURES_BEFORE_BACKOFF = 3;
    public static final long FAILING_INTERVAL_MS = 30_000;

    private int failures;
    private boolean everSucceeded;
    private boolean failing;

    /** Milliseconds until the next post, given the configured interval. */
    public long delayMs(long intervalMs) {
        return failing ? FAILING_INTERVAL_MS : intervalMs;
    }

    public boolean failing() { return failing; }

    /** A post went through. True when this changes the state (the first success, or the first after failing): log it. */
    public boolean success() {
        boolean change = failing || !everSucceeded;
        failures = 0;
        failing = false;
        everSucceeded = true;
        return change;
    }

    /** A post failed. True when this tips into "failing": log it (once). */
    public boolean failure() {
        failures++;
        if (!failing && failures >= FAILURES_BEFORE_BACKOFF) {
            failing = true;
            return true;
        }
        return false;
    }
}