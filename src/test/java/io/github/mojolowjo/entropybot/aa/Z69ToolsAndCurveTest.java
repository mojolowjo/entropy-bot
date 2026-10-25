package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.OreBand;
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
 * 0.39.z69 (run AA on z68, 2026-10-25 11:39-11:50: nothing made in 28 min): item 1 - tools before digging (the band walk
 * priced with its hand breaks against making the cheapest pickaxe first); item 2 - the ore density curve by height (the
 * height provider's shape, count, vein size, air-exposure discard), its peaks, and the caves ranked by it.
 */
class Z69ToolsAndCurveTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    // ---- item 1: run AA's start - the cobblestone for the furnace has no known source, the band is under the feet ----

    static final int FEET_Y = 62;
    static final int BAND_CELL_Y = 30;          // Step.ToBand(5, 31).target(62): one inside the top edge
    static final int DIGS = 2 * (FEET_Y - BAND_CELL_Y);      // the feet and the head cell of each layer dug through
    static final double STONE_H = 1.5;          // TestData's stone hardness (the game's)

    /** Baritone's priced path down by hand: every layer two stone blocks at the hand break (the game's formula), plus a walk. */
    static double handPathS() { return DIGS * TickPrice.breakS(GameTimes.breakSeconds(STONE_H, 1, false)) + 5; }

    static PathPrice runAaPaths() {
        PathPrice walks = FakePaths.byDistance();
        return (x, y, z, d) -> y == BAND_CELL_Y && x == 0 && z == 0
                ? PathPrice.Answer.priced(new PathPrice.Walk(handPathS(), 0, DIGS, DIGS * STONE_H))
                : walks.price(x, y, z, d);
    }

    static NeedChain.World runAaWorld(Map<String, Integer> bag) {
        return ScenariosTest.w(bag, false, false, Map.of("oak_log", List.of(ScenariosTest.tree(12, 6))))
                .withPath(runAaPaths())
                .withBand(word -> new int[]{5, 31})
                .withWhere(new NeedChain.WhereFacts(word -> null, () -> null, () -> new int[]{0, FEET_Y, 0}));
    }

    static int indexOf(List<Step> steps, java.util.function.Predicate<Step> p) {
        for (int i = 0; i < steps.size(); i++) if (p.test(steps.get(i))) return i;
        return -1;
    }

    @Test
    void runAaStartMakesTheWoodenPickaxeBeforeTheDigDown() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("cobblestone", 8, runAaWorld(Map.of()));
        assertTrue(c.explore(), c.why());
        List<Step> s = c.steps();
        int chop = indexOf(s, st -> st instanceof Step.ChopTree);
        int pick = indexOf(s, st -> st instanceof Step.Craft k && k.item().equals("wooden_pickaxe"));
        int band = indexOf(s, st -> st instanceof Step.ToBand);
        int explore = indexOf(s, st -> st instanceof Step.Explore);
        assertTrue(chop >= 0 && pick > chop && band > pick && explore > band, "cut logs -> planks -> sticks -> table -> wooden pickaxe -> down -> explore: " + s);
        assertTrue(indexOf(s, st -> st instanceof Step.Craft k && k.item().equals("stick")) < pick, s.toString());
        assertTrue(c.why().contains("dig down to y 30 with wooden_pickaxe made first (" + DIGS + " blocks to break"), c.why());
    }

    @Test
    void aHandDigDownIsPricedFarAboveThePickaxeFirstWay() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("cobblestone", 8, runAaWorld(Map.of()));
        double hand = handPathS();
        double saved = NeedChain.savedS(DIGS, DIGS * STONE_H, "minecraft:wooden_pickaxe");
        // by hand 150 ticks a stone block (/100), the wooden pickaxe (speed 2, the right tool, /30) 23 ticks
        assertEquals(DIGS * (7.5 - 1.15), saved, 1e-6, "the game's break formula, by hand vs the wooden pickaxe");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("= ([0-9.]+) s, by hand ([0-9.]+) s").matcher(c.why());
        assertTrue(m.find(), c.why());
        double with = Double.parseDouble(m.group(1)), byHand = Double.parseDouble(m.group(2));
        assertEquals(hand, byHand, 0.05, c.why());
        assertTrue(byHand > 3 * with, "the hand dig-down (" + byHand + " s) is far above the pickaxe-first way (" + with + " s): " + c.why());
    }

    @Test
    void aPickaxeInTheBagWalksDownWithNoNewPickaxe() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("cobblestone", 8, runAaWorld(Map.of("minecraft:wooden_pickaxe", 1)));
        assertEquals(-1, indexOf(c.steps(), st -> st instanceof Step.Craft k && k.item().endsWith("_pickaxe")), c.steps().toString());
        assertTrue(c.steps().get(0) instanceof Step.ToBand, c.steps().toString());
        assertTrue(c.why().contains("dig down to y 30 with wooden_pickaxe (" + DIGS + " blocks to break)"), c.why());
    }

    @Test
    void aWalkWithNoBreaksNeedsNoPickaxeAndNoPathIntoTheBandIsHeld() {
        ScenariosTest.world("wooden", "stone");
        var open = runAaWorld(Map.of()).withPath(FakePaths.byDistance());
        Chain c = NeedChain.resolve("cobblestone", 8, open);
        assertEquals(-1, indexOf(c.steps(), st -> st instanceof Step.Craft), "a cave path down breaks nothing: no pickaxe first: " + c.steps());
        assertTrue(c.steps().get(0) instanceof Step.ToBand, c.steps().toString());
        var none = runAaWorld(Map.of()).withPath((x, y, z, d) -> y == BAND_CELL_Y ? PathPrice.Answer.unreachable("no path") : FakePaths.byDistance().price(x, y, z, d));
        Chain u = NeedChain.resolve("cobblestone", 8, none);
        assertEquals(-1, indexOf(u.steps(), st -> st instanceof Step.ToBand), u.steps().toString());
        assertTrue(u.unreachable().containsKey(FactChange.bandKey(5, 31)), "the z42 band fact, held for the order: " + u.unreachable());
        var computing = runAaWorld(Map.of()).withPath((x, y, z, d) -> y == BAND_CELL_Y ? PathPrice.Answer.computing("searching") : FakePaths.byDistance().price(x, y, z, d));
        assertTrue(NeedChain.resolve("cobblestone", 8, computing).waiting(), "rule 18: the walk down's path not known yet - the chain waits");
    }

    // ---- item 2: the density curve ----

    static OreBand.Feature f(String placed, int size, double discard) {
        return OreBand.of(JsonParser.parseString(placed).getAsJsonObject().getAsJsonArray("placement"), size, discard, -64, 320);
    }

    static List<OreBand.Feature> iron() {
        return List.of(f(Z43StripWayTest.MIDDLE, 9, 0), f(Z43StripWayTest.SMALL, 4, 0), f(Z43StripWayTest.UPPER, 9, 0));
    }

    @Test
    void ironsThreePlacersPeakAt16And232() {
        List<Integer> peaks = OreBand.peaks(iron(), false);
        assertEquals(List.of(16, 232), peaks, "ore_iron_middle's trapezoid -24..56 and ore_iron_upper's 80..384; the small uniform is flat");
        assertEquals(10 * 9 * 2.0 / 80 + 10 * 4 / 136.0, OreBand.at(iron(), 16, false), 1e-9);
        assertEquals(90 * 9 * 2.0 / 304, OreBand.at(iron(), 232, false), 1e-9);
        assertEquals(16, OreBand.bestY(iron(), 63), "the strip way reads the same curve: its peak under the feet");
        assertTrue(peaks.contains(OreBand.bestY(iron(), 320)), "the strip's band agrees with a curve peak");
        var w = Z68ExploreWhereTest.where("minecraft:iron_ore", "minecraft:deepslate_iron_ore");
        assertEquals(List.of(232, 16), w.peaks(false).stream().map(GenWhere.Fact.Peak::y).toList(), "densest first");
        assertEquals("minecraft:ore_iron_middle", w.peaks(false).get(1).by().feature());
        assertTrue(w.text().contains("densest at y 232 (ore_iron_upper trapezoid 80..384") && w.text().contains("y 16 (ore_iron_middle trapezoid -24..56"), w.text());
        assertEquals(OreBand.at(iron(), 16, false), w.curve(16, false), 1e-9, "GenWhere and the strip way: one curve");
    }

    @Test
    void theBiasedKindsSitAtTheBottomAndSumToOne() {
        String biased = "{\"placement\":[{\"type\":\"minecraft:count\",\"count\":1},{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:biased_to_bottom\","
                + "\"min_inclusive\":{\"absolute\":0},\"max_inclusive\":{\"absolute\":40},\"inner\":1}}]}";
        OreBand.Feature b = f(biased, 1, 0), v = f(biased.replace("minecraft:biased_to_bottom", "minecraft:very_biased_to_bottom"), 1, 0);
        assertEquals(OreBand.Shape.BIASED_TO_BOTTOM, b.shape());
        assertEquals(OreBand.Shape.VERY_BIASED_TO_BOTTOM, v.shape());
        double sb = 0, sv = 0;
        for (int y = 0; y <= 40; y++) { sb += b.perY(y); sv += v.perY(y); }
        assertEquals(1, sb, 1e-9, "a share of every layer: one vein in all");
        assertEquals(1, sv, 1e-9);
        assertTrue(b.perY(0) > b.perY(20) && b.perY(20) > b.perY(39), "biased to the bottom");
        assertTrue(v.perY(0) > b.perY(0), "very biased: more at the bottom");
        assertEquals(0, b.peak());
        assertEquals(List.of(0), OreBand.peaks(List.of(b), false));
    }

    @Test
    void theAirExposureDiscardCountsForCavesOnly() {
        OreBand.Feature diamond = f(Z43StripWayTest.MIDDLE, 9, 0.5);
        assertEquals(diamond.perY(16) * 0.5, diamond.exposedPerY(16), 1e-12, "half the exposed ore is skipped at generation");
        assertEquals(OreBand.at(List.of(diamond), 16, false), diamond.perY(16), 1e-12, "buried (a strip opens it): the whole density");
        assertEquals(OreBand.perSlab(List.of(diamond), 16), OreBand.perSlab(List.of(f(Z43StripWayTest.MIDDLE, 9, 0)), 16), 1e-12, "the strip way never takes the discard off");
        var p = new GenWhere.Placer("minecraft:ore_x", List.of("minecraft:x_ore"), JsonParser.parseString(Z43StripWayTest.MIDDLE).getAsJsonObject().getAsJsonArray("placement"), 9, 0.5);
        var w = GenWhere.of(List.of("minecraft:x_ore"), List.of(p), -64, 320);
        assertEquals(w.curve(16, false) * 0.5, w.curve(16, true), 1e-12);
    }

    @Test
    void cavesAreRankedByTheCurveAtTheirY() {
        var iron = Z68ExploreWhereTest.where("minecraft:iron_ore", "minecraft:deepslate_iron_ore");
        // nearest first: a cave at y 64 (only the small uniform), one at y 16 (the middle's peak), one at y 40, one at y 75 (none)
        List<int[]> near = List.of(new int[]{3, 64, 3}, new int[]{5, 75, 5}, new int[]{8, 40, 8}, new int[]{9, 16, 9});
        List<int[]> ranked = ExploreRules.byCurve(near, y -> iron.curve(y, true));
        assertArrayEquals(new int[]{9, 16, 9}, ranked.get(0), "the densest Y first, though farther");
        assertArrayEquals(new int[]{8, 40, 8}, ranked.get(1));
        assertArrayEquals(new int[]{3, 64, 3}, ranked.get(2));
        assertArrayEquals(new int[]{5, 75, 5}, ranked.get(3), "no ore at y 75: last");
        // the discard: an ore half discarded in air ranks below an equal buried density elsewhere
        var j = new GenWhere.Placer("minecraft:ore_a", List.of("minecraft:a"), JsonParser.parseString(Z43StripWayTest.MIDDLE).getAsJsonObject().getAsJsonArray("placement"), 9, 0.9);
        var k = new GenWhere.Placer("minecraft:ore_b", List.of("minecraft:a"), JsonParser.parseString(Z43StripWayTest.SMALL).getAsJsonObject().getAsJsonArray("placement"), 9, 0);
        var two = GenWhere.of(List.of("minecraft:a"), List.of(j, k), -64, 320);
        List<int[]> r2 = ExploreRules.byCurve(List.of(new int[]{0, 16, 0}, new int[]{0, 60, 0}), y -> two.curve(y, true));
        assertArrayEquals(new int[]{0, 16, 0}, r2.get(0), "the middle at 16 still beats the small alone at 60 after its 0.9 discard: "
                + two.curve(16, true) + " vs " + two.curve(60, true));
        assertTrue(two.curve(16, true) < two.curve(16, false), "the discard lowers the cave density");
    }
}
