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

    void markStale(SectionKey key);

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
