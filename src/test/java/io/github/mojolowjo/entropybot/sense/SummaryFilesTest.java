package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummaryFilesTest {
    @Test
    void roundTrip() {
        FakeChunk c = new FakeChunk(-3, 4);
        c.set(-40, 10, 70, "iron_ore");
        c.set(-41, 30, 70, "spawner");
        for (int i = 0; i < 12; i++) c.set(-48 + i, 5, 66, "lava");
        ChunkSummary s = c.read();
        s.seenMs = 1234;
        ChunkSummary back = SummaryFiles.fromJson(SummaryFiles.toJson(s));
        assertEquals(s.dim, back.dim);
        assertEquals(-3, back.cx);
        assertEquals(4, back.cz);
        assertEquals(1234, back.seenMs);
        assertTrue(ChunkDiff.disagreements(s, back).isEmpty());
        assertTrue(ChunkDiff.between(s, back).isEmpty());
        assertEquals(1, back.lavaLakes().size());
        assertEquals("-3.4.json", SummaryFiles.fileName(-3, 4));
        assertEquals("minecraft_overworld", SummaryFiles.dimFolder("minecraft:overworld"));
    }

    @Test
    void aBrokenFileIsSkippedAndCounted(@TempDir Path dir) throws Exception {
        ChunkSummary s = new FakeChunk(0, 0).read();
        io.github.mojolowjo.entropybot.io.BotFiles.writeAtomicAnywhere(SummaryFiles.file(dir, s), SummaryFiles.toJson(s));
        Path folder = SummaryFiles.file(dir, s).getParent();
        Files.writeString(folder.resolve("1.1.json"), "{\"v\":1,\"dim\":\"x\"");
        Files.writeString(folder.resolve("2.2.json"), "{\"v\":1,\"dim\":\"x\",\"cx\":2,\"cz\":2,\"tops\":[1,2]}");
        Files.writeString(folder.resolve("3.3.json.tmp"), "junk");
        SummaryFiles.Loaded l = SummaryFiles.loadDir(folder);
        assertEquals(1, l.list().size());
        assertEquals(2, l.broken());
        assertNotNull(l.firstError());
        assertEquals(0, SummaryFiles.loadDir(dir.resolve("none")).list().size());
    }

    @Test
    void dirtyWritesAtMostEveryFiveSeconds() {
        SummaryFiles.Dirty d = new SummaryFiles.Dirty(5000);
        String k = SummaryFiles.Dirty.key("minecraft:overworld", 1, 2);
        d.mark(k, 0);
        assertEquals(List.of(k), d.due(0, 10));
        d.written(k, 0);
        d.mark(k, 100);
        assertTrue(d.due(4999, 10).isEmpty());
        assertEquals(List.of(k), d.due(5000, 10));
        d.failed(k, 5000);
        assertEquals(1, d.pending(), "a failed write stays due");
    }
}
