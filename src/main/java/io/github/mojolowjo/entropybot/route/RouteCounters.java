package io.github.mojolowjo.entropybot.route;

import java.util.concurrent.atomic.AtomicLong;

/** The live counters behind {@link RouteStats}. Thread-safe; R2 and R3 bump the ones they own. */
public final class RouteCounters {
    private final AtomicLong built = new AtomicLong();
    private final AtomicLong exceptions = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong noPath = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong filesIgnored = new AtomicLong();
    private final AtomicLong plans = new AtomicLong();
    private volatile String lastError = "";
    private long lastSnapMs = -1, lastSnapBuilt;
    private double lastRate;

    public void boxBuilt() { built.incrementAndGet(); }
    public void planMade() { plans.incrementAndGet(); }
    public void planningTimeout() { timeouts.incrementAndGet(); }
    public void fallbackNoPath() { noPath.incrementAndGet(); }
    public void fallbackStuck() { stuck.incrementAndGet(); }
    public void refusedBreaking() { refused.incrementAndGet(); }

    /** A worker (or planner) exception: counted, remembered, and logged through {@code log} (rate-limited there). */
    public void workerException(String where, Throwable t, RouteLog log) {
        exceptions.incrementAndGet();
        lastError = where + ": " + t;
        if (log != null) log.error(where, t);
    }

    public void fileIgnored(String why, RouteLog log) {
        filesIgnored.incrementAndGet();
        lastError = why;
        if (log != null) log.warn(why);
    }

    /** A snapshot; the rate covers the time since the previous snapshot (0 on the first). */
    public synchronized RouteStats snapshot(long nowMs, int nowQueue, int idleQueue) {
        long b = built.get();
        if (lastSnapMs >= 0 && nowMs - lastSnapMs >= 1000) {
            lastRate = (b - lastSnapBuilt) * 1000.0 / (nowMs - lastSnapMs);
            lastSnapMs = nowMs;
            lastSnapBuilt = b;
        } else if (lastSnapMs < 0) {
            lastSnapMs = nowMs;
            lastSnapBuilt = b;
        }
        return new RouteStats(b, lastRate, nowQueue, idleQueue, exceptions.get(), timeouts.get(), noPath.get(),
                stuck.get(), refused.get(), filesIgnored.get(), plans.get(), lastError);
    }
}
