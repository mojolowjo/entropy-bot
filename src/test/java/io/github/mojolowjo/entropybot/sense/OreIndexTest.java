package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OreIndexTest {
    private static ChunkSummary chunk(int cx, int cz, String... ores) {
        FakeChunk c = new FakeChunk(cx, cz);
        for (int i = 0; i < ores.length; i++) c.set((cx << 4) + i, 20, (cz << 4) + 1, ores[i]);
        return c.read();
    }

    @Test
    void familiesAndTotals() {
        OreIndex ix = new OreIndex(100);
        ix.put(chunk(0, 0, "iron_ore", "deepslate_iron_ore", "nickel_ore"));
        ix.put(chunk(1, 0, "diamond_ore"));
        Map<String, Integer> t = ix.totals("minecraft:overworld", 0, 0, 64);
        assertEquals(2, t.get("iron"));
        assertEquals(1, t.get("oritech:nickel"));
        assertEquals(1, t.get("diamond"));
        assertArrayEquals(new int[]{3, 4}, ix.familiesAndPositions());
    }

    @Test
    void nearestInsideTheAreasOutsideTheProtectMargin() {
        OreIndex ix = new OreIndex(100);
        ix.put(chunk(0, 0, "iron_ore", "iron_ore"));   // x 0 and 1, y 20, z 1
        ix.put(chunk(3, 0, "iron_ore"));               // x 48
        ix.put(chunk(-2, 0, "iron_ore"));              // x -32: outside the area
        OreIndex.Fence f = new OreIndex.Fence(List.of(new int[]{-10, -10, 100, 100}), List.of(new int[]{0, 0, 0, 2, 40, 2}), 4);
        List<OreIndex.Hit> h = ix.nearest("minecraft:overworld", id -> id.equals("minecraft:iron_ore"), 0, 20, 0, 96, 5, f);
        assertEquals(1, h.size(), "the two near the protect box and the one outside the area are left out: " + h);
        assertEquals(48, h.get(0).x());
        List<OreIndex.Hit> all = ix.nearest("minecraft:overworld", id -> id.endsWith("iron_ore"), 0, 20, 0, 96, 5, null);
        assertEquals(4, all.size());
        assertEquals(0, all.get(0).x());
        assertTrue(ix.nearest("minecraft:overworld", id -> true, 0, 20, 0, 10, 5, null).size() == 2, "within r only");
        assertTrue(ix.nearest("minecraft:the_nether", id -> true, 0, 20, 0, 96, 5, null).isEmpty());
    }

    @Test
    void capsDropTheOldestStaleFirst() {
        OreIndex ix = new OreIndex(3);
        ChunkSummary a = chunk(0, 0), b = chunk(1, 0), c = chunk(2, 0), d = chunk(3, 0);
        b.stale = true;
        ix.put(a);
        ix.put(b);
        ix.put(c);
        ix.put(d);
        assertEquals(3, ix.size("minecraft:overworld"));
        assertNull(ix.get("minecraft:overworld", 1, 0), "the stale one went first");
        assertNotNull(ix.get("minecraft:overworld", 0, 0));
        assertEquals(1, ix.evicted);
        // positions per family per chunk: 64 listed, the count exact
        FakeChunk big = new FakeChunk(5, 5);
        for (int i = 0; i < 100; i++) big.set(80 + (i % 16), 10 + i / 16, 81, "iron_ore");
        ChunkSummary s = big.read();
        assertEquals(100, s.ores.get("iron").n);
        assertEquals(64, s.ores.get("iron").spots.size());
    }

    @Test
    void rebuildFromFilesAllStaleAndAFreshReadWins(@TempDir Path dir) throws Exception {
        ChunkSummary a = chunk(0, 0, "iron_ore"), b = chunk(1, 1, "diamond_ore");
        a.seenMs = 10;
        b.seenMs = 20;
        Path folder = dir.resolve("sense").resolve(SummaryFiles.dimFolder(a.dim));
        io.github.mojolowjo.entropybot.io.BotFiles.writeAtomicAnywhere(SummaryFiles.file(dir, a), SummaryFiles.toJson(a));
        io.github.mojolowjo.entropybot.io.BotFiles.writeAtomicAnywhere(SummaryFiles.file(dir, b), SummaryFiles.toJson(b));
        OreIndex ix = new OreIndex(100);
        ChunkSummary live = chunk(1, 1);       // read this session: the diamond is gone, and it is fresher
        live.seenMs = 30;
        ix.put(live);
        SummaryFiles.Loaded l = SummaryFiles.loadDir(folder);
        assertEquals(2, l.list().size());
        assertEquals(1, ix.loadStale(l.list()));
        assertTrue(ix.get(a.dim, 0, 0).stale);
        assertFalse(ix.get(a.dim, 1, 1).stale);
        assertNull(ix.get(a.dim, 1, 1).ores.get("diamond"));
        assertEquals(1, ix.staleCount());
        List<OreIndex.Hit> h = ix.nearest(a.dim, id -> true, 0, 20, 0, 64, 5, null);
        assertTrue(h.get(0).stale());
        assertEquals(2, ix.chunksSeen(a.dim, 0, 0, 64, null));
    }
}
