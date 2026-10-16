package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.io.BotFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EmptiedCorpsesTest {
    @TempDir
    Path root;

    @Test
    void loadsPerWorldAndClearsOnSwitch() throws Exception {
        UUID u = UUID.randomUUID();
        Files.createDirectories(root.resolve("worlds/a"));
        Files.writeString(root.resolve("worlds/a/corpses.json"), "{\"emptied\":[\"" + u + "\"]}");
        BotFiles f = new BotFiles(root);
        f.setWorld(root.resolve("worlds/a"));
        assertTrue(EmptiedCorpses.load(f).contains("1 emptied"));
        assertTrue(EmptiedCorpses.SET.contains(u));
        f.setWorld(root.resolve("worlds/b"));
        assertTrue(EmptiedCorpses.load(f).contains("none yet"));
        assertFalse(EmptiedCorpses.SET.contains(u));
        Files.createDirectories(root.resolve("worlds/b"));
        Files.writeString(root.resolve("worlds/b/corpses.json"), "{broken");
        assertTrue(EmptiedCorpses.load(f).contains("unreadable"));
        assertNull(EmptiedCorpses.mark(u, f));
        assertTrue(Files.readString(root.resolve("worlds/b/corpses.json")).contains(u.toString()));
        // a mark with no folder yet joins the next load
        UUID v = UUID.randomUUID();
        assertNull(EmptiedCorpses.mark(v, null));
        assertTrue(EmptiedCorpses.load(f).contains("1 marked before the load joined"));
        assertTrue(EmptiedCorpses.SET.contains(v) && EmptiedCorpses.SET.contains(u));
        assertTrue(Files.readString(root.resolve("worlds/b/corpses.json")).contains(v.toString()));
        // over MAX in the file: trimmed at load
        StringBuilder sb = new StringBuilder("{\"emptied\":[");
        for (int i = 0; i < EmptiedCorpses.MAX + 5; i++) sb.append(i > 0 ? "," : "").append("\"").append(UUID.randomUUID()).append("\"");
        Files.writeString(root.resolve("worlds/b/corpses.json"), sb.append("]}").toString());
        assertTrue(EmptiedCorpses.load(f).contains("5 oldest dropped"));
        assertEquals(EmptiedCorpses.MAX, EmptiedCorpses.SET.size());
    }
}
