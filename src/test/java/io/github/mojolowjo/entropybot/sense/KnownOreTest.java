package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KnownOreTest {
    private static OreIndex.Hit h(int x, int y, int z) { return new OreIndex.Hit(x, y, z, "minecraft:iron_ore", "iron", (long) x * x + (long) y * y + (long) z * z, false); }

    @Test
    void veinChainsNearPositionsOnly() {
        OreIndex.Hit a = h(0, 10, 0), b = h(1, 10, 0), c = h(3, 11, 0), far = h(20, 10, 0);
        List<OreIndex.Hit> v = KnownOre.vein(List.of(a, b, c, far), a, 24);
        assertEquals(List.of(a, b, c), v);
        assertEquals(2, KnownOre.vein(List.of(a, b, c), a, 2).size());
    }

    @Test
    void familiesAndWords() {
        assertTrue(KnownOre.family("minecraft:deepslate_iron_ore", "iron"));
        assertTrue(KnownOre.family("oritech:nickel_ore", "nickel"));
        assertTrue(KnownOre.family("minecraft:iron_ore", "iron_ore"));
        assertFalse(KnownOre.family("minecraft:coal_ore", "iron"));
        assertEquals("iron", KnownOre.familyOf("iron_ore"));
        assertEquals("oritech:nickel", KnownOre.familyOf("oritech:deepslate_nickel_ore"));
        assertEquals("no iron ore known in my areas (I have seen 12 chunks here) - explore or mine cave", KnownOre.noneText("iron", 12));
        assertArrayEquals(new Object[]{"iron", 16}, KnownOre.args("known iron 16"));
        assertArrayEquals(new Object[]{null, 64}, KnownOre.args("known"));
    }

    @Test
    void answers() {
        String s = KnownOre.summary(64, Map.of("iron", 3, "coal", 9), Map.of("iron", h(3, 0, 4), "coal", h(30, 0, 40)), 20, 2);
        assertEquals("ores known within 64: iron 3 (nearest 3 0 4, 5 away), coal 9 (nearest 30 0 40, 50 away) | 20 chunks seen within 64 (2 not loaded now)", s);
        assertTrue(KnownOre.summary(64, Map.of(), Map.of(), 1, 0).startsWith("no ores known within 64 of me (1 chunk seen"));
        assertEquals("iron ore known within 16: 1 in all; nearest: 3 0 4 (iron_ore, 5 away) | 4 chunks seen within 16", KnownOre.family("iron", 16, 1, List.of(h(3, 0, 4)), 4));
    }
}
