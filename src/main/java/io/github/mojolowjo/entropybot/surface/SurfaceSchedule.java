package io.github.mojolowjo.entropybot.surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.19.3: which loaded chunks the surface export writes next (pure, game thread only, JUnit). A loaded chunk is due at
 * once; a changed one again at most every {@link #DEBOUNCE_MS} after its last write; an unloaded one is dropped (its
 * pending write cancelled). {@link #take} hands out at most n due chunks, nearest to the bot first. Loader notes: none.
 */
public final class SurfaceSchedule {
    public static final long DEBOUNCE_MS = 2000;
    public static final int PER_TICK = 2;

    private final Map<Long, Long> due = new HashMap<>();
    private final Map<Long, Long> lastWrite = new HashMap<>();

    public static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    public static int cx(long k) { return (int) (k >> 32); }

    public static int cz(long k) { return (int) k; }

    public void loaded(int cx, int cz, long now) {
        long k = key(cx, cz);
        lastWrite.remove(k);
        due.put(k, now);
    }

    /** A block changed in the chunk: due again, no sooner than DEBOUNCE_MS after its last write (an earlier due date stays). */
    public void changed(int cx, int cz, long now) {
        long k = key(cx, cz);
        Long lw = lastWrite.get(k);
        long d = lw == null ? now : Math.max(now, lw + DEBOUNCE_MS);
        Long old = due.get(k);
        if (old == null || d < old) due.put(k, d);
    }

    public void unloaded(int cx, int cz) {
        long k = key(cx, cz);
        due.remove(k);
        lastWrite.remove(k);
    }

    public void clear() {
        due.clear();
        lastWrite.clear();
    }

    public int pending() { return due.size(); }

    public boolean isPending(int cx, int cz) { return due.containsKey(key(cx, cz)); }

    /** Up to n chunks due at now, nearest to (ecx, ecz) first; they leave the pending set and count as written at now. */
    public List<long[]> take(int ecx, int ecz, long now, int n) {
        List<long[]> ready = new ArrayList<>();
        for (Map.Entry<Long, Long> e : due.entrySet()) {
            if (e.getValue() > now) continue;
            long k = e.getKey();
            long dx = cx(k) - ecx, dz = cz(k) - ecz;
            ready.add(new long[]{k, dx * dx + dz * dz});
        }
        ready.sort((a, b) -> a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[0], b[0]));
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < ready.size() && out.size() < n; i++) {
            long k = ready.get(i)[0];
            due.remove(k);
            lastWrite.put(k, now);
            out.add(new long[]{cx(k), cz(k)});
        }
        return out;
    }

    /** check: on and nothing written for this long. */
    public static final long QUIET_MS = 60_000;

    /** Pure: the settings file's "on" value, null when absent or broken. */
    public static Boolean parseOn(String text) {
        if (text == null || text.isBlank() || text.startsWith("error: ")) return null;
        try {
            com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(text);
            if (!e.isJsonObject() || !e.getAsJsonObject().has("on")) return null;
            com.google.gson.JsonElement v = e.getAsJsonObject().get("on");
            return v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() ? v.getAsBoolean() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Pure: on, in a world for over a minute, and nothing written for a minute while chunks wait (or never anything). */
    public static boolean quiet(boolean on, boolean inWorld, long inWorldMs, long sinceWriteMs, int pending) {
        if (!on || !inWorld || inWorldMs < QUIET_MS) return false;
        if (sinceWriteMs < 0) return true;
        return sinceWriteMs >= QUIET_MS && pending > 0;
    }
}
