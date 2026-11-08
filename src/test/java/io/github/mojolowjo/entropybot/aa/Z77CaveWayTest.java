package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 cave (manager 2026-10-26 ~15:10 on run BW, owner's seed): a known cave is a way for its ore. 14:22:14 one raw_iron
 * seen 8 m (held after no progress) kept the cave way out (it ran only when nothing was seen) -> "dig 1 + explore"; at
 * 14:24:10 the cave at 20 21 -10 was confirmed during "explore 5 until raw_iron" and the explore ran on 25 s and failed (a cave
 * was no source in its until test). Now the cave way is priced beside any seen ore (the scorer picks by price), and an explore
 * for the ore ends on a confirmed cave the planner offers (NeedChain.caveSource, the test the ways use).
 * <p>Hand numbers (TIME_MODEL, the fake walk = straight metres x 1.3 / 4.317): 8 m 2.41 s, 30 m 9.03 s, 200 m 60.23 s; an iron
 * ore with the stone pickaxe 1.15 s. 0.39.z78 J6d: a cave way is its walks only - to the lip + from the lip to the record's
 * nearest unseen cell (none here: a fresh cave, 0) - plus the ore's breaks (as every way that mines it), so the cave 30 m
 * is 9.03 + 3.45 = 12.48 s for the whole need (its world-gen count not known: it holds the need).
 */
class Z77CaveWayTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final Map<String, Integer> BAG = Map.of("minecraft:stone_pickaxe", 1);
    static final Spot NEAR = new Spot(8, 64, 0, 8, "minecraft:iron_ore", 1);
    static final double TOL = 0.1;

    /** A known fresh cave {@code dist} m away at 20 21 -10 with iron ore (J6d: its world-gen count and explored share not known). */
    static CaveWay cave(double dist) { return new CaveWay(20, 21, -10, dist, "minecraft:iron_ore", "iron", Double.NaN, Double.NaN); }

    static NeedChain.World world(CaveWay cw, Spot... seen) {
        ScenariosTest.world("wooden", "stone");
        return ScenariosTest.w(BAG, true, true, Map.of("raw_iron", List.of(seen)))
                .withCave(word -> cw != null && (word.contains("raw_iron") || word.contains("iron_ore")) ? cw : null);
    }

    /** 0.39.z78 J6b: the cave way's step is the caving step (Step.Cave, D12). */
    static boolean caveMined(Chain c) { return c.steps().stream().anyMatch(s -> s instanceof Step.Cave); }

    // ---- the cave way beside a seen ore ----

    /** BW 14:22:14: one raw_iron seen (1 block) + a confirmed iron cave 30 m: the cave way is priced and wins: J6d, 9.03 + 3.45 s. */
    @Test
    void oneOreSeen_aConfirmedCaveNear_theCaveWayIsPricedAndWins() {
        Chain c = NeedChain.resolve("raw_iron", 3, world(cave(30), NEAR));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(caveMined(c), "the cave way runs: " + c.steps());
        assertEquals(12.48, c.seconds(), TOL, c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore), c.steps().toString());
        assertTrue(c.why().contains("in the cave: known cave 30 m: the walk to its lip 9.0 s + the walk in 0 s (its passages not measured yet (the cave's cells not loaded)) + 3 ore broken") && c.why().contains(" -> 12.5 s (world-gen not known yet, explored not measured)"), c.why());
    }

    /** The same with the seen ore HELD for the order (BW: no progress): the cave way still stands and wins. */
    @Test
    void theSeenOreHeld_theCaveWayWins() {
        NeedChain.World w = world(cave(30), NEAR).withoutSources(Map.of(NeedChain.spotKey("minecraft:raw_iron", NEAR), "no progress"));
        Chain c = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(c.ok() && !c.explore() && caveMined(c), c.why());
        assertEquals(12.48, c.seconds(), TOL, c.why());
    }

    /** By price, no "prefer caves": 3 ore seen 8 m (2.41 + 3.45 = 5.86 s) beat the cave (12.48 s), which is listed with its seconds. */
    @Test
    void aCheaperSeenOre_beatsTheCave() {
        Spot near3 = new Spot(8, 64, 0, 8, "minecraft:iron_ore", 3);
        Chain c = NeedChain.resolve("raw_iron", 3, world(cave(30), near3));
        assertTrue(c.ok() && !caveMined(c), c.why());
        assertEquals(5.86, c.seconds(), TOL, c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("known cave 30 m") && d.contains("12.5")), c.dropped().toString());
    }

    /** And the other way round: 3 ore seen 200 m (60.23 + 3.45 = 63.68 s) lose to a cave 30 m (12.48 s). */
    @Test
    void aFarSeenOre_losesToANearCave() {
        Spot far3 = new Spot(200, 64, 0, 200, "minecraft:iron_ore", 3);
        Chain c = NeedChain.resolve("raw_iron", 3, world(cave(30), far3));
        assertTrue(c.ok() && caveMined(c), c.why());
        assertEquals(12.48, c.seconds(), TOL, c.why());
    }

    /** Control: no cave known - as before, the seen ore is dug and the rest explored. */
    @Test
    void control_noCaveKnown_digAndExplore() {
        Chain c = NeedChain.resolve("raw_iron", 3, world(null, NEAR));
        assertTrue(c.ok() && c.explore(), c.why());
        assertTrue(c.steps().get(0) instanceof Step.Collect k && java.util.Arrays.equals(k.at(), new int[]{8, 64, 0}), c.steps().toString());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Explore), c.steps().toString());
    }

    // ---- an explore ends when the cave appears ----

    /** The explore's until test on the order's world {@code w}: BW's seen ore is held (dropped), so only a cave can end it. */
    static String until(NeedChain.World w) {
        return ExploreRules.untilFound("raw_iron", part -> NEAR, (part, s) -> "no progress", part -> NeedChain.caveSource(w, part));
    }

    /**
     * BW 14:24:10: "explore 5 until raw_iron" runs (the seen ore held, no cave); a cave with iron is confirmed -> the explore's
     * until test ends it, naming the cave, and the re-plan on the new facts takes the cave way (no explore).
     */
    @Test
    void anExploreRunning_aCaveConfirmed_theExploreEndsAndTheReplanTakesTheCave() {
        Map<String, String> held = Map.of(NeedChain.spotKey("minecraft:raw_iron", NEAR), "no progress");
        NeedChain.World before = world(null, NEAR).withoutSources(held);
        Chain plan = NeedChain.resolve("raw_iron", 3, before);
        assertTrue(plan.ok() && plan.explore(), "the explore runs while no cave is known: " + plan.why());
        assertNull(until(before), "nothing new: the explore runs on");

        NeedChain.World after = world(cave(76), NEAR).withoutSources(held);
        String known = until(after);
        assertNotNull(known, "the confirmed cave ends the explore");
        assertTrue(known.startsWith("a source of raw_iron is known: a cave with iron_ore at 20 21 -10 (76 away)"), known);
        assertTrue(ExploreRules.untilEnds(known, null, 1) && ExploreRules.askAgain(known, null), "new since the start: the order is priced again");
        Chain replan = NeedChain.resolve("raw_iron", 3, after);
        assertTrue(replan.ok() && !replan.explore() && caveMined(replan), "the re-plan takes the cave way: " + replan.why());
        assertTrue(replan.steps().get(0) instanceof Step.ToCave k && k.x() == 20 && k.y() == 21 && k.z() == -10, "J6b run EF: the walk to the lip: " + replan.steps());
    }

    /** A cave the order dropped (no path to its mouth) or one only a candidate ends no explore; a seen source still does, first. */
    @Test
    void aDroppedCaveOrACandidate_endsNoExplore() {
        CaveWay cw = cave(76);
        NeedChain.World dropped = world(cw, NEAR).withoutSources(Map.of(cw.key(), "unreachable"));
        assertNull(until(dropped), "the order dropped the cave");
        assertNull(NeedChain.caveSource(world(cave(76).withOut("no raw_iron generates in its Y range (game data)"), NEAR), "raw_iron"), "no source by the game data: no cave way, no end");
        String seen = ExploreRules.untilFound("raw_iron", part -> NEAR, (part, s) -> null, part -> NeedChain.caveSource(world(cw, NEAR), part));
        assertTrue(seen.startsWith("a source of raw_iron is known: iron_ore at 8 64 0"), seen);
    }
}
