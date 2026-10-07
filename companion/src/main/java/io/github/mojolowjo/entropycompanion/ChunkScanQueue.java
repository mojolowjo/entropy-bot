package io.github.mojolowjo.entropycompanion;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Companion 0.3.0: which loaded chunks are due a column scan. A loaded chunk is due at once; a block change makes its chunk
 * due {@link #DEBOUNCE_MS} later (a burst of changes is one scan); an unloaded chunk is dropped. {@link #take} hands out
 * the due ones, oldest first; the caller scans them within its time budget and gives back the rest with {@link #again}.
 * Plain Java; client thread only.
 */
public final class ChunkScanQueue {
    public static final long DEBOUNCE_MS = 2000;

    private final LinkedHashMap<Long, Long> due = new LinkedHashMap<>();

    public static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }
    public static int cx(long k) { return (int) (k >> 32); }
    public static int cz(long k) { return (int) k; }

    public void loaded(int cx, int cz, long now) {
        due.put(key(cx, cz), now);
    }

    public void changed(int cx, int cz, long now) {
        Long at = due.get(key(cx, cz));
        if (at == null || at > now + DEBOUNCE_MS) due.put(key(cx, cz), now + DEBOUNCE_MS);
    }

    public void unloaded(int cx, int cz) {
        due.remove(key(cx, cz));
    }

    public void clear() { due.clear(); }

    public int size() { return due.size(); }

    /** Up to n chunks due by now, removed from the queue. */
    public List<Long> take(long now, int n) {
        List<Long> out = new ArrayList<>();
        Iterator<Map.Entry<Long, Long>> it = due.entrySet().iterator();
        while (it.hasNext() && out.size() < n) {
            Map.Entry<Long, Long> e = it.next();
            if (e.getValue() <= now) {
                out.add(e.getKey());
                it.remove();
            }
        }
        return out;
    }

    /** A chunk that was taken but not scanned (out of time): due again now, unless something newer is queued. */
    public void again(long k, long now) {
        due.putIfAbsent(k, now);
    }
}
