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
        assertTrue(Storage.loadEmptied(f).contains("1 emptied"));
        assertTrue(Storage.EMPTIED.contains(u));
        f.setWorld(root.resolve("worlds/b"));
        assertTrue(Storage.loadEmptied(f).contains("none yet"));
        assertFalse(Storage.EMPTIED.contains(u));
        Files.createDirectories(root.resolve("worlds/b"));
        Files.writeString(root.resolve("worlds/b/corpses.json"), "{broken");
        assertTrue(Storage.loadEmptied(f).contains("unreadable"));
    }
}
