package io.github.mojolowjo.entropybot.route;

/**
 * Review R10: the counters {@code route status} and {@code check} show. A snapshot of {@link RouteCounters}.
 *
 * @param boxesBuilt       boxes built and stored since start.
 * @param boxesPerSecond   over the time since the previous snapshot.
 * @param nowQueue         boxes waiting on the now queue (the current walk needs them).
 * @param idleQueue        boxes waiting on the idle queue.
 * @param workerExceptions exceptions thrown while building or planning (first few logged in full).
 * @param planningTimeouts walks that waited the full 300 ms for a plan and started plain.
 * @param fallbacksNoPath  RouteGoal walks that got "no path" and swapped to the plain goal.
 * @param fallbacksStuck   RouteGoal walks the stuck watchdog swapped to the plain goal.
 * @param refusedBreaking  boxes built with allowBreak on and not stored (review R5).
 * @param filesIgnored     route files skipped as truncated, damaged or of another format version.
 * @param plans            plans made.
 * @param lastError        the last error line, or "" (never null).
 */
public record RouteStats(long boxesBuilt, double boxesPerSecond, int nowQueue, int idleQueue,
                         long workerExceptions, long planningTimeouts, long fallbacksNoPath, long fallbacksStuck,
                         long refusedBreaking, long filesIgnored, long plans, String lastError) {

    /** One line for {@code route status}. */
    public String line() {
        return String.format(java.util.Locale.ROOT,
                "boxes %d (%.1f/s), queues now %d idle %d, plans %d, errors %d, timeouts %d, fallbacks nopath %d stuck %d,"
                        + " refused (breaking) %d, files ignored %d%s",
                boxesBuilt, boxesPerSecond, nowQueue, idleQueue, plans, workerExceptions, planningTimeouts,
                fallbacksNoPath, fallbacksStuck, refusedBreaking, filesIgnored,
                lastError.isEmpty() ? "" : ", last error: " + lastError);
    }
}
