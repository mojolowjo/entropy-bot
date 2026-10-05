package io.github.mojolowjo.entropybot.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The in-memory {@link RouteStore}: concurrent maps, plus the set of tiles changed since the last save.
 *
 * <p>Review S3, changes during a build: every build takes a stamp from one counter ({@link #beginBuild}); a change to a
 * box being built takes a later stamp ({@link #changedAt}). {@link #put(SectionRecord, long)} keeps the box stale when
 * a change came after the build's stamp. Both maps only hold keys with a build in flight, so they stay small. The few
 * writes that mark or clear stale take the store's lock, so a change can't slip between a put's check and its stale
 * clear; reads stay lock-free.
 */
final class MemoryRouteStore implements RouteStore {
    private record Tile(int dim, int tx, int tz) {
    }

    private final ConcurrentHashMap<SectionKey, SectionRecord> boxes = new ConcurrentHashMap<>();
    private final Set<SectionKey> stale = ConcurrentHashMap.newKeySet();
    private final Set<Tile> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicLong clock = new AtomicLong();
    /** Builds in flight: key -> the newest build's stamp. */
    private final ConcurrentHashMap<SectionKey, Long> building = new ConcurrentHashMap<>();
    /** Keys with a build in flight that changed: key -> the newest change's stamp. */
    private final ConcurrentHashMap<SectionKey, Long> changedAt = new ConcurrentHashMap<>();

    private void touch(SectionKey k) {
        dirty.add(new Tile(k.dim(), k.tileX(), k.tileZ()));
    }

    @Override
    public SectionRecord get(SectionKey key) {
        return boxes.get(key);
    }

    @Override
    public synchronized void put(SectionRecord rec) {
        boxes.put(rec.key(), rec);
        stale.remove(rec.key());
        touch(rec.key());
    }

    @Override
    public synchronized long beginBuild(SectionKey key) {
        long s = clock.incrementAndGet();
        building.merge(key, s, Math::max);
        return s;
    }

    @Override
    public synchronized void put(SectionRecord rec, long stamp) {
        SectionKey k = rec.key();
        boxes.put(k, rec);
        Long c = changedAt.get(k);
        if (c != null && c > stamp) stale.add(k);
        else stale.remove(k);
        touch(k);
        endBuild(k, stamp);
    }

    @Override
    public synchronized void endBuild(SectionKey key, long stamp) {
        // the last build of the key ended: forget its change stamp (a newer build keeps both)
        if (building.remove(key, stamp)) changedAt.remove(key);
    }

    @Override
    public synchronized boolean noteChangeWhileBuilding(SectionKey key) {
        if (!building.containsKey(key)) return false;
        changedAt.put(key, clock.incrementAndGet());
        return true;
    }

    @Override
    public synchronized void markStale(SectionKey key) {
        noteChangeWhileBuilding(key);
        if (boxes.containsKey(key) && stale.add(key)) touch(key);
    }

    @Override
    public boolean isStale(SectionKey key) {
        return stale.contains(key);
    }

    @Override
    public void markAllStale() {
        for (SectionKey k : boxes.keySet()) markStale(k);
        for (SectionKey k : building.keySet()) noteChangeWhileBuilding(k);
    }

    @Override
    public Collection<SectionKey> staleKeys() {
        return new ArrayList<>(stale);
    }

    @Override
    public void forEach(Consumer<SectionRecord> c) {
        boxes.values().forEach(c);
    }

    @Override
    public int size() {
        return boxes.size();
    }

    @Override
    public Collection<int[]> takeDirtyTiles() {
        List<int[]> out = new ArrayList<>();
        for (Tile t : new ArrayList<>(dirty)) {
            dirty.remove(t);
            out.add(new int[]{t.dim(), t.tx(), t.tz()});
        }
        return out;
    }
}
