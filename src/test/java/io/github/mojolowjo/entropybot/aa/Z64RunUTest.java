package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z64 item 2 (run U on z62, 2026-10-25 06:08:07-06:10:19, docs/runs/runU-z62): a fresh stone pickaxe (131 uses) broke
 * after 67 strip cells - the fork digs the feet and the head cell, two blocks a cell - while the chain's wear counted one
 * block a cell, so the spares made on the surface ran out at y 16 and the next pickaxe needed wood that was up there.
 */
class Z64RunUTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.clear();
    }

    /**
     * Hand numbers: strip.maxBlocksPerOre 22 -> 3 ores x 22 = 66 cells; wear 2 x 66 + 3 = 135 > 131 uses: one spare stone
     * pickaxe, crafted before the strip (on the surface) from the bag's cobblestone and sticks. One block a cell (69) would
     * have made none - run U's east tunnel.
     */
    @Test
    void aStripCellWearsThePickaxeTwice() {
        ScenariosTest.world("wooden", "stone");
        Tuning.set("strip.maxBlocksPerOre", 22);
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(bag, Z62RunSTest.band("east")).withUses(id -> 131));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.why().contains("= 66 blocks"), c.why());
        assertEquals(1, Z62RunSTest.crafted(c, "stone_pickaxe"), c.steps().toString());
        int craft = -1, strip = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            if (c.steps().get(i) instanceof Step.Craft k && k.item().equals("stone_pickaxe")) craft = i;
            if (c.steps().get(i) instanceof Step.Strip && strip < 0) strip = i;
        }
        assertTrue(craft >= 0 && craft < strip, "the spare (and its sticks) before the strip: " + c.steps());
        // 135 uses cover 2 x 66 + 3 exactly: none
        assertEquals(0, Z62RunSTest.crafted(NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(bag, Z62RunSTest.band("east")).withUses(id -> 135)), "stone_pickaxe"));
        assertEquals(2, baritone.api.process.IStripProcess.TUNNEL_HEIGHT);
    }

    // ---- item 4: the strip's yield as a fact of the area ----

    /** Hand numbers: 37.1 ore a chunk in 16 layers = 0.00906 a face; 969 faces, 0 iron, K 200 -> (1.81 + 0) / 1169 = 0.00155 -> x 5.85. */
    @Test
    void theAreasFacesScaleTheBandsEstimate() {
        double p = io.github.mojolowjo.entropybot.aa.brain.StripAreas.perFace(37.1);
        assertEquals(37.1 / 4096, p, 1e-12);
        var none = io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield.NONE;
        assertEquals(1.0, io.github.mojolowjo.entropybot.aa.brain.StripAreas.factor(p, none));
        double f = io.github.mojolowjo.entropybot.aa.brain.StripAreas.factor(p, new io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield(969, 0, 2));
        assertEquals(5.85, f, 0.01);
        // a rich area prices cheaper than the band (more ore than expected on its faces)
        assertTrue(io.github.mojolowjo.entropybot.aa.brain.StripAreas.factor(p, new io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield(969, 30, 2)) < 1);
        // facts by area: the same square counts once along a heading, other ores are not the wanted ones
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.add(-220, 16, 33, 500, Map.of("minecraft:copper_ore", 7));
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.add(-219, 16, 33, 0, Map.of("minecraft:iron_ore", 1));
        var y = io.github.mojolowjo.entropybot.aa.brain.StripAreas.along(-220, 16, 33, "east", Z62RunSTest.IRON);
        assertEquals(new io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield(500, 1, 1), y);
        assertEquals(io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield.NONE,
                io.github.mojolowjo.entropybot.aa.brain.StripAreas.along(-220, 17, 33, "east", Z62RunSTest.IRON), "another Y is another area");
    }

    /**
     * Run U: south, west and north used up at the origin (-220 16 33), the faces seen there 969 with 0 iron. The fourth heading
     * at the origin (east) runs through those areas: band 110 blocks/ore x 5.85 = ~643 -> 1929 cells for 3 ore; a strip from
     * fresh ground 48 m away (unseen areas: the band's 110 -> 332 cells) plus the walk there is cheaper and wins. No facts:
     * no fresh start is offered and the origin's heading is the way, as before.
     */
    @Test
    void aFreshStartWinsOverAFourthHeadingInASeenEmptyArea() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Map<String, String> used = Map.of(NeedChain.headingKey(16, "south"), "run U", NeedChain.headingKey(16, "west"), "run U",
                NeedChain.headingKey(16, "north"), "run U");
        NeedChain.World w = Z62RunSTest.world(bag, Z62RunSTest.band("east")).withoutSources(used);
        Chain plain = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(plain.ok(), plain.why());
        assertNull(Z62RunSTest.strip(plain).from(), "no area facts: the origin's heading");
        assertEquals("east", Z62RunSTest.strip(plain).heading());
        assertFalse(plain.why().contains("fresh ground"), plain.why());

        io.github.mojolowjo.entropybot.aa.brain.StripAreas.add(-220, 16, 33, 500, Map.of("minecraft:copper_ore", 7));
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.add(-205, 16, 33, 469, Map.of("minecraft:coal_ore", 6));
        Tuning.set("strip.areaMinFaces", 100000);                // the origin alone: what the fourth heading costs now
        Chain origin = NeedChain.resolve("raw_iron", 3, w);
        assertNull(Z62RunSTest.strip(origin).from());
        assertTrue(origin.why().contains("969 faces seen, 0 ore on them"), origin.why());
        Tuning.reset();
        Chain c = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        Step.Strip st = Z62RunSTest.strip(c);
        assertNotNull(st.from(), "a strip from fresh ground: " + c.steps());
        assertTrue(st.key().endsWith("|from " + st.from()), st.key());
        Step.ToStrip to = (Step.ToStrip) c.steps().stream().filter(s -> s instanceof Step.ToStrip).findFirst().orElseThrow();
        assertEquals(st.from(), "area " + io.github.mojolowjo.entropybot.aa.brain.StripAreas.square(to.x(), to.z()));
        assertFalse(io.github.mojolowjo.entropybot.aa.brain.StripAreas.seen(to.x(), 16, to.z()), "review 6: the start's square is not seen yet: " + to);
        assertTrue(c.cost() < origin.cost(), "fresh " + c.cost() + " < origin " + origin.cost());
        assertTrue(c.why().contains("from fresh ground"), c.why());
    }

    /** Run U's facts: squares (-14,2) and (-13,2) at y 16 seen (969 faces, 0 iron), the bot at -220 16 33. */
    static NeedChain.World runU(int x, Map<String, String> held) {
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.clear();
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.add(-220, 16, 33, 500, Map.of("minecraft:copper_ore", 7));
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.add(-205, 16, 33, 469, Map.of("minecraft:coal_ore", 6));
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Map<String, String> h = new java.util.HashMap<>(held);
        for (String d : List.of("south", "west", "north")) h.put(NeedChain.headingKey(16, d), "run U");
        var b = Z62RunSTest.band("east");
        var at = new NeedChain.StripBand(b.ores(), b.minY(), b.maxY(), b.y(), b.perSlab(), x, b.z(), b.feetY(), b.heading(), b.host(), b.headings());
        return Z62RunSTest.world(bag, at).withoutSources(h);
    }

    /**
     * Review 3: a fresh start is held by (Y, heading, area square): the bot a block or two on asks the SAME way (one key),
     * and once that way failed (its key held) the next plan takes another, never the held one again - two re-plans, no
     * ping-pong.
     */
    @Test
    void aFreshStartIsHeldByItsSquareSoReplansDoNotPingPong() {
        ScenariosTest.world("wooden", "stone");
        Step.Strip first = Z62RunSTest.strip(NeedChain.resolve("raw_iron", 3, runU(-220, Map.of())));
        assertNotNull(first.from(), "run U's facts: a fresh start");
        Step.Strip moved = Z62RunSTest.strip(NeedChain.resolve("raw_iron", 3, runU(-219, Map.of())));
        assertEquals(first.key(), moved.key(), "a block on: the same square, the same way");
        Map<String, String> held = new java.util.HashMap<>();
        held.put(NeedChain.STRIP_KEY + first.key(), "strip: no path");
        Step.Strip second = Z62RunSTest.strip(NeedChain.resolve("raw_iron", 3, runU(-219, held)));
        assertNotEquals(first.key(), second.key(), "the held square is not asked again: " + second.key());
        held.put(NeedChain.STRIP_KEY + second.key(), "strip: lava ahead");
        Step.Strip third = Z62RunSTest.strip(NeedChain.resolve("raw_iron", 3, runU(-218, held)));
        assertNotEquals(first.key(), third.key());
        assertNotEquals(second.key(), third.key());
    }

    /**
     * Review 4: each fresh start is its own way, priced on a snapshot: its walk not known yet (computing) or over unloaded
     * chunks drops only that way; the strip from the feet stands and is the plan.
     */
    @Test
    void aFreshStartsUnknownWalkDropsOnlyItself() {
        ScenariosTest.world("wooden", "stone");
        for (PathPrice.Answer away : List.of(PathPrice.Answer.computing("2 searches in flight"), PathPrice.Answer.chunks("chunks not loaded"))) {
            NeedChain.World w = runU(-220, Map.of()).withPath((x, y, z, d) -> x == -220 && z == 33
                    ? PathPrice.Answer.priced(new PathPrice.Walk(0)) : away);
            Chain c = NeedChain.resolve("raw_iron", 3, w);
            assertTrue(c.ok() && !c.explore(), c.why());
            Step.Strip st = Z62RunSTest.strip(c);
            assertNull(st.from(), "the strip from the feet: " + c.steps());
            assertTrue(c.dropped().stream().anyMatch(s -> s.contains("fresh ground") && s.contains(PathPrice.NOT_KNOWN)), c.dropped().toString());
        }
    }

    /** Review 5: the factor's floor (strip.areaMinFactor 0.5) and few faces moving the price modestly (100 faces, 0 ore: x 1.5). */
    @Test
    void theFactorIsFlooredAndFewFacesMoveItModestly() {
        double p = io.github.mojolowjo.entropybot.aa.brain.StripAreas.perFace(37.1);
        assertEquals(1.5, io.github.mojolowjo.entropybot.aa.brain.StripAreas.factor(p, new io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield(100, 0, 1)), 1e-9);
        assertEquals(0.5, io.github.mojolowjo.entropybot.aa.brain.StripAreas.factor(p, new io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield(100, 9, 1)), 1e-9,
                "one vein of 9 in 100 faces: x 0.5, not x 0.03");
        // review 2: damaged entries are skipped, never thrown on
        Map<String, Integer> nulls = new java.util.HashMap<>();
        nulls.put("minecraft:iron_ore", null);
        nulls.put(null, 3);
        assertEquals(Map.of(), new io.github.mojolowjo.entropybot.aa.brain.StripAreas.Seen(5, nulls).ores());
        Map<String, io.github.mojolowjo.entropybot.aa.brain.StripAreas.Seen> m = new java.util.HashMap<>();
        m.put("16 1 1", null);
        m.put("16 2 2", new io.github.mojolowjo.entropybot.aa.brain.StripAreas.Seen(7, null));
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.load(m);
        assertEquals(1, io.github.mojolowjo.entropybot.aa.brain.StripAreas.all().size());
    }
}
