package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ColumnPatchTest {
    @Test
    void aBlockPlacedAboveChangesOnlyThatColumnsTop() {
        FakeChunk c = new FakeChunk(0, 0);
        ChunkSummary s = c.read();
        int[] before = s.tops.clone();
        c.set(5, 70, 6, "stone");
        int fresh = ColumnPatch.next(s.tops[ChunkSummary.index(5, 6)], 70, true, y -> !c.at(5, y, 6).equals("air"), -64);
        assertEquals(70, fresh);
        assertEquals(c.top(5, 6), fresh);
        List<ChunkDiff.Change> out = new ArrayList<>();
        SummaryBuilder.patch(s, 5, 70, 6, FakeChunk.cls("air"), FakeChunk.cls("stone"), fresh, out, 1);
        assertEquals(List.of(new ChunkDiff.Change(ChunkDiff.Type.TOP, null, null, 5, 70, 6)), out);
        for (int i = 0; i < 256; i++) if (i != ChunkSummary.index(5, 6)) assertEquals(before[i], s.tops[i]);
        assertTrue(ColumnPatch.nearTop(s.tops, 5, 69, 6));
        assertFalse(ColumnPatch.nearTop(s.tops, 5, 60, 6));
    }

    @Test
    void theTopRemovedFallsToTheNextBlock() {
        FakeChunk c = new FakeChunk(0, 0);
        c.set(2, 66, 2, "stone");
        ChunkSummary s = c.read();
        c.set(2, 66, 2, "air");
        int fresh = ColumnPatch.next(66, 66, false, y -> !c.at(2, y, 2).equals("air"), -64);
        assertEquals(63, fresh);
        assertTrue(ColumnPatch.apply(s.tops, 2, 2, fresh));
        assertEquals(63, s.tops[ChunkSummary.index(2, 2)]);
        assertEquals(63, s.tops[ChunkSummary.index(3, 2)]);
        assertTrue(ChunkDiff.disagreements(s, c.read()).isEmpty());
        assertEquals(63, ColumnPatch.next(63, 40, false, y -> true, -64), "a block removed below the top: no move");
    }
}
