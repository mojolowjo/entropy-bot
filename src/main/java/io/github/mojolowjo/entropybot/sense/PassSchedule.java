package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.28.0: which chunks the pass reads next (grown from surface/SurfaceSchedule). A chunk that arrives is due at once:
 * a first load, or a resend when it was already loaded (the server sent it again). A block change is a patch, not a read;
 * a patch the pass had to drop marks the chunk "missed": due again after {@link #MISSED_DELAY_MS} (one rescan however
 * many drops: dedupe). A failed read is due again after the same delay. An unloaded chunk leaves the set. {@link #take}
 * hands out at most n due chunks, nearest to the bot first; what the tick's budget leaves goes back with {@link #requeue}.
 *
 * <p>Plain Java, JUnit, game thread only. Loader notes: none.
 */
public final class PassSchedule {
    public static final long MISSED_DELAY_MS = 1000;

    public enum Why { LOAD, RESEND, MISSED, RETRY }

    public record Item(int cx, int cz, Why why) {}

    private final Map<Long, Long> due = new HashMap<>();
    private final Map<Long, Why> why = new HashMap<>();
    private final Set<Long> loaded = new HashSet<>();
    public long loads, resends, missedMarks, missedDeduped;

    public void loaded(int cx, int cz, long now) {
        long k = ChunkSummary.key(cx, cz);
        Why w = loaded.add(k) ? Why.LOAD : Why.RESEND;
        if (w == Why.LOAD) loads++;
        else resends++;
        // a pending missed rescan is replaced by the read of the new data (one read)
        due.put(k, now);
        why.put(k, w);
    }

    public void unloaded(int cx, int cz) {
        long k = ChunkSummary.key(cx, cz);
        loaded.remove(k);
        due.remove(k);
        why.remove(k);
    }

    public boolean isLoaded(int cx, int cz) { return loaded.contains(ChunkSummary.key(cx, cz)); }

    public boolean isPending(int cx, int cz) { return due.containsKey(ChunkSummary.key(cx, cz)); }

    /** A patch was dropped: rescan soon. False (nothing to do) when the chunk is not loaded; deduped when one is pending. */
    public boolean missed(int cx, int cz, long now) {
        long k = ChunkSummary.key(cx, cz);
        if (!loaded.contains(k)) return false;
        if (due.containsKey(k)) {
            missedDeduped++;
            return true;
        }
        missedMarks++;
        due.put(k, now + MISSED_DELAY_MS);
        why.put(k, Why.MISSED);
        return true;
    }

    /** A read failed (it threw): the chunk stays unknown and is due again after the delay. */
    public void failed(Item it, long now) {
        long k = ChunkSummary.key(it.cx(), it.cz());
        if (!loaded.contains(k)) return;
        due.put(k, now + MISSED_DELAY_MS);
        why.put(k, Why.RETRY);
    }

    /** Over the budget: back in, due now, with its reason. */
    public void requeue(Item it, long now) {
        long k = ChunkSummary.key(it.cx(), it.cz());
        if (!loaded.contains(k)) return;
        due.putIfAbsent(k, now);
        why.putIfAbsent(k, it.why());
    }

    public void clear() {
        due.clear();
        why.clear();
        loaded.clear();
    }

    public int pending() { return due.size(); }

    public int loadedCount() { return loaded.size(); }

    public List<Long> loadedKeys() { return new ArrayList<>(loaded); }

    /** Up to n chunks due at now, nearest to (ecx, ecz) first; they leave the due set. */
    public List<Item> take(int ecx, int ecz, long now, int n) {
        List<long[]> ready = new ArrayList<>();
        for (Map.Entry<Long, Long> e : due.entrySet()) {
            if (e.getValue() > now) continue;
            long k = e.getKey();
            long dx = ChunkSummary.cx(k) - ecx, dz = ChunkSummary.cz(k) - ecz;
            ready.add(new long[]{k, dx * dx + dz * dz});
        }
        ready.sort((a, b) -> a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[0], b[0]));
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < ready.size() && out.size() < n; i++) {
            long k = ready.get(i)[0];
            due.remove(k);
            Why w = why.remove(k);
            out.add(new Item(ChunkSummary.cx(k), ChunkSummary.cz(k), w == null ? Why.LOAD : w));
        }
        return out;
    }
}
