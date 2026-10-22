package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** BRAIN_PLAN B5: ore Y learning (OreStats). */
class OreStatsTest {
    static ChunkSummary chunk(String dim, int cx, int cz, String id, int... ys) {
        ChunkSummary s = new ChunkSummary(dim, cx, cz);
        ChunkSummary.Group g = new ChunkSummary.Group();
        for (int y : ys) g.add(new ChunkSummary.Spot(cx * 16, y, cz * 16, id));
        s.ores.put("mekanism:osmium", g);
        return s;
    }

    @Test
    void rangePercentilesAndPeak() {
        OreStats o = new OreStats();
        for (int i = 0; i < 100; i++) o.add("minecraft:overworld", "mekanism:osmium", 20 + (i % 10));
        o.add("minecraft:overworld", "mekanism:osmium", -50);   // an outlier below the 5th percentile
        var r = o.range("minecraft:overworld", "mekanism:osmium");
        assertEquals(101, r.n());
        assertEquals(-50, r.minY());
        assertEquals(20, r.lowY());
        assertEquals(29, r.highY());
        assertTrue(r.peakY() >= 16 && r.peakY() <= 28, "" + r.peakY());
        assertNull(o.range("minecraft:the_nether", "mekanism:osmium"));
    }

    @Test
    void aChunkCountsOnceAndTheFileRoundTrips() {
        OreStats o = new OreStats();
        assertTrue(o.addChunk(chunk("minecraft:overworld", 1, 2, "mekanism:osmium_ore", 10, 12, 14)));
        assertFalse(o.addChunk(chunk("minecraft:overworld", 1, 2, "mekanism:osmium_ore", 10, 12, 14)), "a resend");
        assertTrue(o.addChunk(chunk("minecraft:the_nether", 1, 2, "mekanism:osmium_ore", 40)), "same chunk, other dimension");
        assertEquals(2, o.chunks());
        int[] bad = new int[1];
        OreStats back = OreStats.fromJson(o.toJson(), bad);
        assertEquals(0, bad[0]);
        assertEquals(2, back.chunks());
        assertEquals(3, back.range("minecraft:overworld", "mekanism:osmium").n());
        assertFalse(back.dirty());
        assertFalse(back.addChunk(chunk("minecraft:overworld", 1, 2, "mekanism:osmium_ore", 10)), "still counted after a restart");
        assertEquals("minecraft:overworld", back.best("mekanism:osmium").dim(), "most seen wins");
    }

    @Test
    void brokenFilesAreSkippedNotFatal() {
        int[] bad = new int[1];
        OreStats o = OreStats.fromJson("not json {", bad);
        assertTrue(bad[0] > 0);
        assertEquals(0, o.chunks());
        bad[0] = 0;
        o = OreStats.fromJson("{\"hist\":{\"d\":{\"f\":{\"x\":3,\"5\":2}}},\"chunks\":{\"d\":[1,\"q\"]}}", bad);
        assertTrue(bad[0] > 0);
        assertEquals(2, o.range("d", "f").n());
    }

    @Test
    void infoTextSaysWhatIsKnown() {
        OreStats o = new OreStats();
        assertTrue(o.info("osmium").contains("no osmium seen"));
        o.add("minecraft:overworld", "mekanism:osmium", 20);
        assertTrue(o.info("osmium").contains("mekanism:osmium in minecraft:overworld"), o.info("osmium"));
        assertTrue(o.info("osmiums").contains("1 seen"));
        assertTrue(o.info("").contains("learned from"));
    }
}
