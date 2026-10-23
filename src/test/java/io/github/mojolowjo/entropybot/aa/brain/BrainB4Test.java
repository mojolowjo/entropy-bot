package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRAIN_PLAN B4, one-planner: the resolver (ids, names, an ore block means what its loot drops, tiers and the worldgen band
 * of the blocks that drop an item) and the owner goals. No family words: "iron" is no item.
 */
class BrainB4Test {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());

    /** Block -> what its loot drops (the test's loot data). */
    static final Map<String, List<String>> DROPS = Map.of("minecraft:iron_ore", List.of("minecraft:raw_iron"),
            "minecraft:deepslate_iron_ore", List.of("minecraft:raw_iron"), "minecraft:diamond_ore", List.of("minecraft:diamond"),
            "minecraft:deepslate_diamond_ore", List.of("minecraft:diamond"), "minecraft:ancient_debris", List.of("minecraft:ancient_debris"),
            "mekanism:osmium_ore", List.of("mekanism:raw_osmium"), "mekanism:deepslate_osmium_ore", List.of("mekanism:raw_osmium"),
            "minecraft:stone", List.of("minecraft:cobblestone"));

    static Thing.Catalog cat(Map<String, Thing.Gen> gen) {
        List<String> blocks = List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:diamond_ore",
                "minecraft:deepslate_diamond_ore", "minecraft:ancient_debris", "minecraft:stone",
                "mekanism:osmium_ore", "mekanism:deepslate_osmium_ore", "minecraft:oak_log");
        List<String> items = List.of("minecraft:raw_iron", "minecraft:diamond", "minecraft:shield", "minecraft:oak_log", "mekanism:raw_osmium",
                "minecraft:ancient_debris", "minecraft:cobblestone");
        Map<String, String> names = new HashMap<>(Map.of("minecraft:shield", "shield", "minecraft:oak_log", "oak log",
                "mekanism:osmium_ore", "osmium ore", "minecraft:raw_iron", "raw iron"));
        Map<String, Integer> tiers = new HashMap<>(Map.of("minecraft:iron_ore", 2, "minecraft:deepslate_iron_ore", 2,
                "minecraft:diamond_ore", 3, "minecraft:deepslate_diamond_ore", 3, "minecraft:ancient_debris", 4,
                "mekanism:osmium_ore", 1, "mekanism:deepslate_osmium_ore", 1, "minecraft:stone", 1));
        return new Thing.Catalog(items, blocks, names, tiers, gen, b -> DROPS.getOrDefault(b, List.of()), item -> {
            List<String> out = new ArrayList<>();
            for (var e : DROPS.entrySet()) if (e.getValue().contains(item)) out.add(e.getKey());
            java.util.Collections.sort(out);
            return out;
        });
    }

    static final Map<String, Thing.Gen> GEN = Map.of(
            "minecraft:iron_ore", new Thing.Gen(Set.of(Thing.OVERWORLD), -24, 320), "minecraft:deepslate_iron_ore", new Thing.Gen(Set.of(Thing.OVERWORLD), -64, 8),
            "minecraft:diamond_ore", new Thing.Gen(Set.of(Thing.OVERWORLD), -64, 16), "minecraft:deepslate_diamond_ore", new Thing.Gen(Set.of(Thing.OVERWORLD), -64, 16),
            "minecraft:ancient_debris", new Thing.Gen(Set.of(Thing.NETHER), 8, 119),
            "mekanism:osmium_ore", new Thing.Gen(Set.of(Thing.OVERWORLD), -8, 72), "mekanism:deepslate_osmium_ore", new Thing.Gen(Set.of(Thing.OVERWORLD), -64, 0));

    @Test
    void anOreBlockMeansWhatItsLootDrops() {
        Thing.Result r = Thing.resolve("iron_ore", cat(GEN));
        assertNull(r.err());
        assertEquals("raw_iron", r.key());
        assertEquals(List.of("minecraft:raw_iron"), r.ids());
        assertTrue(r.note().contains("iron_ore drops raw_iron (the game's loot data)"), r.note());
        assertEquals(List.of("raw_iron"), r.counts());
        assertEquals(2, r.tier(), "the lowest tier among the generated blocks that drop it");
        assertTrue(r.ore());
        assertEquals(Thing.OVERWORLD, r.where().dim());
        assertEquals(-64, r.where().minY());
        assertEquals(320, r.where().maxY());
        assertEquals("from worldgen", r.where().source());
    }

    @Test
    void noFamilyWordsAnItemIsItsOwnId() {
        assertNotNull(Thing.resolve("iron", cat(GEN)).err(), "no item is called iron: no family word");
        assertNotNull(Thing.resolve("diamonds", cat(GEN)).err(), "no plural");
        Thing.Result d = Thing.resolve("diamond", cat(GEN));
        assertEquals(List.of("minecraft:diamond"), d.ids());
        assertTrue(d.ore(), "generated blocks drop it");
        assertEquals(3, d.tier());
        assertEquals(16, d.where().maxY());
        assertEquals("mekanism:raw_osmium", Thing.resolve("mekanism:raw_osmium", cat(GEN)).ids().get(0));
        assertEquals("ancient_debris", Thing.resolve("ancient_debris", cat(GEN)).key(), "a block that drops itself is itself");
        assertEquals(Thing.NETHER, Thing.resolve("ancient_debris", cat(GEN)).where().dim());
    }

    @Test
    void itemsNamesAndUnknowns() {
        Thing.Result s = Thing.resolve("shield", cat(null));
        assertFalse(s.ore());
        assertEquals("shield", s.key());
        assertEquals(List.of("shield"), s.counts());
        assertEquals("oak_log", Thing.resolve("oak log", cat(null)).key(), "a plain id with a space");
        assertEquals("raw_iron", Thing.resolve("raw iron", cat(null)).key(), "a display name");
        assertEquals("mekanism:raw_osmium", Thing.resolve("osmium ore", cat(null)).key(), "a display name of an ore block: its drop");
        assertNotNull(Thing.resolve("unobtainium", cat(null)).err());
        assertNotNull(Thing.resolve(" ", cat(null)).err());
    }

    @Test
    void whereIsTheWorldgenOfTheBlocksThatDropIt() {
        Thing.Where w = Thing.resolve("mekanism:raw_osmium", cat(GEN)).where();
        assertEquals("from worldgen", w.source());
        assertEquals(-64, w.minY());
        assertEquals(72, w.maxY());
        assertNull(Thing.resolve("mekanism:raw_osmium", cat(null)).where(), "no worldgen read (multiplayer): no band");
        assertFalse(Thing.resolve("mekanism:raw_osmium", cat(null)).ore());
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

    /** jobs (owner-watched 2026-10-22): a goal met halfway through a chop job: the job runs to its end, THEN the goal completes. */
    @Test
    void aGoalMetMidChopCompletesOnlyAtTheJobsEnd() {
        Goals.Goal g = Goals.add(bb, Thing.resolve("iron_ore", cat(GEN)), 2, 0, clock.now());
        var chop = new io.github.mojolowjo.entropybot.aa.moves.Step.Collect("raw_iron", 4);
        assertTrue(JobBoundary.holds(chop));
        assertFalse(JobBoundary.holds(new io.github.mojolowjo.entropybot.aa.moves.Step.Craft("torch", 4)), "a craft checks between batches");
        try {
            JobBoundary.enter(chop);
            io.github.mojolowjo.entropybot.aa.Replan.Timer timer = new io.github.mojolowjo.entropybot.aa.Replan.Timer();
            assertTrue(timer.due(chop.text(), 0), "the step's start is a boundary");
            for (int got = 1; got <= 4; got++) {
                bb.in.bag = Map.of("raw_iron", got);              // the count is met at 2, halfway
                assertTrue(Goals.done(bb).isEmpty(), "mid-job: no goal check at " + got);
                assertFalse(timer.due(chop.text(), 60_000L * got), "mid-job: no 5 s re-score");
            }
            assertEquals(1, Goals.of(bb).size(), "the goal is still there while the job runs");
            JobBoundary.leave();                                  // the job ended: the boundary
            assertEquals(1, Goals.done(bb).size());
            assertEquals(g.r.key(), Goals.of(bb).isEmpty() ? g.r.key() : "not done");
        } finally {
            JobBoundary.leave();
        }
    }

    @Test
    void goalsParseScoreAndDone() {
        Goals.Args a = Goals.parse("iron ore 5 high");
        assertEquals("iron_ore", a.word());
        assertEquals(5, a.n());
        assertEquals(1, a.prio());
        assertNotNull(Goals.parse("").err());

        Goals.Goal g = Goals.add(bb, Thing.resolve("iron_ore", cat(GEN)), 5, 0, clock.now());
        assertEquals(70, Goals.score(bb, g), 1e-9);
        assertEquals(90, Goals.score(bb, Goals.add(bb, g.r, 5, 1, clock.now())), 1e-9, "high");
        assertEquals(1, Goals.of(bb).size(), "the same key replaces");
        g = Goals.of(bb).get(0);
        assertNull(Goals.prereq(bb, g), "no prerequisite desire: the chain makes the pickaxe");
        assertNull(Goals.steps(bb, g), "no world facts yet: no chain");
        bb.in.bag = Map.of("raw_iron", 2);
        assertTrue(Goals.done(bb).isEmpty());
        bb.in.bag = Map.of("raw_iron", 5);
        assertEquals(-1, Goals.score(bb, g), 1e-9);
        assertEquals(1, Goals.done(bb).size());
        assertTrue(Goals.of(bb).isEmpty());
    }

    @Test
    void blockedByDimensionOnly() {
        Goals.Goal d = Goals.add(bb, Thing.resolve("ancient_debris", cat(GEN)), 0, 0, clock.now());
        assertEquals(8, d.n, "a generated block's default");
        assertTrue(Goals.blocked(bb, d).contains("only in the Nether"), Goals.blocked(bb, d));
        bb.in.dimension = Thing.NETHER;
        assertNull(Goals.blocked(bb, d), "no diamond-pickaxe park: the chain prices the pickaxe the dig asks for");
        Goals.Goal s = Goals.add(bb, Thing.resolve("shield", cat(null)), 0, -1, clock.now());
        assertEquals(1, s.n);
        assertEquals(50, Goals.score(bb, s), 1e-9, "low");
        assertTrue(Goals.line(bb, s, "score 50").startsWith("shield 0/1 low: score 50 - no facts for the chain yet"), Goals.line(bb, s, "score 50"));
    }
}
