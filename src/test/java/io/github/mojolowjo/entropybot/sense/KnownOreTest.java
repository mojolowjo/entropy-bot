package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KnownOreTest {
    private static OreIndex.Hit h(int x, int y, int z) { return new OreIndex.Hit(x, y, z, "minecraft:iron_ore", "iron", (long) x * x + (long) y * y + (long) z * z, false); }

    @Test
    void familiesAndWords() {
        assertTrue(KnownOre.family("minecraft:deepslate_iron_ore", "iron"));
        assertTrue(KnownOre.family("oritech:nickel_ore", "nickel"));
        assertTrue(KnownOre.family("minecraft:iron_ore", "iron_ore"));
        assertFalse(KnownOre.family("minecraft:coal_ore", "iron"));
        assertEquals("iron", KnownOre.familyOf("iron_ore"));
        assertEquals("oritech:nickel", KnownOre.familyOf("oritech:deepslate_nickel_ore"));
        assertEquals("no iron ore known (I have seen 12 chunks here) - explore or mine cave", KnownOre.noneText("iron", 12));
        assertArrayEquals(new Object[]{"iron", 16}, KnownOre.args("known iron 16"));
        assertArrayEquals(new Object[]{null, 64}, KnownOre.args("known"));
    }

    @Test
    void answers() {
        OreIndex.Count three = new OreIndex.Count(3, false), nine = new OreIndex.Count(9, true);
        String s = KnownOre.summary(64, Map.of("iron", three, "coal", nine), Map.of("iron", h(3, 0, 4), "coal", h(30, 0, 40)), 20, 2);
        assertTrue(s.startsWith("ores known within 64: iron 3 (nearest 3 0 4, 5 away), coal 9+ (nearest 30 0 40, 50 away) | 20 chunks seen within 64 (2 not loaded now) (+: some chunk lists only"), s);
        assertTrue(KnownOre.summary(64, Map.of(), Map.of(), 1, 0).startsWith("no ores known within 64 of me (1 chunk seen"));
        assertEquals("iron ore known within 16: 1; nearest: 3 0 4 (iron_ore, 5 away) | 4 chunks seen within 16", KnownOre.family("iron", 16, new OreIndex.Count(1, false), List.of(h(3, 0, 4)), 4));
    }

    @Test
    void withinCountsPositionsNotChunks() {
        OreIndex ix = new OreIndex(10);
        FakeChunk c = new FakeChunk(0, 0);
        c.set(1, 20, 1, "iron_ore");
        c.set(15, 20, 15, "iron_ore");
        ix.put(c.read());
        assertEquals(new OreIndex.Count(1, false), ix.within("minecraft:overworld", 0, 20, 0, 4).get("iron"));
        FakeChunk big = new FakeChunk(1, 0);
        for (int i = 0; i < 80; i++) big.set(16 + (i % 16), 10 + i / 16, 5, "iron_ore");
        ix.put(big.read());
        assertTrue(ix.within("minecraft:overworld", 20, 10, 5, 4).get("iron").capped());
    }

    /** rm-mining: under aa fair the lines and picks name seen cells only; no "capped" hint at the unseen ones. */
    @Test
    void fairPlayListsSeenOreOnly() {
        OreIndex ix = new OreIndex(10);
        FakeChunk big = new FakeChunk(1, 0);
        for (int i = 0; i < 80; i++) big.set(16 + (i % 16), 10 + i / 16, 5, "iron_ore");
        ix.put(big.read());
        String d = "minecraft:overworld";
        assertNull(OreIndex.fairPlay(false, (x, y, z) -> false), "fair off: no filter");
        OreIndex.Allow seenOne = OreIndex.fairPlay(true, (x, y, z) -> x == 20 && y == 10 && z == 5);
        assertEquals(new OreIndex.Count(1, false), ix.within(d, 20, 10, 5, 4, seenOne).get("iron"), "only the seen cell, never capped");
        OreIndex.Allow none = OreIndex.fairPlay(true, null);
        assertNull(ix.within(d, 20, 10, 5, 4, none).get("iron"), "nothing seen: nothing listed");
        List<OreIndex.Hit> hits = ix.nearest(d, id -> true, 20, 10, 5, 64, 8, seenOne);
        assertEquals(1, hits.size());
        assertEquals(20, hits.get(0).x());
    }
}
