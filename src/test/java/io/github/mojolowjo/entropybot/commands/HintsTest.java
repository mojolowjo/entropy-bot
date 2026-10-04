package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.storage.StorageRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** B7e N item 2: replies that name the command that fixes them. */
class HintsTest {
    @Test
    void nextAppendsOnce() {
        assertEquals("error: x - next: deposit", Hints.next("error: x", "deposit"));
        assertEquals("error: x - PM \"deposit\"", Hints.next("error: x - PM \"deposit\"", "deposit"), "already named");
        assertEquals("error: x", Hints.next("error: x", null));
        assertNull(Hints.next(null, "deposit"));
    }

    @Test
    void unknownWordsSuggest() {
        assertEquals("unknown command \"minee\" - did you mean mine? (help mine)", Hints.unknown("minee"));
        assertEquals("unknown command \"qqqqqq\" - next: help", Hints.unknown("qqqqqq"));
        assertTrue(Texts.stepFailed(Hints.unknown("minee")), "still a failed step for a chain");
    }

    @Test
    void fixes() {
        assertEquals("craft stone_pickaxe 3", Hints.pickaxeFix("stone"));
        assertEquals("craft stone_pickaxe 3", Hints.pickaxeFix(null));
        assertEquals("get iron_pickaxe 1 (or craft iron_pickaxe)", Hints.pickaxeFix("iron"));
        assertTrue(Hints.placeFix("base").startsWith("setbase"));
        assertTrue(Hints.placeFix("mine").startsWith("mark mine"));
        assertEquals("places", Hints.placeFix("farm"));
    }

    @Test
    void auditedRepliesEndWithTheirFix() {
        assertTrue(StorageRules.depositPlan(Map.of("minecraft:dirt", 3), List.of(), new int[3], "").err().endsWith("- next: scan base"));
        assertTrue(StorageRules.resolveSpot("base", Map.of(), "minecraft:overworld").err().endsWith("- next: setbase (standing at the base)"));
        assertTrue(io.github.mojolowjo.entropybot.farm.Compact.NO_TABLE.contains("next: craft crafting_table"));
    }
}
