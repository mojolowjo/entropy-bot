package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.commands.JsonStore;
import io.github.mojolowjo.entropybot.io.BotFiles;
import io.github.mojolowjo.entropybot.io.WorldState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** live-facts (0.39.y3): AA's switch is the world's: two world keys hold independent switches; a new world starts off, no only. */
class AaSwitchTest {
    @TempDir
    Path root;

    @Test
    void twoWorldKeysHoldIndependentSwitches() throws Exception {
        // a 0.39.y2 commands.json: the switch in the bot-wide "aa" object, on
        Files.writeString(root.resolve("commands.json"), "{\"aa\":{\"on\":true,\"since\":5,\"fair\":true}}");
        JsonStore s = new JsonStore("commands.json", WorldState.COMMANDS_WORLD, WorldState.COMMANDS_WORLD_KEYS, () -> false);
        BotFiles f = new BotFiles(root);
        f.setWorld(root.resolve("worlds/a"));
        s.load(f);
        assertEquals(java.util.List.of("on", "since"), AaSwitch.dropOldGlobal(s.data().getAsJsonObject("aa")));
        assertTrue(s.data().getAsJsonObject("aa").get("fair").getAsBoolean(), "the bot-wide settings stay");
        assertFalse(AaSwitch.on(AaSwitch.of(s.data())), "world a starts off");

        AaSwitch.turnOn(AaSwitch.of(s.data()), 100);
        AaSwitch.only(AaSwitch.of(s.data()), "torches");
        AaSwitch.shelterBuilt(AaSwitch.of(s.data()), true);
        assertTrue(s.flush().startsWith("ok"));
        assertFalse(Files.readString(root.resolve("commands.json")).contains(AaSwitch.KEY), "the switch is not in the root file");

        // a fresh world b: off, no only, no shelter
        f.setWorld(root.resolve("worlds/b"));
        s.loadWorldPart(f);
        JsonObject b = AaSwitch.of(s.data());
        assertFalse(AaSwitch.on(b));
        assertNull(AaSwitch.only(b));
        assertFalse(AaSwitch.shelterBuilt(b));
        AaSwitch.hold(b, 77);
        assertTrue(s.flush().startsWith("ok"));

        // back to a: its own switch again
        f.setWorld(root.resolve("worlds/a"));
        s.loadWorldPart(f);
        JsonObject a = AaSwitch.of(s.data());
        assertTrue(AaSwitch.on(a));
        assertEquals("torches", AaSwitch.only(a));
        assertTrue(AaSwitch.shelterBuilt(a));
        assertEquals(0, AaSwitch.heldUntil(a), "b's hold stays in b");

        // and b still off, held
        f.setWorld(root.resolve("worlds/b"));
        s.loadWorldPart(f);
        assertFalse(AaSwitch.on(AaSwitch.of(s.data())));
        assertEquals(77, AaSwitch.heldUntil(AaSwitch.of(s.data())));
    }
}
