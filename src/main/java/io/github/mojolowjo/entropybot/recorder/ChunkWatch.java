package io.github.mojolowjo.entropybot.recorder;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongFunction;

/**
 * Which chunks the recorder knows exactly (pure; game thread only, except {@link #written}). A chunk is "covered" in
 * {@link RecStore} (so {@code blocksAt} may say "exact") only while all of this holds:
 * <ul>
 *   <li>its baseline was written and it has been tracked since (the same chunk object, never unloaded);</li>
 *   <li>it stayed inside the recording range, so every change in it was recorded ({@link #watch} drops a chunk that
 *       leaves the range and forgets its coverage);</li>
 *   <li>no change in it was dropped (out of range at that moment, rate-limited): {@link #missed};</li>
 *   <li>recording never went off and the range never changed since: {@link #reset}.</li>
 * </ul>
 * A dropped chunk is tracked afresh when it comes back: a new baseline and new coverage.
 */
public final class ChunkWatch {
    private final RecStore store;
    private String dim;
    private final Map<Long, Entry> tracked = new HashMap<>();
    /** Baselines the background thread has written: {dim, key, takenMs}; coverage starts when the game thread sees them. */
    private final Queue<Object[]> written = new ConcurrentLinkedQueue<>();

    private static final class Entry {
        final WeakReference<Object> ref;
        final long takenMs, since;

        Entry(Object chunk, long takenMs, long since) {
            this.ref = new WeakReference<>(chunk);
            this.takenMs = takenMs;
            this.since = since;
        }
    }

    public ChunkWatch(RecStore store) {
        this.store = store;
    }

    public static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    static int cx(long k) { return (int) (k >> 32); }

    static int cz(long k) { return (int) k; }

    public int size() { return tracked.size(); }

    public boolean tracked(int cx, int cz) { return tracked.containsKey(key(cx, cz)); }

    /** Another dimension (or level): everything starts over. */
    public void dim(String d) {
        if (d == null || !d.equals(dim)) {
            reset();
            dim = d;
        }
    }

    /** Recording went off, the range changed, the level went away: nothing is exact any more, all chunks start over. */
    public void reset() {
        tracked.clear();
        written.clear();
        store.clearCoverage();
    }

    /** True when the chunk (this object) needs a baseline now: new, reloaded, or its baseline older than rebaseMs. */
    public boolean needsBaseline(int cx, int cz, Object chunk, long now, long rebaseMs) {
        Entry e = tracked.get(key(cx, cz));
        return e == null || e.ref.get() != chunk || now - e.takenMs >= rebaseMs;
    }

    /**
     * A baseline of the chunk is being taken at now (it reaches the disk later, then {@link #written}). A fresh one
     * starts a new coverage (the old interval no longer matches the baseline on disk, so it is forgotten now); a
     * re-take of a chunk still tracked keeps its start.
     */
    public void taking(int cx, int cz, Object chunk, long now) {
        long k = key(cx, cz);
        Entry e = tracked.get(k);
        boolean fresh = e == null || e.ref.get() != chunk;
        if (fresh) store.forgetCoverage(dim, cx, cz);
        tracked.put(k, new Entry(chunk, now, fresh ? now : e.since));
    }

    /** Background thread: the baseline taken at takenMs is on disk. */
    public void written(String d, int cx, int cz, long takenMs) {
        written.add(new Object[]{d, key(cx, cz), takenMs});
    }

    /** A change in this chunk was not recorded: the chunk is no longer known exactly. */
    public void missed(int cx, int cz) {
        tracked.remove(key(cx, cz));
        store.forgetCoverage(dim, cx, cz);
    }

    /**
     * Once a second: coverage starts for the baselines written meanwhile (if their chunk is still tracked by that same
     * take), runs on for the tracked chunks still loaded within range of the bot's chunk, and ends for the rest.
     * loaded: the chunk object at a key now, or null.
     */
    public void watch(int pcx, int pcz, int range, LongFunction<Object> loaded, long now) {
        for (Object[] w; (w = written.poll()) != null; ) {
            if (!w[0].equals(dim)) continue;
            long k = (Long) w[1];
            Entry e = tracked.get(k);
            if (e != null && e.takenMs == (Long) w[2]) store.covered(dim, cx(k), cz(k), e.since, now);
        }
        for (Iterator<Map.Entry<Long, Entry>> it = tracked.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Entry> me = it.next();
            long k = me.getKey();
            int cx = cx(k), cz = cz(k);
            Object c = loaded.apply(k);
            if (c == null || c != me.getValue().ref.get() || Math.abs(cx - pcx) > range || Math.abs(cz - pcz) > range) {
                it.remove();
                store.forgetCoverage(dim, cx, cz);
                continue;
            }
            store.seen(dim, cx, cz, now);
        }
    }
}
