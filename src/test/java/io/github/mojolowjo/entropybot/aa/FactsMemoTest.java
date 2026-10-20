package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND3 4: a facts supplier is read once per tick, and kept for its ttl while the key holds. */
class FactsMemoTest {
    @Test
    void oncePerTickThenByKeyAndTtl() {
        AtomicInteger loads = new AtomicInteger();
        FactsMemo<Integer> m = new FactsMemo<>(10_000);
        assertEquals(1, m.get(5, 0, "a", loads::incrementAndGet));
        assertEquals(1, m.get(5, 0, "other key, same tick", loads::incrementAndGet));
        assertEquals(1, m.get(6, 9_000, "a", loads::incrementAndGet));          // same key within the ttl
        assertEquals(2, m.get(7, 9_100, "b", loads::incrementAndGet));          // moved: read again
        assertEquals(3, m.get(8, 20_000, "b", loads::incrementAndGet));         // past the ttl
        m.clear();
        assertEquals(4, m.get(8, 20_000, "b", loads::incrementAndGet));         // cleared: read again, same tick
    }

    @Test
    void oneTickOnlyAndNullKept() {
        AtomicInteger loads = new AtomicInteger();
        FactsMemo<String> m = new FactsMemo<>(0);
        assertNull(m.get(1, 0, "k", () -> { loads.incrementAndGet(); return null; }));
        assertNull(m.get(1, 0, "k", () -> { loads.incrementAndGet(); return "x"; }));
        assertEquals(1, loads.get());
        assertEquals("x", m.get(2, 0, "k", () -> "x"));
    }

    @Test
    void aThrowKeepsNothing() {
        FactsMemo<String> m = new FactsMemo<>(10_000);
        assertThrows(IllegalStateException.class, () -> m.get(1, 0, "k", () -> { throw new IllegalStateException("x"); }));
        assertFalse(m.has());
        assertEquals("y", m.get(1, 0, "k", () -> "y"));
    }
}
