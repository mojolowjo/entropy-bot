package io.github.mojolowjo.entropybot.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** The in-memory {@link RouteStore}: concurrent maps, plus the set of tiles changed since the last save. */
final class MemoryRouteStore implements RouteStore {
    private record Tile(int dim, int tx, int tz) {
    }

    private final ConcurrentHashMap<SectionKey, SectionRecord> boxes = new ConcurrentHashMap<>();
    private final Set<SectionKey> stale = ConcurrentHashMap.newKeySet();
    private final Set<Tile> dirty = ConcurrentHashMap.newKeySet();

    private void touch(SectionKey k) {
        dirty.add(new Tile(k.dim(), k.tileX(), k.tileZ()));
    }

    @Override
    public SectionRecord get(SectionKey key) {
        return boxes.get(key);
    }

    @Override
    public void put(SectionRecord rec) {
        boxes.put(rec.key(), rec);
        stale.remove(rec.key());
        touch(rec.key());
    }

    @Override
    public void markStale(SectionKey key) {
        if (boxes.containsKey(key) && stale.add(key)) touch(key);
    }

    @Override
    public boolean isStale(SectionKey key) {
        return stale.contains(key);
    }

    @Override
    public void markAllStale() {
        for (SectionKey k : boxes.keySet()) markStale(k);
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
