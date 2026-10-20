package io.github.mojolowjo.entropybot.aa;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * AA_ROUND3 4 (the 33 ms pick): a facts supplier's memory. Within one game tick a value is read once (the pick, the
 * re-score and the sequencer all ask); across ticks it is kept for {@code ttlMs} while its key (where I stand, what
 * I carry: the caller's choice) is unchanged. A null value is kept too (nothing readable stays nothing for that tick).
 * A load that throws keeps nothing and rethrows (the picker counts and logs it).
 *
 * <p>Pure (JUnit: FactsMemoTest). Loader notes: none (the adapter passes the game time as the tick).
 */
public final class FactsMemo<T> {
    private final long ttlMs;
    private boolean has;
    private T value;
    private Object key;
    private long tick, at;

    /** @param ttlMs how long a value outlives its tick with the same key (0: one tick only) */
    public FactsMemo(long ttlMs) { this.ttlMs = Math.max(0, ttlMs); }

    public T get(long tick, long nowMs, Object key, Supplier<T> load) {
        if (has && ((tick >= 0 && tick == this.tick) || (nowMs - at < ttlMs && Objects.equals(key, this.key)))) return value;
        has = false;
        T v = load.get();
        value = v;
        this.key = key;
        this.tick = tick;
        at = nowMs;
        has = true;
        return v;
    }

    /** Forget the value (the world changed under it: a dig, a pickup). */
    public void clear() { has = false; value = null; }

    public boolean has() { return has; }
}
