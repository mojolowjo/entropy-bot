package io.github.mojolowjo.entropybot.route;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * The built boxes, in memory. Thread-safe: workers put, the game thread marks stale, the planner iterates.
 *
 * <p>A stale box keeps its crossings: planning still uses it (better than nothing), the heuristic inside it falls back
 * to the plain one, and it goes first in the idle queue. {@link #put} clears the stale mark.
 */
public interface RouteStore {
    SectionRecord get(SectionKey key);

    /** Stores a built box (replacing the old one) and clears its stale mark. */
    void put(SectionRecord rec);

    /**
     * Marks a stored box stale. For a box being built right now ({@link #beginBuild}, stored or not) it also notes the
     * change, so the build's {@link #put(SectionRecord, long)} leaves the box stale (review S3).
     */
    void markStale(SectionKey key);

    /**
     * A worker starts building {@code key}: returns the build's stamp for {@link #put(SectionRecord, long)}. Changes
     * after this ({@link #markStale}, {@link #markAllStale}) keep the stored result stale. Default: no tracking.
     */
    default long beginBuild(SectionKey key) {
        return 0;
    }

    /**
     * Stores a box built from {@link #beginBuild}'s {@code stamp}: like {@link #put(SectionRecord)}, except that a block
     * change marked during the build leaves the box stale (it was built from older terrain). Default: plain put.
     */
    default void put(SectionRecord rec, long stamp) {
        put(rec);
    }

    /** A build from {@code stamp} ended without storing (refused, failed). Default: nothing. */
    default void endBuild(SectionKey key, long stamp) {
    }

    /**
     * A block change touched {@code key}, which is not stored yet: true when a build of it is in flight (the change is
     * noted and the result will be stale; queue it again). Default: false.
     */
    default boolean noteChangeWhileBuilding(SectionKey key) {
        return false;
    }

    boolean isStale(SectionKey key);

    /** Marks every box stale (a settings hash change). */
    void markAllStale();

    /** Stale keys, a copy. */
    Collection<SectionKey> staleKeys();

    void forEach(Consumer<SectionRecord> c);

    int size();

    /** Keys of the 128x128 tiles changed since the last {@link #takeDirtyTiles}, as {@code {dim, tx, tz}}. */
    Collection<int[]> takeDirtyTiles();
}
