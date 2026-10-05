package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.BuildQueue;
import io.github.mojolowjo.entropybot.route.CellMoves;
import io.github.mojolowjo.entropybot.route.MoveSink;
import io.github.mojolowjo.entropybot.route.RouteStore;
import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Fakes for the R1 contract (R1's real classes are stubs until it lands). */
final class RouteFakes {
    private RouteFakes() {
    }

    static SectionRecord record(SectionKey k, SectionRecord.Quality q) {
        return new SectionRecord(k, q, 0, 0, List.of(), new char[0]);
    }

    /** A queue: now first, then PLACES, STALE, REST; raising moves up, never down. */
    static final class Queue implements BuildQueue {
        final Map<Priority, LinkedHashSet<SectionKey>> lists = new EnumMap<>(Priority.class);
        final List<String> offers = new ArrayList<>();

        Queue() {
            for (Priority p : Priority.values()) lists.put(p, new LinkedHashSet<>());
        }

        synchronized Priority where(SectionKey k) {
            for (Priority p : Priority.values()) if (lists.get(p).contains(k)) return p;
            return null;
        }

        @Override
        public synchronized boolean offer(SectionKey key, Priority p) {
            offers.add(key + ":" + p);
            Priority cur = where(key);
            if (cur != null && cur.ordinal() <= p.ordinal()) return false;
            if (cur != null) lists.get(cur).remove(key);
            lists.get(p).add(key);
            return true;
        }

        @Override
        public synchronized SectionKey poll(boolean idleAllowed) {
            for (Priority p : Priority.values()) {
                if (p != Priority.NOW && !idleAllowed) break;
                var it = lists.get(p).iterator();
                if (it.hasNext()) {
                    SectionKey k = it.next();
                    it.remove();
                    return k;
                }
            }
            return null;
        }

        @Override
        public synchronized int nowSize() {
            return lists.get(Priority.NOW).size();
        }

        @Override
        public synchronized int idleSize() {
            return lists.get(Priority.PLACES).size() + lists.get(Priority.STALE).size() + lists.get(Priority.REST).size();
        }

        @Override
        public synchronized void clearIdle() {
            lists.get(Priority.PLACES).clear();
            lists.get(Priority.STALE).clear();
            lists.get(Priority.REST).clear();
        }
    }

    static final class Store implements RouteStore {
        final Map<SectionKey, SectionRecord> recs = new ConcurrentHashMap<>();
        final Set<SectionKey> stale = ConcurrentHashMap.newKeySet();
        final Set<List<Integer>> dirty = ConcurrentHashMap.newKeySet();

        @Override
        public SectionRecord get(SectionKey key) {
            return recs.get(key);
        }

        @Override
        public void put(SectionRecord rec) {
            recs.put(rec.key(), rec);
            stale.remove(rec.key());
            dirty.add(List.of(rec.key().dim(), rec.key().tileX(), rec.key().tileZ()));
        }

        @Override
        public void markStale(SectionKey key) {
            stale.add(key);
            dirty.add(List.of(key.dim(), key.tileX(), key.tileZ()));
        }

        @Override
        public boolean isStale(SectionKey key) {
            return stale.contains(key);
        }

        @Override
        public void markAllStale() {
            for (SectionKey k : recs.keySet()) markStale(k);
        }

        @Override
        public Collection<SectionKey> staleKeys() {
            return new ArrayList<>(stale);
        }

        @Override
        public void forEach(Consumer<SectionRecord> c) {
            recs.values().forEach(c);
        }

        @Override
        public int size() {
            return recs.size();
        }

        @Override
        public synchronized Collection<int[]> takeDirtyTiles() {
            List<int[]> out = new ArrayList<>();
            for (List<Integer> t : dirty) out.add(new int[]{t.get(0), t.get(1), t.get(2)});
            dirty.clear();
            return out;
        }
    }

    /** A flat stone floor at y = floorY - 1 everywhere: standable at floorY, 4 sideways moves of 4.6 ticks. */
    static final class Flat implements CellMoves {
        final int floorY;

        Flat(int floorY) {
            this.floorY = floorY;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return y == floorY;
        }

        @Override
        public void forEachMove(int x, int y, int z, MoveSink s) {
            if (y != floorY) return;
            s.move(x + 1, y, z, 4.633333333333334);
            s.move(x - 1, y, z, 4.633333333333334);
            s.move(x, y, z + 1, 4.633333333333334);
            s.move(x, y, z - 1, 4.633333333333334);
        }
    }

    static AreaBoxes everywhere() {
        return new AreaBoxes(List.of(new int[]{0, -100000, -100000, 100000, 100000, -64, 319}), -64, 319);
    }
}
