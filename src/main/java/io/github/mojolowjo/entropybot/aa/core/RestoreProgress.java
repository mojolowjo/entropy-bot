package io.github.mojolowjo.entropybot.aa.core;

/**
 * AA_ROUND3 1 finding 4 (aa388 19:21: "restore stopped: aa stuck: needs.iron made no progress for 40s (no move, no bag
 * change)" with 8 blocks left): putting back blocks is stationary work, so the restore's work count (placed + tries)
 * feeds {@link JobPort#progress}: every change of it moves the task's counter, and the brain's watchdog stays quiet
 * while the restore works. Pure (JUnit RestoreProgressTest). Loader notes: none.
 */
public final class RestoreProgress {
    private RestoreProgress() {}

    /**
     * @param progress the task's activity counter
     * @param last     the restore's work count at the last tick (-1: no restore ran)
     * @param now      the work count now (-1: no restore runs)
     * @return the counter, one up when a running restore placed or tried a block since the last tick
     */
    public static long tick(long progress, int last, int now) {
        return now >= 0 && last >= 0 && now != last ? progress + 1 : progress;
    }
}
