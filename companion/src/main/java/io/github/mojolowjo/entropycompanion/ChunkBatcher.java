package io.github.mojolowjo.entropycompanion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Companion 0.3.0 (chunks-0.23.5): which scanned chunks go to the dashboard, and when. Plain Java (JUnit). A scan is
 * offered with a hash of its columns (not its time): one equal to the last one the dashboard took is dropped, so a chunk
 * is only sent when it changed. Posts go out at most every {@link #INTERVAL_MS}, at most {@link #MAX_PER_POST} chunks,
 * the oldest offers first. A failed post puts its chunks back (a newer scan of the same chunk wins) and waits longer:
 * 10 s, 20 s, 40 s ... up to {@link #MAX_BACKOFF_MS}; a success goes back to 10 s. At most {@link #MAX_PENDING} chunks
 * wait; past that the oldest are dropped (counted). Thread-safe (synchronized): the tick offers, a worker posts.
 */
public final class ChunkBatcher {
    public static final long INTERVAL_MS = 10_000, MAX_BACKOFF_MS = 300_000;
    public static final int MAX_PER_POST = 64, MAX_PENDING = 4096;

    /** One scanned chunk ready to send: its key ("dim cx cz"), its JSON, the hash of its columns. */
    public record Item(String key, String json, long hash) {
    }

    private final LinkedHashMap<String, Item> pending = new LinkedHashMap<>();
    private final Map<String, Long> sentHash = new HashMap<>();
    private long nextPostAt;
    private int failuresInRow;
    private long chunksSent, posts, failures, unchanged, dropped, lastOkAt, lastTryAt;
    private String lastError = "none";

    /** A scan. False when it equals what the dashboard already has (nothing to send). */
    public synchronized boolean offer(String key, String json, long hash) {
        Long h = sentHash.get(key);
        if (h != null && h == hash) {
            pending.remove(key);
            unchanged++;
            return false;
        }
        pending.remove(key);                 // re-inserted at the end: a re-scan waits its turn again
        pending.put(key, new Item(key, json, hash));
        while (pending.size() > MAX_PENDING) {
            Iterator<String> it = pending.keySet().iterator();
            it.next();
            it.remove();
            dropped++;
        }
        return true;
    }

    /** True when a post should go out now. */
    public synchronized boolean due(long now) {
        return !pending.isEmpty() && now >= nextPostAt;
    }

    /** The next batch (taken out of the queue; give it back with {@link #done}). */
    public synchronized List<Item> take(long now) {
        List<Item> out = new ArrayList<>();
        Iterator<Item> it = pending.values().iterator();
        while (it.hasNext() && out.size() < MAX_PER_POST) {
            out.add(it.next());
            it.remove();
        }
        lastTryAt = now;
        nextPostAt = now + INTERVAL_MS;      // no second post while this one is out
        return out;
    }

    /** The post's outcome. error null = the dashboard took it. */
    public synchronized void done(List<Item> batch, String error, long now) {
        if (error == null) {
            for (Item i : batch) sentHash.put(i.key(), i.hash());
            chunksSent += batch.size();
            posts++;
            failuresInRow = 0;
            lastOkAt = now;
            nextPostAt = now + INTERVAL_MS;
            return;
        }
        failures++;
        failuresInRow++;
        lastError = error;
        LinkedHashMap<String, Item> back = new LinkedHashMap<>();
        for (Item i : batch) back.put(i.key(), i);
        for (Map.Entry<String, Item> e : pending.entrySet()) back.put(e.getKey(), e.getValue());   // a newer scan wins
        pending.clear();
        pending.putAll(back);
        nextPostAt = now + backoffMs(failuresInRow);
    }

    /** 10 s after the first failure in a row, doubling, at most 5 minutes. */
    public static long backoffMs(int failuresInRow) {
        if (failuresInRow <= 0) return INTERVAL_MS;
        long d = INTERVAL_MS << Math.min(failuresInRow - 1, 10);
        return Math.min(d, MAX_BACKOFF_MS);
    }

    /** Forget what was sent (a new world: the dashboard's files may belong to another one). */
    public synchronized void reset() {
        pending.clear();
        sentHash.clear();
        nextPostAt = 0;
        failuresInRow = 0;
    }

    public synchronized int pending() { return pending.size(); }
    public synchronized long chunksSent() { return chunksSent; }
    public synchronized long posts() { return posts; }
    public synchronized long failures() { return failures; }
    public synchronized long lastOkAt() { return lastOkAt; }
    public synchronized long nextPostAt() { return nextPostAt; }

    public synchronized String line(long now) {
        return "chunks sent " + chunksSent + " in " + posts + " posts, last post " + (lastOkAt == 0 ? "never" : (now - lastOkAt) / 1000 + " s ago")
                + ", " + pending.size() + " waiting, " + unchanged + " unchanged, failures " + failures
                + (failuresInRow > 0 ? " (" + failuresInRow + " in a row, next try in " + Math.max(0, nextPostAt - now) / 1000 + " s; last: " + lastError + ")" : "")
                + (dropped > 0 ? ", dropped " + dropped : "");
    }
}
