package io.github.mojolowjo.entropybot.sense;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OreMergeTest {
    private static OreIndex.Hit h(int x, int y, int z, long d2) {
        return new OreIndex.Hit(x, y, z, "minecraft:iron_ore", "iron", d2, false);
    }

    @Test
    void dedupesByPositionIndexWins() {
        var idx = List.of(h(1, 2, 3, 14));
        var live = List.of(h(1, 2, 3, 14), h(5, 5, 5, 3));
        var m = OreMerge.merge(idx, live, 10);
        assertEquals(2, m.hits().size());
        assertEquals(1, m.fromIndex());
        assertEquals(1, m.liveOnly());
        assertEquals(5, m.hits().get(0).x());           // nearest first
        assertEquals("live scan", m.sourceOf(5, 5, 5, Set.of(OreMerge.key(1, 2, 3))));
        assertEquals("index+live", m.sourceOf(1, 2, 3, Set.of(OreMerge.key(1, 2, 3))));
    }

    @Test
    void capsAndHandlesEmpty() {
        var m = OreMerge.merge(List.of(), List.of(h(1, 1, 1, 3), h(2, 2, 2, 12), h(-3, 9, 4, 20)), 2);
        assertEquals(2, m.hits().size());
        assertEquals(3, m.liveOnly());
        assertTrue(OreMerge.merge(List.of(), List.of(), 5).hits().isEmpty());
        assertEquals("index", m.sourceOf(9, 9, 9, Set.of()));
    }

    @Test
    void negativeCoordsDistinctKeys() {
        assertNotEquals(OreMerge.key(-1, 5, 3), OreMerge.key(1, 5, 3));
        assertNotEquals(OreMerge.key(1, 5, -3), OreMerge.key(1, 5, 3));
    }
}
