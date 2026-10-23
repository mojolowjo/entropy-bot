package io.github.mojolowjo.entropybot.assist;

import io.github.mojolowjo.entropybot.assist.AssistRules.Act;
import io.github.mojolowjo.entropybot.assist.AssistRules.Ev;
import io.github.mojolowjo.entropybot.assist.AssistRules.Report;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.24.2 assist: parsing, the action guess, the switch rule, the half-cut tree finder, the mining targets, the give rule. */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class AssistRulesTest {
    static final long NOW = 2_000_000;

    static Report rep(String held, String action, Ev broke, Ev placed) {
        return new Report("mojolowjo", 0, 64, 0, "minecraft:overworld", NOW, held, action, broke, placed);
    }

    static Ev ev(String id, long ago) { return new Ev(id, 1, 64, 1, NOW - ago, -1); }

    @Test
    void parsesTheReport() {
        String j = "{\"name\":\"mojolowjo\",\"x\":10.7,\"y\":64,\"z\":-3.2,\"dim\":\"minecraft:overworld\",\"received\":5000,\"held\":\"minecraft:iron_axe\","
                + "\"broke\":{\"id\":\"minecraft:oak_log\",\"x\":3,\"y\":70,\"z\":-4,\"age\":1500},"
                + "\"placed\":{\"id\":\"minecraft:cobblestone\",\"x\":4,\"y\":64,\"z\":-4,\"age\":200,\"left\":7},\"action\":\"chopping\"}";
        Report r = AssistRules.parse(j);
        assertNotNull(r);
        assertEquals(10, r.x());
        assertEquals(-4, r.z());
        assertEquals("chopping", r.action());
        assertEquals(3500, r.broke().at(), "at = received - age");
        assertEquals(70, r.broke().y());
        assertEquals(7, r.placed().left());
        assertEquals(-1, AssistRules.parse(j.replace(",\"left\":7", "")).placed().left(), "left unknown");
        Report old = AssistRules.parse("{\"name\":\"a\",\"x\":0,\"y\":0,\"z\":0,\"dim\":\"d\",\"received\":1}");
        assertNull(old.broke());
        assertNull(old.action());
        assertEquals("", old.held());
        assertNull(AssistRules.parse("not json"));
        assertNull(AssistRules.parse("{\"name\":\"a\"}"));
    }

    @Test
    void guessesTheActivity() {
        assertEquals(Act.MINING, AssistRules.guess(rep("", "mining", null, null), NOW), "the report's own word wins");
        assertEquals(Act.CHOPPING, AssistRules.guess(rep("", "dancing", ev("minecraft:oak_log", 1000), null), NOW), "an unknown word: own guess");
        assertEquals(Act.CHOPPING, AssistRules.guess(rep("minecraft:iron_axe", null, ev("minecraft:spruce_log", 1000), null), NOW));
        assertEquals(Act.MINING, AssistRules.guess(rep("", null, ev("minecraft:stone", 1000), null), NOW));
        assertEquals(Act.MINING, AssistRules.guess(rep("", null, ev("minecraft:deepslate_iron_ore", 1000), null), NOW));
        assertEquals(Act.FARMING, AssistRules.guess(rep("", null, ev("minecraft:wheat", 1000), null), NOW));
        assertEquals(Act.FARMING, AssistRules.guess(rep("", null, null, ev("minecraft:wheat_seeds", 1000)), NOW));
        assertEquals(Act.BUILDING, AssistRules.guess(rep("", null, ev("minecraft:stone", 4000), ev("minecraft:cobblestone", 1000)), NOW), "the newer event");
        assertEquals(Act.FIGHTING, AssistRules.guess(rep("minecraft:diamond_sword", null, null, null), NOW));
        assertEquals(Act.IDLE, AssistRules.guess(rep("minecraft:iron_pickaxe", null, ev("minecraft:stone", 11_000), null), NOW), "older than 10 s");
    }
}
