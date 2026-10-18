package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryPickTest {
    private static MemoryPick.Spot s(int x, int z) {
        return new MemoryPick.Spot("minecraft:oak_log", x, 64, z, MemoryPick.Source.BARITONE);
    }

    @Test
    void nearestFirstFilteredAndStaleDropped() {
        int[] me = {0, 64, 0};
        List<MemoryPick.Spot> spots = List.of(s(300, 0), s(100, 0), s(200, 0), s(50, 0));
        MemoryPick.Result r = MemoryPick.pick(spots, me,
                (x, y, z) -> x != 50,                                                    // 50: outside the areas
                (id, x, y, z) -> x == 100 ? MemoryPick.Live.LOADED_GONE : MemoryPick.Live.UNLOADED, 2);
        assertEquals(2, r.kept().size());
        assertEquals(200, r.kept().get(0).x());
        assertEquals(300, r.kept().get(1).x());
        assertEquals(1, r.dropped().size());
        assertEquals(100, r.dropped().get(0).x());
    }

    @Test
    void findIdsExactFirst() {
        List<String> all = List.of("minecraft:stripped_oak_log", "minecraft:oak_log_x", "minecraft:logger", "minecraft:oak_log", "minecraft:cave_air");
        assertEquals(List.of("minecraft:oak_log", "minecraft:stripped_oak_log", "minecraft:oak_log_x"), MemoryPick.ids("oak_log", all, 3));
        assertEquals("minecraft:stripped_oak_log", MemoryPick.ids("log", all, 16).get(0));
        assertEquals(List.of(), MemoryPick.ids("air", all, 16));
    }

    @Test
    void textSaysWhereItCameFrom() {
        assertEquals("remembered oak at 180 64 0, 180 blocks", MemoryPick.text(s(180, 0), new int[]{0, 64, 0}));
        assertEquals("found iron_ore at 3 60 4, 5 blocks",
                MemoryPick.text(new MemoryPick.Spot("minecraft:iron_ore", 3, 60, 4, MemoryPick.Source.LIVE), new int[]{0, 60, 0}));
        assertEquals("oak_log", MemoryPick.cacheName("minecraft:oak_log"));
        assertEquals("mod:x_ore", MemoryPick.cacheName("mod:x_ore"));
    }
}
