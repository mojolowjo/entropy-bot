package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B7e (E1): "b <baritone command>": owner only, the bridge's replies. */
class BaritoneVerbTest {

    @Test
    void ownerOnly() {
        List<String> ran = new ArrayList<>();
        assertEquals("only mojolowjo can send raw Baritone commands", BaritoneVerb.reply("set allowBreak true", false, "mojolowjo", t -> ran.add(t)));
        assertTrue(ran.isEmpty(), "a guest's command never reaches Baritone");
    }

    @Test
    void repliesAsTheBridge() {
        List<String> ran = new ArrayList<>();
        assertEquals("ok (check chat for Baritone errors)", BaritoneVerb.reply("set allowSprint true", true, "o", t -> ran.add(t)));
        assertEquals(List.of("set allowSprint true"), ran);
        assertEquals("error: baritone rejected command", BaritoneVerb.reply("nonsense", true, "o", t -> false));
        assertEquals("error: baritone not loaded", BaritoneVerb.reply("goto 1 2 3", true, "o", t -> { throw new IllegalStateException("baritone not loaded"); }));
    }

    @Test
    void usageAndThePrefix() {
        assertEquals(BaritoneVerb.USAGE, BaritoneVerb.reply("  ", true, "o", t -> true));
        assertEquals(BaritoneVerb.USAGE, BaritoneVerb.reply(null, true, "o", t -> true));
        List<String> ran = new ArrayList<>();
        BaritoneVerb.reply("#goto 1 2 3", true, "o", t -> ran.add(t));
        assertEquals(List.of("goto 1 2 3"), ran, "a typed # prefix is dropped (the command manager wants none)");
    }
}
