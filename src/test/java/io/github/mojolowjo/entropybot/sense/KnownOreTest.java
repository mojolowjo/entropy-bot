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
        OreIndex.Count three = new OreIndex.Count(3, false), nine = new OreIndex.Count(9, true);
        String s = KnownOre.summary(64, Map.of("iron", three, "coal", nine), Map.of("iron", h(3, 0, 4), "coal", h(30, 0, 40)), 20, 2);
        assertTrue(s.startsWith("ores known within 64: iron 3 (nearest 3 0 4, 5 away), coal 9+ (nearest 30 0 40, 50 away) | 20 chunks seen within 64 (2 not loaded now) (+: some chunk lists only"), s);
        assertTrue(KnownOre.summary(64, Map.of(), Map.of(), 1, 0).startsWith("no ores known within 64 of me (1 chunk seen"));
        assertEquals("iron ore known within 16: 1; nearest: 3 0 4 (iron_ore, 5 away) | 4 chunks seen within 16", KnownOre.family("iron", 16, new OreIndex.Count(1, false), List.of(h(3, 0, 4)), 4));
        assertTrue(KnownOre.unreachableText("iron", 28, 5).startsWith("28 iron ore known within 96 but none I could reach (tried 5) - next: mine strip iron"));
    }

    private static void checkRoute(int fx, int fy, int fz, int ox, int oy, int oz) {
        List<int[]> r = KnownOre.route(fx, fy, fz, ox, oy, oz);
        assertNotNull(r);
        int[] last = null;
        for (int[] c : r) {
            for (int y = c[1]; y <= c[3]; y++) assertFalse(c[0] == ox && y == oy && c[2] == oz, "never digs the ore cell itself");
            if (c[4] != Integer.MIN_VALUE) last = c;
        }
        int sx = last == null ? fx : last[0], sy = last == null ? fy : last[4], sz = last == null ? fz : last[2];
        assertEquals(oy, sy, "ends at the ore's height");
        assertEquals(1, Math.abs(sx - ox) + Math.abs(sz - oz), "ends beside the ore");
    }

    @Test
    void routesDownUpAndStraight() {
        checkRoute(0, 64, 0, 5, 59, 0);      // a clean diagonal: must not end in the ore
        checkRoute(0, 64, 0, 3, 40, 2);      // deep: overshoots, comes back on the flat
        checkRoute(0, 64, 0, 0, 50, 0);      // straight below
        checkRoute(0, 40, 0, 4, 45, -3);     // above
        checkRoute(0, 64, 0, 6, 64, 1);      // level
        assertNull(KnownOre.route(0, 64, 0, 0, -50, 0), "too far: no route");
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
}