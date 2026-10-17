package io.github.mojolowjo.entropybot.trail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrailTest {
    @Test
    void offersEveryStepAndRoundTrips() {
        Trail t = new Trail();
        assertTrue(t.offer("o", 0, 64, 0, true));
        assertFalse(t.offer("o", 2, 64, 0, true));
        assertTrue(t.offer("o", 4, 64, 0, true));
        assertTrue(t.offer("n", 4, 64, 0, false));          // another dimension always counts
        int[] skipped = new int[1];
        Trail u = Trail.fromText(t.toText() + "garbage line\n", skipped);
        assertEquals(3, u.size());
        assertEquals(1, skipped[0]);
        assertTrue(u.points().get(0).sky());
    }

    @Test
    void retraceHopsBackToSky() {
        Trail t = new Trail();
        t.offer("o", 0, 64, 0, true);                     // the cave mouth
        for (int i = 1; i <= 10; i++) t.offer("o", i * 4, 64 - i * 4, 0, false);
        List<int[]> h = t.retrace("o", 40, 24, 0, 8, 8, 400);
        assertFalse(h.isEmpty());
        assertArrayEquals(new int[]{0, 64, 0}, h.get(h.size() - 1));   // ends at open sky
        for (int i = 1; i < h.size(); i++) {
            int[] a = h.get(i - 1), b = h.get(i);
            assertTrue(Math.abs(a[0] - b[0]) <= 12, "hops are short");
        }
        assertTrue(t.retrace("o", 500, 24, 500, 8, 8, 400).isEmpty());   // far from the trail
        Trail noSky = new Trail();
        for (int i = 0; i < 5; i++) noSky.offer("o", i * 4, 20, 0, false);
        assertTrue(noSky.retrace("o", 16, 20, 0, 8, 8, 400).isEmpty());
    }

    @Test
    void boxesAndCorridors() {
        Trail t = new Trail();
        for (int i = 0; i < 20; i++) t.offer("o", i * 4, 64, 0, true);
        List<int[]> b = t.boxes("o", 32, 10);
        assertEquals(3, b.size());
        assertEquals(56, b.get(0)[4]);                     // y range +-8
        List<int[]> c = Trail.corridors(List.of(new int[]{0, 0, 9, 9}, new int[]{100, 0, 109, 9}), 16, 40, 140, 40);
        assertTrue(c.size() >= 5, "a box every 16 along 100 blocks: " + c.size());
        assertEquals(40, c.get(0)[4]);
    }
}
