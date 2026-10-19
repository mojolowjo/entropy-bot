package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** BRAIN_PLAN B4: the resolver (ids, families, names, tiers, where) and the owner goals. */
class BrainB4Test {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());

    static Thing.Catalog cat(Map<String, Thing.Gen> gen) {
        List<String> blocks = List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:diamond_ore",
                "minecraft:deepslate_diamond_ore", "minecraft:nether_quartz_ore", "minecraft:ancient_debris", "minecraft:stone",
                "mekanism:osmium_ore", "mekanism:deepslate_osmium_ore", "oritech:nickel_ore", "other:nickel_ore",
                "deepmod:deepslate_zinc_ore", "endmod:end_stone_tin_ore", "mymod:mythril_ore", "minecraft:oak_log");
        List<String> items = List.of("minecraft:raw_iron", "minecraft:diamond", "minecraft:shield", "minecraft:oak_log", "mekanism:raw_osmium");
        Map<String, String> names = new HashMap<>(Map.of("minecraft:shield", "shield", "minecraft:oak_log", "oak log",
                "mekanism:osmium_ore", "osmium ore", "minecraft:raw_iron", "raw iron"));
        Map<String, Integer> tiers = new HashMap<>(Map.of("minecraft:iron_ore", 2, "minecraft:deepslate_iron_ore", 2,
                "minecraft:diamond_ore", 3, "minecraft:deepslate_diamond_ore", 3, "minecraft:ancient_debris", 4,
                "mekanism:osmium_ore", 1, "minecraft:stone", 1));
        return new Thing.Catalog(items, blocks, names, tiers, gen);
    }

    @Test
    void anOreIdTakesItsFamilyAndSaysSo() {
        Thing.Result r = Thing.resolve("iron_ore", cat(null));
        assertNull(r.err());
        assertTrue(r.ore());
        assertEquals("iron", r.key());
        assertEquals(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), r.ids());
        assertTrue(r.note().contains("both"), r.note());
        assertTrue(r.counts().containsAll(List.of("iron_ore", "deepslate_iron_ore", "raw_iron")), r.counts().toString());
        assertEquals(2, r.tier());
        assertEquals(Thing.OVERWORLD, r.where().dim());
        assertEquals(-64, r.where().minY());
        assertEquals("the vanilla table", r.where().source());
    }

    @Test
    void familyWordsPluralsRawDropsAndNamespaces() {
        assertEquals("diamond", Thing.resolve("diamonds", cat(null)).key());
        assertTrue(Thing.resolve("diamond", cat(null)).ore(), "an ore family wins over the plain item");
        assertEquals(3, Thing.resolve("diamond", cat(null)).tier());
        assertEquals("mekanism:osmium", Thing.resolve("raw_osmium", cat(null)).key());
        assertEquals("mekanism:osmium", Thing.resolve("Osmium", cat(null)).key());
        Thing.Result nickel = Thing.resolve("nickel", cat(null));
        assertEquals(2, nickel.ids().size());
        assertTrue(nickel.note().contains("take them all"), nickel.note());
        assertEquals(List.of("oritech:nickel_ore"), Thing.resolve("oritech:nickel", cat(null)).ids());
        assertEquals("ancient_debris", Thing.resolve("ancient_debris", cat(null)).key());
        assertEquals(Thing.NETHER, Thing.resolve("ancient_debris", cat(null)).where().dim());
        assertTrue(Thing.resolve("quartz", cat(null)).counts().contains("quartz"));
    }

    @Test
    void itemsNamesAndUnknowns() {
        Thing.Result s = Thing.resolve("shield", cat(null));
        assertFalse(s.ore());
        assertEquals("shield", s.key());
        assertEquals(List.of("shield"), s.counts());
        assertEquals("oak_log", Thing.resolve("oak log", cat(null)).key(), "a plain id with a space");
        assertEquals("mekanism:osmium", Thing.resolve("osmium ore", cat(null)).key(), "a display name of an ore: its family");
        assertNotNull(Thing.resolve("unobtainium", cat(null)).err());
        assertNotNull(Thing.resolve(" ", cat(null)).err());
    }

    @Test
    void whereFromWorldgenThenTableThenGuess() {
        Map<String, Thing.Gen> gen = Map.of("mekanism:osmium_ore", new Thing.Gen(Set.of(Thing.OVERWORLD), -8, 72),
                "mekanism:deepslate_osmium_ore", new Thing.Gen(Set.of(Thing.OVERWORLD), -64, 0));
        Thing.Where w = Thing.resolve("osmium", cat(gen)).where();
        assertEquals("from worldgen", w.source());
        assertEquals(-64, w.minY());
        assertEquals(72, w.maxY());
        Thing.Where u = Thing.resolve("mythril", cat(null)).where();
        assertFalse(u.yKnown());
        assertEquals(Thing.OVERWORLD, u.dim());
        assertEquals(-64, Thing.resolve("zinc", cat(null)).where().minY(), "deepslate only: deep");
        assertEquals(Thing.END, Thing.resolve("tin", cat(null)).where().dim());
        assertEquals(Thing.NETHER, Thing.resolve("nether_quartz_ore", cat(null)).where().dim());
        assertEquals(1, Thing.resolve("mythril", cat(null)).tier(), "unknown tier: a pickaxe");
    }

    @Test
    void tiersAndAnchors() {
        assertEquals(4, Thing.tier(true, true, true, true));
        assertEquals(3, Thing.tier(true, false, true, false));
        assertEquals(2, Thing.tier(true, true, false, false));
        assertEquals(1, Thing.tier(true, false, false, false));
        assertEquals(0, Thing.tier(false, false, false, false));
        assertEquals(-56, Thing.anchorY("above_bottom", 8, -64, 320));
        assertEquals(311, Thing.anchorY("below_top", 8, -64, 320));
        assertEquals(16, Thing.anchorY("absolute", 16, -64, 320));
    }

    @Test
    void goalsParseScoreChainAndDone() {
        Goals.Args a = Goals.parse("iron ore 5 high");
        assertEquals("iron_ore", a.word());
        assertEquals(5, a.n());
        assertEquals(1, a.prio());
        assertNotNull(Goals.parse("").err());

        Goals.Goal g = Goals.add(bb, Thing.resolve("iron_ore", cat(null)), 5, 0, clock.now());
        assertEquals(70, Goals.score(bb, g), 1e-9);
        assertEquals(90, Goals.score(bb, Goals.add(bb, g.r, 5, 1, clock.now())), 1e-9, "high");
        assertEquals(1, Goals.of(bb).size(), "the same key replaces");
        g = Goals.of(bb).get(0);

        bb.in.pickTier = 1;
        assertNull(Goals.chain(bb, g), "a stone pickaxe first");
        assertEquals("needs.tools", Goals.prereq(bb, g));
        bb.in.pickTier = 2;
        assertEquals("mine strip iron 5", Goals.chain(bb, g));
        bb.fact(Goals.KNOWN_FACT, Map.of("iron", 12));
        bb.in.bag = Map.of("raw_iron", 2, "deepslate_iron_ore", 1);
        assertEquals("mine iron 2 dig", Goals.chain(bb, g));
        assertTrue(Goals.done(bb).isEmpty());
        bb.in.bag = Map.of("raw_iron", 5);
        assertEquals(-1, Goals.score(bb, g), 1e-9);
        assertEquals(1, Goals.done(bb).size());
        assertTrue(Goals.of(bb).isEmpty());
    }

    @Test
    void blockedByDimensionAndDiamondTier() {
        Goals.Goal d = Goals.add(bb, Thing.resolve("ancient_debris", cat(null)), 0, 0, clock.now());
        assertEquals(8, d.n, "ore default");
        assertTrue(Goals.blocked(bb, d).contains("only in the Nether"), Goals.blocked(bb, d));
        bb.in.dimension = Thing.NETHER;
        assertTrue(Goals.blocked(bb, d).contains("diamond pickaxe"));
        Goals.Goal s = Goals.add(bb, Thing.resolve("shield", cat(null)), 0, -1, clock.now());
        assertEquals(1, s.n);
        assertEquals(50, Goals.score(bb, s), 1e-9, "low");
        bb.in.dimension = Thing.OVERWORLD;
        assertNull(Goals.blocked(bb, s));
        assertEquals("goal shield 1", Goals.chain(bb, s));
        assertTrue(Goals.line(bb, s, "score 50").startsWith("shield 0/1 low: score 50 - next: goal shield 1"), Goals.line(bb, s, "score 50"));
    }
}
