package io.github.mojolowjo.entropybot.route;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Factory for the R1 implementations behind the contract interfaces, plus the staleness helpers. */
public final class RouteCore {
    private RouteCore() {
    }

    /** A thread-safe in-memory store. */
    public static RouteStore memoryStore() {
        return new MemoryRouteStore();
    }

    /** The door-graph planner over {@code store}; {@code counters}/{@code log} may be null in tests. */
    public static Router router(RouteStore store, RouteCounters counters, RouteLog log) {
        return new DoorGraphRouter(store, counters, log);
    }

    /** The now/idle build queue. */
    public static BuildQueue queue() {
        return new PriorityBuildQueue();
    }

    /**
     * The boxes a block change at (x, y, z) can affect: its own box, plus the face neighbours when the block is
     * within 2 blocks of that face (moves read blocks around the cell, and crossings use a one-cell margin).
     * R2 calls it only when the block's walkability changed (passable/solid/liquid), then marks the keys stale.
     */
    public static List<SectionKey> boxesForBlockChange(int dim, int x, int y, int z) {
        SectionKey k = SectionKey.of(dim, x, y, z);
        List<SectionKey> out = new ArrayList<>(4);
        out.add(k);
        int lx = x & 15, ly = y & 15, lz = z & 15;
        if (lx <= 1) out.add(k.neighbour(0));
        if (lx >= 14) out.add(k.neighbour(1));
        if (ly <= 1) out.add(k.neighbour(2));
        if (ly >= 14) out.add(k.neighbour(3));
        if (lz <= 1) out.add(k.neighbour(4));
        if (lz >= 14) out.add(k.neighbour(5));
        return out;
    }

    /**
     * A chunk (re)load re-hashed a box: when {@code walkHash} differs from the stored one, the box is marked stale
     * and true comes back (queue it). An unknown box returns true too (build it).
     */
    public static boolean rehash(RouteStore store, SectionKey key, long walkHash) {
        SectionRecord r = store.get(key);
        if (r == null) return true;
        if (r.walkHash() == walkHash) return false;
        store.markStale(key);
        return true;
    }

    /** The walk-grid hash of a box: which of its 4096 cells are standable. R2 uses it for the chunk-load re-hash. */
    public static long walkHash(SectionKey key, CellMoves moves) {
        // only the standable test is needed; a grid without moves would be cheaper, but this keeps one definition
        CellMoves standOnly = new CellMoves() {
            @Override
            public boolean standable(int x, int y, int z) {
                return moves.standable(x, y, z);
            }

            @Override
            public void forEachMove(int x, int y, int z, MoveSink s) {
            }
        };
        return new BoxGrid(key, standOnly).walkHash();
    }

    /**
     * The boxes on the straight stretch from a to b (sampled every block), for "a walk failed where the map said
     * possible": R3 marks them stale.
     */
    public static List<SectionKey> boxesAlong(int dim, Cell a, Cell b) {
        Set<SectionKey> out = new LinkedHashSet<>();
        int steps = (int) Math.ceil(a.dist(b));
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            int x = (int) Math.floor(a.x() + (b.x() - a.x()) * t + 0.5);
            int y = (int) Math.floor(a.y() + (b.y() - a.y()) * t + 0.5);
            int z = (int) Math.floor(a.z() + (b.z() - a.z()) * t + 0.5);
            out.add(SectionKey.of(dim, x, y, z));
        }
        return new ArrayList<>(out);
    }
}
