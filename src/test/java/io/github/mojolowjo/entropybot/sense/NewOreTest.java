package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.memory.Limits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.36.a9 (to-do vanilla 1b): new ore reaches the index (a patch past the cap, the near-bot check's turns). */
class NewOreTest {
    @Test
    void placedOreShowsInTheIndexAfterAPatch() {
        FakeChunk c = new FakeChunk(0, 0);
        OreIndex idx = new OreIndex();
        ChunkSummary s = c.read();
        idx.put(s);
        List<ChunkDiff.Change> out = new ArrayList<>();
        assertTrue(SummaryBuilder.patch(s, 5, 60, 2, FakeChunk.cls("stone"), FakeChunk.cls("iron_ore"), 63, out, 1));
        assertEquals(1, idx.nearest("minecraft:overworld", id -> true, 5, 60, 2, 10, 5, null).size());
        assertEquals(1, idx.within("minecraft:overworld", 5, 60, 2, 10).get("iron").n());
    }

    @Test
    void aPatchListsNewOreEvenInAFullChunk() {
        FakeChunk c = new FakeChunk(0, 0);
        for (int i = 0; i < Limits.SENSE_SPOTS + 10; i++) c.set(i % 16, 5 + i / 16, 3, "coal_ore");
        ChunkSummary s = c.read();
        ChunkSummary.Group g = s.ores.get("coal");
        assertEquals(Limits.SENSE_SPOTS + 10, g.n);
        assertEquals(Limits.SENSE_SPOTS, g.spots.size(), "a full read lists the cap only");
        List<ChunkDiff.Change> out = new ArrayList<>();
        SummaryBuilder.patch(s, 7, 100, 7, FakeChunk.cls("stone"), FakeChunk.cls("coal_ore"), 100, out, 1);
        assertEquals(Limits.SENSE_SPOTS + 11, g.n);
        assertTrue(g.spots.stream().anyMatch(p -> p.at(7, 100, 7)), "the placed coal is listed");
        // a patch is listed only up to the extra room, but always counted
        for (int i = 0; i < ChunkSummary.Group.PATCH_EXTRA + 5; i++)
            SummaryBuilder.patch(s, i % 16, 110, 9 + i / 16, FakeChunk.cls("stone"), FakeChunk.cls("coal_ore"), 111, out, 1);
        assertEquals(Limits.SENSE_SPOTS + ChunkSummary.Group.PATCH_EXTRA, g.spots.size());
        assertEquals(Limits.SENSE_SPOTS + 11 + ChunkSummary.Group.PATCH_EXTRA + 5, g.n);
    }

    @Test
    void aCappedGroupIsNotACountDisagreementBecauseOfTheExtraRoom() {
        FakeChunk c = new FakeChunk(0, 0);
        for (int i = 0; i < Limits.SENSE_SPOTS + 10; i++) c.set(i % 16, 5 + i / 16, 3, "coal_ore");
        ChunkSummary s = c.read();
        c.set(7, 100, 7, "coal_ore");
        SummaryBuilder.patch(s, 7, 100, 7, FakeChunk.cls("stone"), FakeChunk.cls("coal_ore"), 100, new ArrayList<>(), 1);
        assertTrue(ChunkDiff.disagreements(s, c.read()).isEmpty(), "same count, lists not whole: the near check leaves it");
    }

    @Test
    void aMissedPatchIsADisagreementTheNearCheckFixes() {
        FakeChunk c = new FakeChunk(0, 0);
        ChunkSummary s = c.read();
        c.set(5, 60, 2, "iron_ore");                         // the hook never told the pass
        List<String> diff = ChunkDiff.disagreements(s, c.read());
        assertFalse(diff.isEmpty());
        assertTrue(diff.get(0).startsWith("ores.iron count"));
        OreIndex idx = new OreIndex();
        idx.put(s);
        assertTrue(idx.nearest("minecraft:overworld", id -> true, 5, 60, 2, 10, 5, null).isEmpty());
        idx.put(c.read());                                   // what the near check does with a disagreement
        assertEquals(1, idx.nearest("minecraft:overworld", id -> true, 5, 60, 2, 10, 5, null).size());
    }

    @Test
    void theNearCheckTakesTheNineChunksRoundTheBotInTurn() {
        Set<String> seen = new HashSet<>();
        for (long i = 0; i < 9; i++) {
            int[] c = NearAudit.pick(10, -4, i);
            assertTrue(Math.abs(c[0] - 10) <= 1 && Math.abs(c[1] + 4) <= 1);
            seen.add(c[0] + "," + c[1]);
        }
        assertEquals(9, seen.size(), "all nine, none twice");
        assertArrayEquals(new int[]{10, -4}, NearAudit.pick(10, -4, 0), "the chunk under the bot first");
        assertArrayEquals(NearAudit.pick(10, -4, 3), NearAudit.pick(10, -4, 12), "then round again");
        assertArrayEquals(new int[]{10, -4}, NearAudit.pick(10, -4, 9));
    }
}

