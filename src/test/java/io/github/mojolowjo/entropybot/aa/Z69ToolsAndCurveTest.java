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
                ? PathPrice.Answer.priced(new PathPrice.Walk(handPathS(), 0, DIGS, Map.of("minecraft:stone", DIGS)))
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
        double saved = NeedChain.savedS(Map.of("minecraft:stone", DIGS), "minecraft:wooden_pickaxe");
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
        List<int[]> ranked = ExploreRules.byCurve(near, y -> iron.curve(y, true), new int[]{0, 30, 0}, 64);
        assertArrayEquals(new int[]{9, 16, 9}, ranked.get(0), "the densest Y first, though farther");
        assertArrayEquals(new int[]{8, 40, 8}, ranked.get(1));
        assertArrayEquals(new int[]{3, 64, 3}, ranked.get(2));
        assertArrayEquals(new int[]{5, 75, 5}, ranked.get(3), "no ore at y 75: last");
        // the discard: an ore half discarded in air ranks below an equal buried density elsewhere
        var j = new GenWhere.Placer("minecraft:ore_a", List.of("minecraft:a"), JsonParser.parseString(Z43StripWayTest.MIDDLE).getAsJsonObject().getAsJsonArray("placement"), 9, 0.9);
        var k = new GenWhere.Placer("minecraft:ore_b", List.of("minecraft:a"), JsonParser.parseString(Z43StripWayTest.SMALL).getAsJsonObject().getAsJsonArray("placement"), 9, 0);
        var two = GenWhere.of(List.of("minecraft:a"), List.of(j, k), -64, 320);
        List<int[]> r2 = ExploreRules.byCurve(List.of(new int[]{0, 16, 0}, new int[]{0, 60, 0}), y -> two.curve(y, true), new int[]{0, 38, 0}, 64);
        assertArrayEquals(new int[]{0, 16, 0}, r2.get(0), "the middle at 16 still beats the small alone at 60 after its 0.9 discard: "
                + two.curve(16, true) + " vs " + two.curve(60, true));
        assertTrue(two.curve(16, true) < two.curve(16, false), "the discard lowers the cave density");
    }

    // ---- fix round (Opus Design + the Sonnet review) ----

    /** Fix 1: one unreachable cobblestone spot holds that spot only; another spot of the same item is still used. */
    @Test
    void oneUnreachableSpotHoldsThatSpotOnly() {
        ScenariosTest.world("wooden", "stone");
        NeedChain.Spot far = new NeedChain.Spot(12, 60, 3, 12, "minecraft:stone", 40), near = new NeedChain.Spot(20, 60, 3, 20, "minecraft:stone", 40);
        PathPrice walks = FakePaths.byDistance();
        var w = ScenariosTest.w(Map.of("minecraft:wooden_pickaxe", 1), false, false, Map.of("cobblestone", List.of(far, near)))
                .withPath((x, y, z, d) -> x == 12 ? PathPrice.Answer.unreachable("no path") : walks.price(x, y, z, d));
        Chain first = NeedChain.resolve("cobblestone", 3, w);
        String key = NeedChain.spotKey("minecraft:cobblestone", far);
        assertEquals("collect cobblestone at 12 60 3", key);
        assertTrue(first.unreachable().containsKey(key), "the spot's own key: " + first.unreachable());
        assertFalse(first.unreachable().containsKey("collect cobblestone"), "never the whole item: " + first.unreachable());
        Chain again = NeedChain.resolve("cobblestone", 3, w.withoutSources(first.unreachable()));
        assertTrue(again.ok() && !again.explore(), "the other cobblestone spot is still a source: " + again.why());
        assertTrue(again.why().contains("dig cobblestone seen 20 m"), again.why());
        // a failed Collect step (the fork's mine takes any block of the item) still holds the item key: every spot out
        Chain step = NeedChain.resolve("cobblestone", 3, w.withoutSources(Map.of("collect cobblestone", "the mine ended with none")));
        assertTrue(step.explore(), step.why());
    }

    static final String ORE_BLACKSTONE = "{\"type\":\"minecraft:ore\",\"config\":{\"discard_chance_on_air_exposure\":0.0,\"size\":33,\"targets\":["
            + "{\"state\":{\"Name\":\"minecraft:blackstone\"},\"target\":{\"predicate_type\":\"minecraft:tag_match\",\"tag\":\"minecraft:base_stone_nether\"}}]}}";
    static final String BLACKSTONE_PLACED = "{\"feature\":\"minecraft:ore_blackstone\",\"placement\":[{\"type\":\"minecraft:count\",\"count\":2},{\"type\":\"minecraft:in_square\"},"
            + "{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:uniform\",\"max_inclusive\":{\"absolute\":31},\"min_inclusive\":{\"absolute\":5}}},{\"type\":\"minecraft:biome\"}]}";

    static io.github.mojolowjo.entropybot.guard.WorldGenFiles.Ores twoDimensions() {
        Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put("minecraft:worldgen/configured_feature/ore_iron.json", Z68ExploreWhereTest.ORE_IRON);
        f.put("minecraft:worldgen/placed_feature/ore_iron_middle.json", Z68ExploreWhereTest.MIDDLE);
        f.put("minecraft:worldgen/configured_feature/ore_blackstone.json", ORE_BLACKSTONE);
        f.put("minecraft:worldgen/placed_feature/ore_blackstone.json", BLACKSTONE_PLACED);
        f.put("minecraft:worldgen/biome/plains.json", "{\"features\":[[],[\"minecraft:ore_iron_middle\"]]}");
        f.put("minecraft:worldgen/biome/basalt_deltas.json", "{\"features\":[[],[\"minecraft:ore_blackstone\"]]}");
        List<io.github.mojolowjo.entropybot.guard.WorldGenFiles.Entry> es = new java.util.ArrayList<>();
        for (var e : f.entrySet()) es.add(Z68ExploreWhereTest.entry(e.getKey(), e.getValue()));
        return io.github.mojolowjo.entropybot.guard.WorldGenFiles.read(es).ores();
    }

    /** Fix 2: the overworld's biomes never give the stone materials the nether's blackstone band. */
    @Test
    void theOverworldsStoneSourcesGetNoNetherBand() {
        var ores = twoDimensions();
        List<String> stone = List.of("minecraft:cobblestone", "minecraft:blackstone", "minecraft:cobbled_deepslate", "minecraft:stone");
        var over = io.github.mojolowjo.entropybot.guard.WorldGenFiles.features(ores, List.of("minecraft:plains"), b -> false, -64, 320, null);
        assertNull(io.github.mojolowjo.entropybot.guard.WorldGenFiles.band(over, stone), "no overworld biome lists ore_blackstone: " + over.keySet());
        assertArrayEquals(new int[]{-24, 56}, io.github.mojolowjo.entropybot.guard.WorldGenFiles.band(over, List.of("minecraft:iron_ore")));
        var nether = io.github.mojolowjo.entropybot.guard.WorldGenFiles.features(ores, List.of("minecraft:basalt_deltas"), b -> false, 0, 256, null);
        assertArrayEquals(new int[]{5, 31}, io.github.mojolowjo.entropybot.guard.WorldGenFiles.band(nether, stone), "in the nether it is the band");
        var whereOver = GenWhere.of(stone, io.github.mojolowjo.entropybot.guard.WorldGenFiles.placersIn(ores, List.of("minecraft:plains"), b -> false), -64, 320);
        assertNotEquals(GenWhere.Kind.BAND, whereOver.kind(), whereOver.text());
        var whereNether = GenWhere.of(stone, io.github.mojolowjo.entropybot.guard.WorldGenFiles.placersIn(ores, List.of("minecraft:basalt_deltas"), b -> false), 0, 256);
        assertEquals(GenWhere.Kind.BAND, whereNether.kind(), whereNether.text());
    }

    /** Fix 3: the peaks are said nearest the feet first. */
    @Test
    void thePeaksAreSaidFromTheFeet() {
        var iron = Z68ExploreWhereTest.where("minecraft:iron_ore", "minecraft:deepslate_iron_ore");
        String low = iron.withFeet(20).peakText(), high = iron.withFeet(200).peakText();
        assertTrue(low.indexOf("y 16 (") < low.indexOf("y 232 ("), low);
        assertTrue(high.indexOf("y 232 (") < high.indexOf("y 16 ("), high);
    }

    /** Review (a): an explore the chain was in before is not the pickaxe-first way's failure. */
    @Test
    void anEarlierStuckDoesNotBlockThePickaxeFirstWay() {
        assertNull(NeedChain.pickFirstFails(null, true, true), "stuck before and after: the pickaxe itself stood");
        assertNotNull(NeedChain.pickFirstFails(null, false, true), "the pickaxe started an explore: it does not stand");
        assertNull(NeedChain.pickFirstFails(null, false, false));
        assertEquals("walk: path not known yet (computing)", NeedChain.pickFirstFails("walk: path not known yet (computing)", true, true));
    }

    /** Review (b): a near cave at 90% of the peak beats a far one at the peak; a far one clearly denser still wins. */
    @Test
    void aNearCaveNearThePeakBeatsAFarOneAtThePeak() {
        java.util.function.IntToDoubleFunction d = y -> y == 10 ? 0.9 : y == 20 ? 1.0 : y == 30 ? 3.0 : 0;
        int[] me = {0, 15, 0};
        List<int[]> r = ExploreRules.byCurve(List.of(new int[]{10, 10, 0}, new int[]{150, 20, 0}), d, me, 64);
        assertEquals(10, r.get(0)[1], "near at 90% first");
        List<int[]> r2 = ExploreRules.byCurve(List.of(new int[]{10, 10, 0}, new int[]{150, 30, 0}), d, me, 64);
        assertEquals(30, r2.get(0)[1], "three times the density is worth the walk");
    }

    /** Review (d): each block kind breaks with its own harvest divisor (/30 right tool and tier, /100 otherwise). */
    @Test
    void theSavingUsesEachKindsHarvestDivisor() {
        ScenariosTest.world("wooden", "stone");
        String wood = "minecraft:wooden_pickaxe";
        double stone = TickPrice.breakS(GameTimes.breakSeconds(1.5, 1, false)) - TickPrice.breakS(GameTimes.breakSeconds(1.5, 2, true));
        double dirt = TickPrice.breakS(GameTimes.breakSeconds(0.5, 1, true)) - TickPrice.breakS(GameTimes.breakSeconds(0.5, 2, true));
        double iron = TickPrice.breakS(GameTimes.breakSeconds(3, 1, false)) - TickPrice.breakS(GameTimes.breakSeconds(3, 2, false));
        assertEquals(2 * stone, NeedChain.savedS(Map.of("minecraft:stone", 2), wood), 1e-9, "stone: tier 1, the wooden pickaxe harvests it");
        assertEquals(2 * dirt, NeedChain.savedS(Map.of("minecraft:dirt", 2), wood), 1e-9, "no tool needed: /30 by hand too");
        assertEquals(iron, NeedChain.savedS(Map.of("minecraft:iron_ore", 1), wood), 1e-9, "tier 2 over the wooden pickaxe: /100 both");
        assertEquals(2 * stone + 2 * dirt + iron, NeedChain.savedS(Map.of("minecraft:stone", 2, "minecraft:dirt", 2, "minecraft:iron_ore", 1), wood), 1e-9);
    }
}
