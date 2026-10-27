package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.guard.WorldGenFiles;
import io.github.mojolowjo.entropybot.vocab.ExploreWords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z68 (owner 2026-10-25: "Depending on the explore goal, we should have some logic: if it's wood - go to surface to
 * explore, if it's ore, explore for caves"): where an explore's target generates, read from hand-made world-gen fixtures in
 * the jars' format (vanilla 1.21.1 shapes), and what the chain and the explore hands do with it.
 */
class Z68ExploreWhereTest {

    @AfterEach
    void clear() { Tuning.reset(); }

    // ---- the fixtures: the vanilla shapes of a tree selector, an ore, a nested placed tree, a nether fungus ----

    static final String TREES_PLAINS_PLACED = "{\"feature\":\"minecraft:trees_plains\",\"placement\":[{\"type\":\"minecraft:count\",\"count\":1},"
            + "{\"type\":\"minecraft:in_square\"},{\"type\":\"minecraft:surface_water_depth_filter\",\"max_water_depth\":0},"
            + "{\"type\":\"minecraft:heightmap\",\"heightmap\":\"OCEAN_FLOOR\"},{\"type\":\"minecraft:biome\"}]}";
    static final String TREES_PLAINS = "{\"type\":\"minecraft:random_selector\",\"config\":{\"default\":\"minecraft:oak_bees_0002\","
            + "\"features\":[{\"chance\":0.33333334,\"feature\":\"minecraft:fancy_oak_bees_0002\"}]}}";
    static final String OAK_PLACED = "{\"feature\":\"minecraft:oak_bees_0002\",\"placement\":[{\"type\":\"minecraft:block_predicate_filter\","
            + "\"predicate\":{\"type\":\"minecraft:would_survive\",\"state\":{\"Name\":\"minecraft:oak_sapling\",\"Properties\":{\"stage\":\"0\"}}}}]}";
    static final String OAK_TREE = "{\"type\":\"minecraft:tree\",\"config\":{\"dirt_provider\":{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:dirt\"}},"
            + "\"trunk_provider\":{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:oak_log\",\"Properties\":{\"axis\":\"y\"}}},"
            + "\"foliage_provider\":{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:oak_leaves\"}}}}";
    static final String CRIMSON_PLACED = "{\"feature\":\"minecraft:crimson_fungus\",\"placement\":[{\"type\":\"minecraft:count_on_every_layer\",\"count\":8},{\"type\":\"minecraft:biome\"}]}";
    static final String CRIMSON = "{\"type\":\"minecraft:huge_fungus\",\"config\":{\"stem_state\":{\"Name\":\"minecraft:crimson_stem\"}}}";
    static final String ORE_DIRT_PLACED = "{\"feature\":\"minecraft:ore_dirt\",\"placement\":[{\"type\":\"minecraft:count\",\"count\":7},{\"type\":\"minecraft:in_square\"},"
            + "{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:uniform\",\"max_inclusive\":{\"absolute\":160},\"min_inclusive\":{\"absolute\":0}}},{\"type\":\"minecraft:biome\"}]}";
    static final String ORE_DIRT = "{\"type\":\"minecraft:ore\",\"config\":{\"size\":33,\"targets\":[{\"state\":{\"Name\":\"minecraft:dirt\"},\"target\":{\"predicate_type\":\"minecraft:tag_match\",\"tag\":\"minecraft:base_stone_overworld\"}}]}}";

    // the vanilla 1.21.1 iron placements (as Z45JarPlacementsTest has them, from minecraft_1.21.1_client.jar)
    static final String ORE_IRON = "{\"type\":\"minecraft:ore\",\"config\":{\"discard_chance_on_air_exposure\":0.0,\"size\":9,\"targets\":["
            + "{\"state\":{\"Name\":\"minecraft:iron_ore\"},\"target\":{\"predicate_type\":\"minecraft:tag_match\",\"tag\":\"minecraft:stone_ore_replaceables\"}},"
            + "{\"state\":{\"Name\":\"minecraft:deepslate_iron_ore\"},\"target\":{\"predicate_type\":\"minecraft:tag_match\",\"tag\":\"minecraft:deepslate_ore_replaceables\"}}]}}";
    static final String ORE_IRON_SMALL = ORE_IRON.replace("\"size\":9", "\"size\":4");
    static final String MIDDLE = "{\"feature\":\"minecraft:ore_iron\",\"placement\":[{\"type\":\"minecraft:count\",\"count\":10},{\"type\":\"minecraft:in_square\"},"
            + "{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:trapezoid\",\"max_inclusive\":{\"absolute\":56},\"min_inclusive\":{\"absolute\":-24}}},{\"type\":\"minecraft:biome\"}]}";
    static final String SMALL = "{\"feature\":\"minecraft:ore_iron_small\",\"placement\":[{\"type\":\"minecraft:count\",\"count\":10},{\"type\":\"minecraft:in_square\"},"
            + "{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:uniform\",\"max_inclusive\":{\"absolute\":72},\"min_inclusive\":{\"above_bottom\":0}}},{\"type\":\"minecraft:biome\"}]}";
    static final String UPPER = "{\"feature\":\"minecraft:ore_iron\",\"placement\":[{\"type\":\"minecraft:count\",\"count\":90},{\"type\":\"minecraft:in_square\"},"
            + "{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:trapezoid\",\"max_inclusive\":{\"absolute\":384},\"min_inclusive\":{\"absolute\":80}}},{\"type\":\"minecraft:biome\"}]}";

    static WorldGenFiles.Entry entry(String id, String text) {
        return new WorldGenFiles.Entry() {
            @Override public String id() { return id; }
            @Override public InputStream open() { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
        };
    }

    static WorldGenFiles.Read read() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("minecraft:worldgen/configured_feature/ore_iron.json", ORE_IRON);
        f.put("minecraft:worldgen/configured_feature/ore_iron_small.json", ORE_IRON_SMALL);
        f.put("minecraft:worldgen/placed_feature/ore_iron_middle.json", MIDDLE);
        f.put("minecraft:worldgen/placed_feature/ore_iron_small.json", SMALL);
        f.put("minecraft:worldgen/placed_feature/ore_iron_upper.json", UPPER);
        f.put("minecraft:worldgen/configured_feature/trees_plains.json", TREES_PLAINS);
        f.put("minecraft:worldgen/placed_feature/trees_plains.json", TREES_PLAINS_PLACED);
        f.put("minecraft:worldgen/configured_feature/oak_bees_0002.json", OAK_TREE);
        f.put("minecraft:worldgen/placed_feature/oak_bees_0002.json", OAK_PLACED);
        f.put("minecraft:worldgen/configured_feature/fancy_oak_bees_0002.json", OAK_TREE);
        f.put("minecraft:worldgen/placed_feature/fancy_oak_bees_0002.json", OAK_PLACED.replace("oak_bees_0002", "fancy_oak_bees_0002"));
        f.put("minecraft:worldgen/configured_feature/crimson_fungus.json", CRIMSON);
        f.put("minecraft:worldgen/placed_feature/crimson_fungi.json", CRIMSON_PLACED);
        f.put("minecraft:worldgen/configured_feature/ore_dirt.json", ORE_DIRT);
        f.put("minecraft:worldgen/configured_feature/kelp.json", "{\"type\":\"minecraft:kelp\",\"config\":{\"state\":{\"Name\":\"minecraft:kelp\"}}}");
        f.put("minecraft:worldgen/placed_feature/kelp_cold.json", "{\"feature\":\"minecraft:kelp\",\"placement\":[{\"type\":\"minecraft:noise_based_count\",\"noise_factor\":80.0,\"noise_to_count_ratio\":120},"
                + "{\"type\":\"minecraft:in_square\"},{\"type\":\"minecraft:heightmap\",\"heightmap\":\"OCEAN_FLOOR_WG\"},{\"type\":\"minecraft:biome\"}]}");
        f.put("minecraft:worldgen/configured_feature/flower_plains.json", "{\"type\":\"minecraft:flower\",\"config\":{\"state\":{\"Name\":\"minecraft:dandelion\"}}}");
        f.put("minecraft:worldgen/placed_feature/flower_plains.json", "{\"feature\":\"minecraft:flower_plains\",\"placement\":[{\"type\":\"minecraft:rarity_filter\",\"chance\":32},"
                + "{\"type\":\"minecraft:in_square\"},{\"type\":\"minecraft:heightmap\",\"heightmap\":\"MOTION_BLOCKING\"},{\"type\":\"minecraft:biome\"}]}");
        f.put("minecraft:worldgen/placed_feature/ore_dirt.json", ORE_DIRT_PLACED);
        List<WorldGenFiles.Entry> es = new ArrayList<>();
        for (var e : f.entrySet()) es.add(entry(e.getKey(), e.getValue()));
        return WorldGenFiles.read(es);
    }

    static List<GenWhere.Placer> placers() { return read().ores().placers(); }

    static GenWhere.Fact where(String... blocks) { return GenWhere.of(List.of(blocks), placers(), -64, 320); }

    // ---- the "where" fact ----

    @Test
    void logsAreSurfaceTheTreeSelectorIsHeightmapPlaced() {
        var w = where("minecraft:oak_log");
        assertEquals(GenWhere.Kind.SURFACE, w.kind(), w.text());
        assertEquals("minecraft:trees_plains", w.feature(), "the top-level placed feature with the heightmap, through the selector's placed trees");
        assertEquals("oak_log grows on the surface: trees_plains is heightmap-placed", w.text());
        for (var p : placers()) assertFalse(p.blocks().contains("minecraft:oak_sapling"), "a would_survive state is a predicate, not a placed block: " + p);
    }

    @Test
    void ironOreIsABandAroundTheIronBands() {
        var w = where("minecraft:iron_ore", "minecraft:deepslate_iron_ore");
        assertEquals(GenWhere.Kind.BAND, w.kind(), w.text());
        assertEquals(-64, w.lo(), "ore_iron_small: uniform above_bottom 0 at bottom -64");
        assertEquals(384, w.hi(), "ore_iron_upper: trapezoid 80..384");
        // 0.39.z69 item 2: the curve's peaks follow, densest first
        assertEquals("deepslate_iron_ore generates under ground at Y -64..384: ore_iron_middle is height-range-placed; densest at y 232 (ore_iron_upper trapezoid 80..384, 5.3 a chunk layer)"
                + " and y 16 (ore_iron_middle trapezoid -24..56, 2.5 a chunk layer)", w.text());
    }

    @Test
    void aTargetWithNoFeatureIsUnknownAndANotLoadedReadIsNotNone() {
        var none = where("minecraft:obsidian");
        assertEquals(GenWhere.Kind.UNKNOWN, none.kind());
        assertEquals("where it generates is not known: no world-gen feature places obsidian", none.text());
        assertEquals(GenWhere.Kind.UNKNOWN, where("minecraft:crimson_stem").kind(), "count_on_every_layer says neither surface nor band");
        var both = where("minecraft:dirt");
        assertEquals(GenWhere.Kind.UNKNOWN, both.kind(), "dirt: under trees (heightmap) and ore_dirt (a band): both ways - as before");
        assertTrue(both.why().contains("both ways"), both.why());
        var nl = GenWhere.of(List.of("minecraft:oak_log"), null, -64, 320);
        assertEquals(GenWhere.Kind.NOT_LOADED, nl.kind(), "rule 15: the read not done is not loaded, never unknown or none");
    }

    @Test
    void theCacheKeepsThePlacers() {
        var r = read();
        String hash = "0123456789abcdef";
        var back = WorldGenFiles.fromCache(WorldGenFiles.toCache(hash, r), hash);
        assertEquals(r.ores().placers().size(), back.ores().placers().size());
        assertEquals(GenWhere.of(List.of("minecraft:oak_log"), r.ores().placers(), -64, 320),
                GenWhere.of(List.of("minecraft:oak_log"), back.ores().placers(), -64, 320));
        String v4 = WorldGenFiles.toCache(hash, r).replace("\"version\":7", "\"version\":4");
        assertThrows(IllegalArgumentException.class, () -> WorldGenFiles.fromCache(v4, hash), "a version 4 cache has no vein sizes / discards (z69): the jars are read again");
        String v5 = WorldGenFiles.toCache(hash, r).replace("\"version\":7", "\"version\":5");
        assertThrows(IllegalArgumentException.class, () -> WorldGenFiles.fromCache(v5, hash), "0.39.z74: a version 5 cache has no base blocks: the jars are read again");
    }

    // ---- the chain: a bot at y 16 exploring for logs walks up first ----

    static final int SKY_Y = 70;

    static NeedChain.World logsWorld(NeedChain.Surface surface) {
        ScenariosTest.world("wooden");
        var placers = placers();
        return ScenariosTest.w(Map.of(), false, false, Map.of()).withWhere(new NeedChain.WhereFacts(
                word -> GenWhere.of(word.contains("log") ? Set.of("minecraft:oak_log") : Set.of("minecraft:iron_ore"), placers, -64, 320), () -> surface));
    }

    @Test
    void aBotAtY16ExploringForLogsFirstWalksUpThenExploresOnTheSurface() {
        NeedChain.Surface under = new NeedChain.Surface(SKY_Y - 16, 3, SKY_Y, 4, 54.1);
        Chain c = NeedChain.resolve("oak_log", 4, logsWorld(under));
        assertTrue(c.explore(), c.why());
        List<Step> s = c.steps();
        assertEquals(new Step.ToSurface(3, SKY_Y, 4), s.get(0), "first the priced walk up: " + s);
        assertTrue(s.get(1) instanceof Step.Explore e && "oak_log".equals(e.until()), "then the explore: " + s);
        assertEquals(2, s.size(), s.toString());
        assertTrue(c.why().contains("explore for oak_log on the surface (oak_log grows on the surface: trees_plains is heightmap-placed); walk up to the surface at 3 70 4 first (54 under ground)"), c.why());
        assertTrue(c.why().contains("first (54 under ground), walk "), "the walk up has its price (Baritone's path; the fake): " + c.why());
    }

    @Test
    void onTheSurfaceTheLogsExploreRunsAsBefore() {
        Chain c = NeedChain.resolve("oak_log", 4, logsWorld(new NeedChain.Surface(0, 0, 64, 0, 0)));
        assertEquals(1, c.steps().size(), c.steps().toString());
        assertTrue(c.steps().get(0) instanceof Step.Explore, c.steps().toString());
        assertTrue(c.why().contains("explore for oak_log on the surface (oak_log grows on the surface"), c.why());
    }

    /**
     * 0.39.z77 J2 E2 (manager 2026-10-26; runs AX 14:23, BC 06:24:02): the failed walk up is held for the order as before, but
     * from 54 blocks under ground the explore no longer runs from here (its target would be a goal through rock, rule 22):
     * the way fails with the reason (was z68: the explore ran from here).
     */
    @Test
    void aFailedWalkUpIsHeldAndNoExploreRunsThroughRock() {
        NeedChain.Surface under = new NeedChain.Surface(54, 3, SKY_Y, 4, 54.1);
        var w = logsWorld(under);
        Chain first = NeedChain.resolve("oak_log", 4, w);
        String key = NeedChain.sourceKey(first.steps().get(0));
        assertEquals("surface at 3 70 4", key);
        FactChange.surface(3, SKY_Y, 4, "the walk up ended short: Baritone stopped");
        FactChange.Fact f = FactChange.take();
        assertEquals("source " + key, f.key(), "a source fact: held for the order, a re-plan");
        Chain again = NeedChain.resolve("oak_log", 4, w.withoutSources(Map.of(key, f.text())));
        assertTrue(again.steps().stream().noneMatch(st -> st instanceof Step.ToSurface), "no walk up again: " + again.steps());
        assertTrue(again.steps().stream().noneMatch(st -> st instanceof Step.Explore), "no explore from under ground: " + again.steps());
        assertFalse(again.ok(), again.why());
        assertTrue(again.missing().startsWith(NeedChain.UNDER_GROUND), again.why());
    }

    @Test
    void noPathUpIsUnreachableAndAPathComputingWaits() {
        NeedChain.Surface under = new NeedChain.Surface(54, 3, SKY_Y, 4, 54.1);
        var none = logsWorld(under).withPath((x, y, z, d) -> PathPrice.Answer.unreachable("no path"));
        Chain c = NeedChain.resolve("oak_log", 4, none);
        assertTrue(c.steps().stream().noneMatch(st -> st instanceof Step.ToSurface), c.steps().toString());
        assertTrue(c.unreachable().containsKey("surface at 3 70 4"), c.unreachable().toString());
        var computing = logsWorld(under).withPath((x, y, z, d) -> PathPrice.Answer.computing("searching"));
        Chain w = NeedChain.resolve("oak_log", 4, computing);
        assertTrue(w.waiting(), "rule 18: the walk up's path not known yet - the chain waits: " + w.why());
    }

    @Test
    void anOreTargetKeepsItsBandAndSaysCaves() {
        // z68 review 3: the iron target with the iron fact (logsWorld's where gives iron_ore for a word without "log")
        Chain c = NeedChain.resolve("iron_ore", 1, logsWorld(null));
        assertTrue(c.steps().get(0) instanceof Step.ToBand b && b.minY() == -64 && b.maxY() == 384, "the fact's band when the ore placements gave none: " + c.steps());
        assertTrue(c.why().contains("explore for iron_ore under ground, toward caves in or near the band (iron_ore generates under ground at Y -64..384"), c.why());
    }

    @Test
    void withCaveKeepsTheWhereFacts() {
        var facts = new NeedChain.WhereFacts(w -> GenWhere.unknown("x"), () -> null);
        var w = ScenariosTest.w(Map.of(), false, false, Map.of()).withWhere(facts).withCave(word -> null).withStrip(word -> null).withUses(i -> 1)
                .withRooms(List.of()).withPath(FakePaths.byDistance()).withBand(word -> null).withStations(k -> null).withMobs(id -> List.of(), null)
                .withoutSources(Map.of("collect x", "gone")).withPlaceFailures(Map.of("place at 1 2 3", "no room"), new int[]{1, 2, 3});
        assertSame(facts, w.where(), "every later with* keeps the where facts (review 1: withCave dropped them)");
    }

    @Test
    void ironCavesInTheMiddleBandComeFirstForABotAtY16() {
        var iron = where("minecraft:iron_ore", "minecraft:deepslate_iron_ore");
        assertEquals(3, iron.bands().size(), iron.bands().toString());
        var pb = iron.preferred(16);
        assertEquals("minecraft:ore_iron_middle", pb.feature(), "y 16 is inside small (-64..72, 10 veins) and middle (-24..56, 10 veins): middle is denser");
        assertEquals(-24, pb.lo());
        assertEquals(56, pb.hi());
        assertEquals("minecraft:ore_iron_upper", iron.preferred(200).feature(), "high in a mountain: the upper band");
        List<int[]> caves = List.of(new int[]{3, 64, 3}, new int[]{8, -40, 8}, new int[]{9, 10, 9}, new int[]{12, 100, 0});
        List<int[]> sorted = ExploreRules.bandFirst(caves, pb.lo(), pb.hi());
        assertArrayEquals(new int[]{9, 10, 9}, sorted.get(0), "the cave in -24..56 first, though -40 is in the union too");
    }

    @Test
    void onlyLandHeightmapsAreTheSurfaceTheSeaFloorIsNot() {
        var kelp = where("minecraft:kelp");
        assertEquals(GenWhere.Kind.UNKNOWN, kelp.kind(), "OCEAN_FLOOR_WG without the no-water filter: the sea floor, not the surface");
        assertTrue(kelp.why().contains("placed on the sea floor (kelp_cold"), kelp.why());
        assertEquals(GenWhere.Kind.SURFACE, where("minecraft:dandelion").kind(), "MOTION_BLOCKING: the land surface");
        assertEquals(GenWhere.Kind.SURFACE, where("minecraft:oak_log").kind(), "trees: OCEAN_FLOOR with surface_water_depth_filter 0 (no water over it): land");
    }

    @Test
    void noWhereFactsIsTheExploreAsBefore() {
        ScenariosTest.world("wooden");
        Chain c = NeedChain.resolve("oak_log", 4, ScenariosTest.w(Map.of(), false, false, Map.of()));
        assertEquals(1, c.steps().size(), c.steps().toString());
        assertFalse(c.why().contains("explore for"), c.why());
    }

    // ---- the hands' pure parts: land first for a surface target, the band's caves first for a band target ----

    @Test
    void aSurfaceExplorePrefersLandOverKnownWater() {
        ExploreWords.BiPred none = (x, z) -> false;
        int[] plain = ExploreWords.next(0, 0, 0, 0, null, none, none);
        int[] same = ExploreWords.next(0, 0, 0, 0, null, none, none, null, 4);
        assertArrayEquals(plain, same, "no water fact: the plain ring order");
        ExploreWords.BiPred westSea = (x, z) -> x < 0;                     // the client knows the west is sea
        int[] land = ExploreWords.next(0, 0, 0, 0, null, none, none, westSea, 4);
        assertTrue(land[0] >= 0, "land first: " + land[0] + " " + land[1]);
        assertEquals(1, Math.max(Math.abs(land[0]), Math.abs(land[1])), "the nearest land ring");
        ExploreWords.BiPred allSea = (x, z) -> Math.max(Math.abs(x), Math.abs(z)) <= 3;   // land only from ring 4
        int[] far = ExploreWords.next(0, 0, 0, 0, null, none, none, allSea, 4);
        assertEquals(4, Math.max(Math.abs(far[0]), Math.abs(far[1])), "ring 1 sea costs 1 + 4 = 5, ring 4 land costs 4: land wins");
    }

    @Test
    void aBandExploreAimsAtCavesInTheBandFirst() {
        List<int[]> near = List.of(new int[]{5, 70, 5}, new int[]{9, 20, 9}, new int[]{12, 72, 0});
        List<int[]> sorted = ExploreRules.bandFirst(near, -64, 56);
        assertArrayEquals(new int[]{9, 20, 9}, sorted.get(0), "the pocket in the band first");
        assertArrayEquals(new int[]{5, 70, 5}, sorted.get(1), "then the others nearest first");
        assertEquals(3, sorted.size());
    }
}
