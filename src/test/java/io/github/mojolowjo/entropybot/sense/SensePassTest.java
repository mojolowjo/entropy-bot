package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.28.0: the read and the patch over a fake section reader (the game side's McChunkReader feeds the same builder). */
class SensePassTest {
    @Test
    void stoneWithThreeIronAndASpawner() {
        FakeChunk c = new FakeChunk(2, -1);
        c.set(33, 12, -10, "iron_ore");
        c.set(34, 12, -10, "iron_ore");
        c.set(35, -20, -9, "deepslate_iron_ore");
        c.set(40, 30, -12, "spawner");
        ChunkSummary s = c.read();
        assertEquals(3, s.ores.get("iron").n);
        assertEquals(3, s.ores.get("iron").spots.size());
        assertEquals(1, s.pois.get("spawner").n);
        assertEquals(63, s.tops[ChunkSummary.index(33, -10)]);
        assertTrue(s.ores.keySet().equals(java.util.Set.of("iron")));

        // one iron mined: to air
        c.set(34, 12, -10, "air");
        List<ChunkDiff.Change> out = new ArrayList<>();
        assertTrue(SummaryBuilder.patch(s, 34, 12, -10, FakeChunk.cls("iron_ore"), FakeChunk.cls("air"), 63, out, 2000));
        assertEquals(2, s.ores.get("iron").n);
        assertFalse(s.ores.get("iron").spots.stream().anyMatch(p -> p.at(34, 12, -10)));
        assertEquals(List.of(new ChunkDiff.Change(ChunkDiff.Type.ORE_REMOVE, "iron", "minecraft:iron_ore", 34, 12, -10)), out);
        assertTrue(ChunkDiff.disagreements(s, c.read()).isEmpty(), "the patched summary matches a fresh read");
    }

    @Test
    void aPatchThatFindsNothingAsksForARescan() {
        FakeChunk c = new FakeChunk(0, 0);
        ChunkSummary s = c.read();
        List<ChunkDiff.Change> out = new ArrayList<>();
        assertFalse(SummaryBuilder.patch(s, 3, 10, 3, FakeChunk.cls("iron_ore"), FakeChunk.cls("stone"), 63, out, 1));
        assertTrue(out.isEmpty());
    }

    @Test
    void railsAboveTheMineshaftLineAndLavaLakes() {
        FakeChunk c = new FakeChunk(0, 0);
        c.set(1, 50, 1, "rail");
        c.set(1, 20, 1, "rail");
        for (int i = 0; i < 12; i++) c.set(i, 5, 2, "lava");
        ChunkSummary s = c.read();
        assertEquals(1, s.pois.get("mineshaft").n);
        assertEquals(1, s.lavaLakes().size());
        List<ChunkDiff.Change> out = new ArrayList<>();
        SummaryBuilder.patch(s, 0, 5, 2, FakeChunk.cls("lava"), FakeChunk.cls("stone"), 63, out, 1);
        assertEquals(ChunkDiff.Type.POI_REMOVE, out.get(0).type());
        assertEquals(ChunkSummary.LAVA_LAKE_KIND, out.get(0).key());
        assertTrue(s.lavaLakes().isEmpty());
    }

    @Test
    void moddedOreFamiliesUseOreSpec() {
        assertEquals("iron", io.github.mojolowjo.entropybot.strip.OreSpec.family("minecraft:deepslate_iron_ore"));
        assertEquals("oritech:nickel", io.github.mojolowjo.entropybot.strip.OreSpec.family("oritech:nickel_ore"));
        assertEquals("oritech:nickel", io.github.mojolowjo.entropybot.strip.OreSpec.family("oritech:deepslate_nickel_ore"));
        assertEquals("ancient_debris", io.github.mojolowjo.entropybot.strip.OreSpec.family("minecraft:ancient_debris"));
    }
}
