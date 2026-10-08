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

    @Test
    void switchRule() {
        AssistRules.Switcher s = new AssistRules.Switcher();
        AssistRules.Switcher.Phase p = s.update(Act.CHOPPING, 0);
        assertEquals(Act.CHOPPING, p.act());
        assertTrue(p.changed(), "the first counts at once");
        assertFalse(s.update(Act.CHOPPING, 1000).changed());
        assertEquals(Act.CHOPPING, s.update(Act.MINING, 2000).act(), "a new one waits 5 s");
        assertEquals(Act.CHOPPING, s.update(Act.MINING, 6000).act());
        p = s.update(Act.MINING, 7000);
        assertEquals(Act.MINING, p.act());
        assertTrue(p.changed());
        // a blip back does not switch, and restarts the wait
        s.update(Act.CHOPPING, 8000);
        assertEquals(Act.MINING, s.update(Act.MINING, 9000).act());
        assertEquals(Act.MINING, s.update(Act.CHOPPING, 10_000).act());
        assertEquals(Act.MINING, s.update(Act.CHOPPING, 14_000).act(), "4 s since the new start");
        assertEquals(Act.CHOPPING, s.update(Act.CHOPPING, 15_000).act());
        // idle: mirroring for 60 s, then only following, then back with the next activity
        s.update(Act.IDLE, 20_000);
        p = s.update(Act.IDLE, 25_000);
        assertEquals(Act.IDLE, p.act());
        assertTrue(p.mirroring());
        assertTrue(s.update(Act.IDLE, 84_000).mirroring());
        p = s.update(Act.IDLE, 85_000);
        assertFalse(p.mirroring(), "60 s idle");
        assertTrue(p.changed(), "the end of mirroring is a change (one whisper)");
        assertFalse(s.update(Act.IDLE, 90_000).changed());
        s.update(Act.MINING, 91_000);
        p = s.update(Act.MINING, 96_000);
        assertEquals(Act.MINING, p.act());
        assertTrue(p.mirroring());
    }

    /** A fake world: a map of positions, air elsewhere. */
    static final class FakeWorld implements AssistRules.World {
        final Map<String, String> m = new HashMap<>();

        void set(int x, int y, int z, String id) { m.put(x + "," + y + "," + z, id); }

        @Override public String id(int x, int y, int z) { return m.getOrDefault(x + "," + y + "," + z, "minecraft:air"); }

        /** An oak: trunk from y0 for h logs, a leaf blob over and around the top. */
        void tree(int x, int y0, int z, int h, String log) {
            for (int y = y0; y < y0 + h; y++) set(x, y, z, log);
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int dy = 0; dy <= 2; dy++)
                        if ((dx != 0 || dz != 0 || dy > 0) && !m.containsKey((x + dx) + "," + (y0 + h - 1 + dy) + "," + (z + dz)))
                            set(x + dx, y0 + h - 1 + dy, z + dz, "minecraft:oak_leaves");
        }
    }

    @Test
    void findsHalfCutTrees() {
        FakeWorld w = new FakeWorld();
        // the owner cut the bottom 2 logs of a tree at (0,64..65,0): logs 66..69 hang in the leaves
        w.tree(0, 64, 0, 6, "minecraft:oak_log");
        w.set(0, 64, 0, "minecraft:air");
        w.set(0, 65, 0, "minecraft:air");
        w.tree(3, 64, 2, 5, "minecraft:oak_log");                    // a whole tree nearby
        for (int y = 64; y < 68; y++) w.set(-3, y, 0, "minecraft:oak_log");    // a log wall: no leaves
        w.tree(2, 64, -3, 5, "minecraft:birch_log");                 // another kind
        w.tree(20, 64, 0, 5, "minecraft:oak_log");                   // too far
        List<AssistRules.Box> b = AssistRules.halfCutTrees(w, 0, 65, 0, "minecraft:oak_log");
        assertEquals(2, b.size(), "the half-cut one and the whole one: " + b);
        assertEquals(new AssistRules.Box(0, 66, 0, 0, 69, 0), b.get(0), "nearest first, its remaining trunk");
        assertEquals(new AssistRules.Box(3, 64, 2, 3, 68, 2), b.get(1));
        assertEquals("dig 0 66 0 0 69 0 then dig 3 64 2 3 68 2", AssistRules.chain(b, false));
        assertTrue(AssistRules.halfCutTrees(new FakeWorld(), 0, 64, 0, "minecraft:oak_log").isEmpty());
        assertNull(AssistRules.chain(List.of(), false));
    }

    @Test
    void minesTheSameKindNearby() {
        FakeWorld w = new FakeWorld();
        for (int x = -15; x <= 15; x++)
            for (int z = -15; z <= 15; z++)
                for (int y = 50; y <= 63; y++) w.set(x, y, z, "minecraft:stone");
        // the owner's hole at (0,63,0) and (0,62,0)
        w.set(0, 63, 0, "minecraft:air");
        w.set(0, 62, 0, "minecraft:air");
        w.set(1, 62, 0, "minecraft:iron_ore");
        List<AssistRules.Box> b = AssistRules.mineTargets(w, 0, 62, 0, "minecraft:stone");
        assertEquals(AssistRules.MINE_MAX, b.size());
        for (AssistRules.Box x : b) {
            assertEquals("minecraft:stone", w.id(x.x1(), x.y1(), x.z1()));
            assertTrue(Math.abs(x.x1()) <= 12 && Math.abs(x.z1()) <= 12);
        }
        AssistRules.Box first = b.get(0);
        assertEquals(1, first.x1() * first.x1() + (first.y1() - 62) * (first.y1() - 62) + first.z1() * first.z1(), "next to the hole first: " + first);
        assertTrue(b.contains(new AssistRules.Box(0, 61, 0, 0, 61, 0)), "the floor of the hole");
        List<AssistRules.Box> ore = AssistRules.mineTargets(w, 0, 62, 0, "minecraft:iron_ore");
        assertEquals(List.of(new AssistRules.Box(1, 62, 0, 1, 62, 0)), ore);
        assertEquals("dig 1 62 0 1 62 0 ores", AssistRules.chain(ore, true));
        assertTrue(AssistRules.mineTargets(w, 0, 62, 0, "minecraft:oak_planks").isEmpty(), "built blocks are never mirrored");
        assertTrue(AssistRules.mineTargets(w, 0, 62, 0, "minecraft:cobblestone").isEmpty());
    }

    @Test
    void giveRule() {
        Ev placed = new Ev("minecraft:cobblestone", 0, 64, 0, NOW - 2000, 5);
        assertEquals(32, AssistRules.give(placed, 200, Long.MIN_VALUE / 2, NOW));
        assertEquals(10, AssistRules.give(placed, 10, Long.MIN_VALUE / 2, NOW), "only what the keep rules leave");
        assertEquals(0, AssistRules.give(placed, 0, Long.MIN_VALUE / 2, NOW), "nothing giveable (tools, kept supplies...)");
        assertEquals(0, AssistRules.give(new Ev("minecraft:cobblestone", 0, 64, 0, NOW - 2000, 40), 200, Long.MIN_VALUE / 2, NOW), "the owner has plenty");
        assertEquals(32, AssistRules.give(new Ev("minecraft:cobblestone", 0, 64, 0, NOW - 2000, -1), 200, Long.MIN_VALUE / 2, NOW), "unknown counts as low");
        assertEquals(0, AssistRules.give(placed, 200, NOW - 10_000, NOW), "30 s between hand-overs");
        assertEquals(0, AssistRules.give(new Ev("minecraft:cobblestone", 0, 64, 0, NOW - 20_000, 1), 200, Long.MIN_VALUE / 2, NOW), "an old placement");
        assertEquals(0, AssistRules.give(null, 200, Long.MIN_VALUE / 2, NOW));
    }

    @Test
    void words() {
        assertEquals(AssistRules.Word.ON, AssistRules.word(""));
        assertEquals(AssistRules.Word.ON, AssistRules.word(" me "));
        assertEquals(AssistRules.Word.OFF, AssistRules.word("OFF"));
        assertEquals(AssistRules.Word.STATUS, AssistRules.word("status"));
        assertEquals(AssistRules.Word.ERROR, AssistRules.word("banana"));
        assertEquals("", AssistRules.fromBrainCopy("copy on"));
        assertEquals("off", AssistRules.fromBrainCopy("copy  off"));
        assertEquals("status", AssistRules.fromBrainCopy("copy"));
        assertNull(AssistRules.fromBrainCopy("on"));
        assertEquals("assist: cutting oak with you", AssistRules.text(Act.CHOPPING, true, "minecraft:oak_log"));
        assertEquals("assist: mining stone with you", AssistRules.text(Act.MINING, true, "minecraft:stone"));
        assertEquals("assist: you look idle - I just follow you", AssistRules.text(Act.IDLE, false, null));
        assertTrue(AssistRules.text(Act.BUILDING, true, "minecraft:cobblestone").contains("cobblestone"));
    }
}
