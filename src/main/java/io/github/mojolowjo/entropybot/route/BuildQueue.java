package io.github.mojolowjo.entropybot.route;

/**
 * The two build queues (plan section 3). {@link Priority#NOW} boxes (the current walk needs them) always come first
 * and always run; the idle ones (near marked places, then stale, then the rest of the areas) only when the caller says
 * the bot is idle. A key is held once; offering it again at a more urgent priority moves it up, never down.
 * Thread-safe. R2's worker pool polls it; R1 implements it ({@link RouteCore#queue()}).
 */
public interface BuildQueue {
    enum Priority { NOW, PLACES, STALE, REST }

    /** Adds the key (or raises its priority). Returns false when it was already queued at this or a higher priority. */
    boolean offer(SectionKey key, Priority p);

    /** The next key: the now queue first; idle keys only when {@code idleAllowed}. Null when nothing is due. */
    SectionKey poll(boolean idleAllowed);

    int nowSize();

    int idleSize();

    /** Drops the idle queue (e.g. areas changed); the now queue stays. */
    void clearIdle();
}
