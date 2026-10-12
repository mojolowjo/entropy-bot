package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkDiffTest {
    private static List<ChunkDiff.Change> of(List<ChunkDiff.Change> all, ChunkDiff.Type t) {
        return all.stream().filter(c -> c.type() == t).toList();
    }

    @Test
    void everythingIsNewAgainstNothing() {
        FakeChunk c = new FakeChunk(0, 0);
        c.set(1, 10, 1, "iron_ore");
        c.set(2, 30, 2, "spawner");
        List<ChunkDiff.Change> d = ChunkDiff.between(null, c.read());
        assertEquals(1, of(d, ChunkDiff.Type.ORE_ADD).size());
        assertEquals(1, of(d, ChunkDiff.Type.POI_ADD).size());
        assertEquals(256, of(d, ChunkDiff.Type.TOP).size());
    }

    @Test
    void addRemoveAndMoveOfOresAndPois() {
        FakeChunk c = new FakeChunk(0, 0);
        c.set(1, 10, 1, "iron_ore");
        c.set(2, 30, 2, "spawner");
        c.set(3, 11, 3, "diamond_ore");
        ChunkSummary a = c.read();
        c.set(1, 10, 1, "stone");         // iron moved
        c.set(5, 12, 5, "iron_ore");
        c.set(2, 30, 2, "stone");         // spawner gone
        c.set(7, 20, 7, "nickel_ore");    // modded ore added
        ChunkSummary b = c.read();
        List<ChunkDiff.Change> d = ChunkDiff.between(a, b);
        assertTrue(d.contains(new ChunkDiff.Change(ChunkDiff.Type.ORE_REMOVE, "iron", "minecraft:iron_ore", 1, 10, 1)));
        assertTrue(d.contains(new ChunkDiff.Change(ChunkDiff.Type.ORE_ADD, "iron", "minecraft:iron_ore", 5, 12, 5)));
        assertTrue(d.contains(new ChunkDiff.Change(ChunkDiff.Type.ORE_ADD, "oritech:nickel", "oritech:nickel_ore", 7, 20, 7)));
        assertTrue(d.contains(new ChunkDiff.Change(ChunkDiff.Type.POI_REMOVE, "spawner", "minecraft:spawner", 2, 30, 2)));
        assertFalse(d.stream().anyMatch(x -> "diamond".equals(x.key()) || "diamonds".equals(x.key())), "unchanged diamond: no change");
        assertTrue(of(d, ChunkDiff.Type.TOP).isEmpty());
        assertEquals(4, d.size());
    }

    @Test
    void disagreementsNameTheField() {
        FakeChunk c = new FakeChunk(0, 0);
        c.set(1, 10, 1, "iron_ore");
        ChunkSummary a = c.read();
        c.set(1, 10, 1, "stone");
        c.set(4, 70, 4, "stone");
        List<String> d = ChunkDiff.disagreements(a, c.read());
        assertTrue(d.contains("ores.iron count 1 vs 0"), d.toString());
        assertTrue(d.contains("tops 1 column"), d.toString());
        assertTrue(ChunkDiff.disagreements(a, a.copy()).isEmpty());
    }
}
