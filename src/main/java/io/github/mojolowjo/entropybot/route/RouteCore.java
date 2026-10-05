package io.github.mojolowjo.entropybot.route;

import java.util.List;

/** Factory for the R1 implementations behind the contract interfaces, plus the staleness helpers. */
public final class RouteCore {
    private RouteCore() {
    }

    /** A thread-safe in-memory store. */
    public static RouteStore memoryStore() {
        throw new UnsupportedOperationException("stage B");
    }

    /** The door-graph planner over {@code store}; {@code counters}/{@code log} may be null in tests. */
    public static Router router(RouteStore store, RouteCounters counters, RouteLog log) {
        throw new UnsupportedOperationException("stage B");
    }

    /** The now/idle build queue. */
    public static BuildQueue queue() {
        throw new UnsupportedOperationException("stage B");
    }

    /**
     * The boxes a block change at (x, y, z) can affect: its own box, plus the face neighbours when the block is
     * within 2 blocks of that face (moves read blocks around the cell, and crossings use a one-cell margin).
     * R2 calls it only when the block's walkability changed (passable/solid/liquid), then marks the keys stale.
     */
    public static List<SectionKey> boxesForBlockChange(int dim, int x, int y, int z) {
        throw new UnsupportedOperationException("stage B");
    }

    /**
     * A chunk (re)load re-hashed a box: when {@code walkHash} differs from the stored one, the box is marked stale
     * and true comes back (queue it). An unknown box returns true too (build it).
     */
    public static boolean rehash(RouteStore store, SectionKey key, long walkHash) {
        throw new UnsupportedOperationException("stage B");
    }

    /** The walk-grid hash of a box: which of its 4096 cells are standable. R2 uses it for the chunk-load re-hash. */
    public static long walkHash(SectionKey key, CellMoves moves) {
        throw new UnsupportedOperationException("stage B");
    }

    /**
     * The boxes on the straight stretch from a to b (sampled every block), for "a walk failed where the map said
     * possible": R3 marks them stale.
     */
    public static List<SectionKey> boxesAlong(int dim, Cell a, Cell b) {
        throw new UnsupportedOperationException("stage B");
    }
}
