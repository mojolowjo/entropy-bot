package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.io.BotFiles;
import io.github.mojolowjo.entropybot.io.WorldState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class JsonStoreSplitTest {
    @TempDir
    Path root;

    @Test
    void worldKeysFollowTheWorldAndLegacyGoesToTheMainServer() throws Exception {
        // a 0.30 commands.json: global routines plus a world-bound home
        Files.writeString(root.resolve("commands.json"), "{\"routines\":{\"a\":1},\"home\":{\"x\":5},\"mines\":[1]}");
        AtomicBoolean main = new AtomicBoolean(false);
        JsonStore s = new JsonStore("commands.json", WorldState.COMMANDS_WORLD, WorldState.COMMANDS_WORLD_KEYS, main::get);
        BotFiles f = new BotFiles(root);
        f.setWorld(root.resolve("worlds/sp1"));
        s.load(f);
        assertFalse(s.data().has("home"));                    // a singleplayer world starts without the server's home
        assertTrue(s.data().has("routines"));
        s.data().add("home", JsonParser.parseString("{\"x\":9}"));
        assertTrue(s.flush().startsWith("ok"));
        String rootText = Files.readString(root.resolve("commands.json"));
        assertTrue(rootText.contains(JsonStore.LEGACY) && rootText.contains("\"x\":5"), rootText);   // the server's home kept
        assertFalse(rootText.contains("\"x\":9"), rootText);
        assertTrue(Files.readString(root.resolve("worlds/sp1/commands-world.json")).contains("\"x\":9"));
        // the main server: takes the legacy keys once
        f.setWorld(root.resolve("worlds/main"));
        main.set(true);
        String note = s.loadWorldPart(f);
        assertTrue(note.contains("took 2 keys"), note);
        assertEquals(5, s.data().getAsJsonObject("home").get("x").getAsInt());
        s.flush();
        assertFalse(Files.readString(root.resolve("commands.json")).contains(JsonStore.LEGACY));
        // back to sp1: its own home again, same object (in place)
        var data = s.data();
        f.setWorld(root.resolve("worlds/sp1"));
        s.loadWorldPart(f);
        assertSame(data, s.data());
        assertEquals(9, s.data().getAsJsonObject("home").get("x").getAsInt());
        assertFalse(s.data().has("mines"));
    }
}
