package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.brain.OreBand;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
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

/** 0.39.z45 (rule 19): the ore band from the jars' placed_feature JSON, joined to the configured feature, on singleplayer and a server alike. */
class Z45JarPlacementsTest {

    @AfterEach
    void clear() { Tuning.reset(); }

    // the vanilla 1.21.1 client jar's files (data/minecraft/worldgen/...), as read from minecraft_1.21.1_client.jar
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
        f.put("minecraft:worldgen/configured_feature/trees_plains.json", "{\"type\":\"minecraft:random_selector\",\"config\":{\"features\":[]}}");
        f.put("minecraft:worldgen/placed_feature/ore_iron_middle.json", MIDDLE);
        f.put("minecraft:worldgen/placed_feature/ore_iron_small.json", SMALL);
        f.put("minecraft:worldgen/placed_feature/ore_iron_upper.json", UPPER);
        f.put("minecraft:worldgen/placed_feature/trees_plains.json", "{\"feature\":\"minecraft:trees_plains\",\"placement\":[{\"type\":\"minecraft:count\",\"count\":1}]}");
        f.put("othermod:worldgen/configured_feature/ore_tin.json", ORE_IRON.replace("minecraft:iron_ore", "othermod:tin_ore").replace("minecraft:deepslate_iron_ore", "othermod:deepslate_tin_ore"));
        f.put("othermod:worldgen/placed_feature/ore_tin.json", MIDDLE.replace("minecraft:ore_iron", "othermod:ore_tin"));
        f.put("othermod:worldgen/placed_feature/ore_lost.json", MIDDLE.replace("minecraft:ore_iron", "othermod:not_in_the_jars"));
        f.put("othermod:tags/worldgen/placed_feature/ores.json", "{\"values\":[\"othermod:ore_tin\"]}");
        f.put("othermod:neoforge/biome_modifier/add_tin.json", "{\"type\":\"neoforge:add_features\",\"biomes\":\"#minecraft:is_overworld\",\"features\":\"#othermod:ores\",\"step\":\"underground_ores\"}");
        f.put("minecraft:worldgen/biome/plains.json", "{\"features\":[[],[\"minecraft:trees_plains\"],[\"minecraft:ore_iron_upper\",\"minecraft:ore_iron_middle\",\"minecraft:ore_iron_small\"]]}");
        f.put("minecraft:worldgen/biome/the_void.json", "{\"features\":[[]]}");
        List<WorldGenFiles.Entry> es = new ArrayList<>();
        for (var e : f.entrySet()) es.add(entry(e.getKey(), e.getValue()));
        return WorldGenFiles.read(es);
    }

    @Test
    void thePlacedFeatureJoinsItsConfiguredOreAndTheBiomeListsIt() {
        var o = read().ores();
        assertEquals(Set.of("minecraft:ore_iron_middle", "minecraft:ore_iron_small", "minecraft:ore_iron_upper", "othermod:ore_tin", "othermod:ore_lost").size() - 1, o.placed().size(),
                "four ore placements; trees are not ore-shaped; the lost one's configured feature is not in the jars");
        assertEquals(List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"), o.placed().get("minecraft:ore_iron_middle").blocks());
        assertEquals(4, o.placed().get("minecraft:ore_iron_small").size());
        assertEquals(1, o.unread(), "the placed feature without its configured feature is counted, not dropped silently");
        assertTrue(o.firstUnread().contains("othermod:ore_lost"));
        assertEquals(List.of(new WorldGenFiles.Modifier(List.of("#minecraft:is_overworld"), List.of("othermod:ore_tin"))), o.modifiers(), "the feature tag expanded from the jars");
    }

    @Test
    void theIronBandAndPeakComeFromTheJarsTrapezoidAndUniformRange() {
        var o = read().ores();
        var fs = WorldGenFiles.features(o, List.of("minecraft:plains"), b -> false, -64, 320, null);
        var iron = fs.get("minecraft:iron_ore");
        assertEquals(3, iron.size());
        assertTrue(iron.contains(new OreBand.Feature(-24, 56, 0, 10, 9)), "trapezoid -24..56");
        assertEquals(16, new OreBand.Feature(-24, 56, 0, 10, 9).peak(), "peak 16");
        assertTrue(iron.contains(new OreBand.Feature(-64, 72, 136, 10, 4)), "uniform above_bottom 0 .. 72");
        assertTrue(iron.contains(new OreBand.Feature(80, 384, 0, 90, 9)));
        assertFalse(fs.containsKey("othermod:tin_ore"), "the modifier's biome tag does not name plains here");
        // z43's S52 numbers from the jar data
        assertEquals(16, OreBand.bestY(iron, 63));
        double slab = OreBand.perSlab(iron, 16);
        assertEquals(32.4 + 16 * 40 / 136.0, slab, 1e-6);
        assertEquals(110, Math.round(OreBand.blocksPerOre(slab)));
        var withTin = WorldGenFiles.features(o, List.of("minecraft:plains"), "#minecraft:is_overworld"::equals, -64, 320, null);
        assertEquals(List.of(new OreBand.Feature(-24, 56, 0, 10, 9)), withTin.get("othermod:tin_ore"), "a biome modifier adds a modded ore by the synced biome tag");
        assertTrue(WorldGenFiles.features(o, List.of("minecraft:the_void"), b -> false, -64, 320, null).isEmpty(), "a biome without ores: none");
    }

    @Test
    void theCacheKeepsThePlacementsAndAnOldCacheIsRebuilt() {
        var r = read();
        String hash = "0123456789abcdef";
        var back = WorldGenFiles.fromCache(WorldGenFiles.toCache(hash, r), hash);
        assertEquals(r.ores().placed().keySet(), back.ores().placed().keySet());
        assertEquals(r.ores().biomes(), back.ores().biomes());
        assertEquals(r.ores().modifiers(), back.ores().modifiers());
        assertEquals(WorldGenFiles.features(r.ores(), List.of("minecraft:plains"), b -> true, -64, 320, null),
                WorldGenFiles.features(back.ores(), List.of("minecraft:plains"), b -> true, -64, 320, null), "the same band from the cache");
        String v2 = "{\"version\":2,\"hash\":\"" + hash + "\",\"files\":1,\"bad\":0,\"blocks\":[\"minecraft:stone\"]}";
        assertThrows(IllegalArgumentException.class, () -> WorldGenFiles.fromCache(v2, hash), "a version 2 cache has no placements: read the jars again");
        assertTrue(r.blocks().contains("minecraft:iron_ore"), "the structure fact's block set is still read from the configured features");
    }
}
