package io.github.mojolowjo.entropybot.cave;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** job-tick (run JAU): a record's Density is sampled once and kept until its inputs change. */
class DensityCacheTest {
    @Test
    void unchangedRecordReturnsTheSameObjectAndSamplesOnce() {
        var cache = new CaveWalk.Density.Cache();
        Object basis = new Object();
        AtomicInteger calls = new AtomicInteger();
        java.util.function.IntToDoubleFunction curve = y -> { calls.incrementAndGet(); return y < 16 ? 0.01 : 0.001; };
        var rec = List.of("overworld", 1, 2, 3, "iron");
        var a = cache.get(rec, basis, curve, 100, 50, 0, 0);
        int once = calls.get();
        var b = cache.get(List.of("overworld", 1, 2, 3, "iron"), basis, curve, 100, 50, 0, 0);
        assertSame(a, b);
        assertEquals(once, calls.get());
        assertEquals(1, cache.built());
        var c = cache.get(rec, basis, curve, 120, 50, 0, 0);     // the record's count changed
        assertNotSame(a, c);
        var d = cache.get(rec, new Object(), curve, 120, 50, 0, 0);   // a new placement fact
        assertNotSame(c, d);
        assertEquals(3, cache.built());
    }
}
