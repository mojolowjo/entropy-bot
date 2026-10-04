package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7d D1: the ores tallied for players. (B7e: the tests of a bridge job handing over to a trip of its own went with
 * the bridge; the mod's jobs end only through their own finish.)
 */
class B7dD1Test {
    @Test
    void theOreTally() {
        io.github.mojolowjo.entropybot.clear.OreTally.add(java.util.Map.of("minecraft:iron_ore", 2));
        var start = io.github.mojolowjo.entropybot.clear.OreTally.copy();
        io.github.mojolowjo.entropybot.clear.OreTally.add(java.util.Map.of("minecraft:iron_ore", 3, "minecraft:coal_ore", 1));
        assertEquals(3, io.github.mojolowjo.entropybot.clear.OreTally.since(start, id -> id.contains("iron")));
        assertEquals(4, io.github.mojolowjo.entropybot.clear.OreTally.since(start, id -> true));
    }
}
