package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.guard.BreakPolicy.Action;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Ask;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.BlockFacts;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Fluid;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Snapshot;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Structure;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Verdict;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Where;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 natural (run CJ 17:25:25 / 17:31:00: the strip's next cell 6 16 25, a dripstone_block, was refused as
 * "structure block outside my areas" and the tunnel had no path). dripstone_block has a crafting recipe (4 pointed
 * dripstone), and the dripstone features place it in code (DripstoneClusterFeature / DripstoneUtils), so their JSON names
 * no state; the reader took block-state Names only and dropped the plain block ids the features' configs write
 * (glow_lichen / sculk_vein: can_be_placed_on dripstone_block). The table then called it crafted and never world-gen.
 *
 * <p>0.39.z77 natural 2 (manager 2026-10-26): no override file (a name list); a crafted block is never a structure block
 * when (1) the jars' world gen names it, (2) it is in a tag a carver of the jars names (vanilla's
 * #overworld_carver_replaceables holds the ore veins' raw_iron_block / raw_copper_block; the 1.21.1 jar has no ore-vein
 * JSON or tag, the veins are the noise router's code), or (3) its loot drops an ore family's item. Glowstone is named by
 * none of them (GlowstoneFeature places it in code) and stays refused, saying so.
 * The fixture files are the vanilla 1.21.1 shapes (trimmed to the keys that hold blocks).
 */
class Z77NaturalBlocksTest {
    static final String OW = "minecraft:overworld";

    /** The fixture jar's configured features, carver and noise settings, as vanilla 1.21.1 writes them. */
    static Map<String, String> files() {
        Map<String, String> f = new LinkedHashMap<>();
        String cf = "minecraft:worldgen/configured_feature/";
        f.put(cf + "dripstone_cluster.json", "{\"type\":\"minecraft:dripstone_cluster\",\"config\":{\"chance_of_dripstone_column_at_max_distance_from_center\":0.1,"
                + "\"density\":{\"type\":\"minecraft:uniform\",\"max_exclusive\":0.7,\"min_inclusive\":0.3},\"dripstone_block_layer_thickness\":{\"type\":\"minecraft:uniform\",\"max_inclusive\":4,\"min_inclusive\":2},"
                + "\"floor_to_ceiling_search_range\":12,\"height_deviation\":3}}");
        f.put(cf + "large_dripstone.json", "{\"type\":\"minecraft:large_dripstone\",\"config\":{\"column_radius\":{\"type\":\"minecraft:uniform\",\"max_inclusive\":19,\"min_inclusive\":3},"
                + "\"floor_to_ceiling_search_range\":30,\"min_radius_for_wind\":4}}");
        f.put(cf + "pointed_dripstone.json", "{\"type\":\"minecraft:simple_random_selector\",\"config\":{\"features\":[{\"feature\":{\"type\":\"minecraft:pointed_dripstone\","
                + "\"config\":{\"chance_of_directional_spread\":0.7,\"chance_of_taller_dripstone\":0.2}},\"placement\":[{\"type\":\"minecraft:environment_scan\","
                + "\"allowed_search_condition\":{\"type\":\"minecraft:matching_blocks\",\"blocks\":[\"minecraft:air\",\"minecraft:water\"]},\"direction_of_search\":\"down\","
                + "\"max_steps\":12,\"target_condition\":{\"type\":\"minecraft:solid\"}}]}]}}");
        f.put(cf + "glow_lichen.json", "{\"type\":\"minecraft:multiface_growth\",\"config\":{\"block\":\"minecraft:glow_lichen\",\"can_be_placed_on\":[\"minecraft:stone\","
                + "\"minecraft:andesite\",\"minecraft:diorite\",\"minecraft:granite\",\"minecraft:dripstone_block\",\"minecraft:calcite\",\"minecraft:tuff\",\"minecraft:deepslate\"],"
                + "\"can_place_on_ceiling\":true,\"can_place_on_floor\":false,\"can_place_on_wall\":true,\"chance_of_spreading\":0.5,\"search_range\":20}}");
        f.put(cf + "amethyst_geode.json", "{\"type\":\"minecraft:geode\",\"config\":{\"blocks\":{"
                + "\"alternate_inner_layer_provider\":{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:budding_amethyst\"}},"
                + "\"cannot_replace\":\"#minecraft:features_cannot_replace\",\"filling_provider\":{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:air\"}},"
                + "\"inner_layer_provider\":{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:amethyst_block\"}},\"inner_placements\":["
                + "{\"Name\":\"minecraft:small_amethyst_bud\",\"Properties\":{\"facing\":\"up\",\"waterlogged\":\"false\"}},{\"Name\":\"minecraft:medium_amethyst_bud\",\"Properties\":{\"facing\":\"up\",\"waterlogged\":\"false\"}},"
                + "{\"Name\":\"minecraft:large_amethyst_bud\",\"Properties\":{\"facing\":\"up\",\"waterlogged\":\"false\"}},{\"Name\":\"minecraft:amethyst_cluster\",\"Properties\":{\"facing\":\"up\",\"waterlogged\":\"false\"}}],"
                + "\"invalid_blocks\":\"#minecraft:geode_invalid_blocks\",\"middle_layer_provider\":{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:calcite\"}},"
                + "\"outer_layer_provider\":{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:smooth_basalt\"}}},\"max_gen_offset\":16}}");
        f.put(cf + "moss_patch.json", "{\"type\":\"minecraft:vegetation_patch\",\"config\":{\"depth\":1,\"ground_state\":{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:moss_block\"}},"
                + "\"replaceable\":\"#minecraft:moss_replaceable\",\"surface\":\"floor\",\"vegetation_chance\":0.8,\"vegetation_feature\":{\"feature\":\"minecraft:moss_vegetation\",\"placement\":[]},\"vertical_range\":5}}");
        f.put(cf + "moss_vegetation.json", "{\"type\":\"minecraft:simple_block\",\"config\":{\"to_place\":{\"type\":\"minecraft:weighted_state_provider\",\"entries\":["
                + "{\"data\":{\"Name\":\"minecraft:flowering_azalea\"},\"weight\":4},{\"data\":{\"Name\":\"minecraft:azalea\"},\"weight\":7},{\"data\":{\"Name\":\"minecraft:moss_carpet\"},\"weight\":25}]}}}");
        f.put(cf + "spore_blossom.json", "{\"type\":\"minecraft:simple_block\",\"config\":{\"to_place\":{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:spore_blossom\"}}}}");
        // vanilla's cave carver: its debug states (glass, a button, stained glass, a candle) are not terrain, its replaceable tag is
        f.put("minecraft:worldgen/configured_carver/cave.json", "{\"type\":\"minecraft:cave\",\"config\":{\"debug_settings\":{\"air_state\":{\"Name\":\"minecraft:crimson_button\","
                + "\"Properties\":{\"face\":\"wall\",\"facing\":\"north\",\"powered\":\"false\"}},\"barrier_state\":{\"Name\":\"minecraft:glass\"},\"lava_state\":{\"Name\":\"minecraft:orange_stained_glass\"},"
                + "\"water_state\":{\"Name\":\"minecraft:candle\",\"Properties\":{\"candles\":\"1\",\"lit\":\"false\",\"waterlogged\":\"false\"}}},\"floor_level\":{\"type\":\"minecraft:uniform\","
                + "\"max_exclusive\":-0.4,\"min_inclusive\":-1.0},\"lava_level\":{\"above_bottom\":8},\"probability\":0.15,\"replaceable\":\"#minecraft:overworld_carver_replaceables\"}}");
        f.put("minecraft:worldgen/noise_settings/overworld.json", "{\"default_block\":{\"Name\":\"minecraft:stone\"},\"default_fluid\":{\"Name\":\"minecraft:water\",\"Properties\":{\"level\":\"0\"}}}");
        return f;
    }

    /** The fixture's synced block tags (vanilla 1.21.1's members, trimmed): the carver tag and c:ores. */
    static final Map<String, Set<String>> TAGS = Map.of(
            "minecraft:overworld_carver_replaceables", Set.of("minecraft:stone", "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff",
                    "minecraft:deepslate", "minecraft:calcite", "minecraft:water", "minecraft:suspicious_gravel", "minecraft:iron_ore", "minecraft:raw_iron_block", "minecraft:raw_copper_block"),
            "c:ores", Set.of("minecraft:coal_ore", "minecraft:iron_ore", "minecraft:redstone_ore"),
            "minecraft:stairs", Set.of("minecraft:oak_stairs"),
            "minecraft:impermeable", Set.of("minecraft:glass", "minecraft:orange_stained_glass"),
            "minecraft:candles", Set.of("minecraft:candle"),
            "minecraft:buttons", Set.of("minecraft:crimson_button"));
    static final List<String> STRUCTURE_TAGS = List.of("minecraft:stairs", "minecraft:impermeable", "minecraft:candles", "minecraft:buttons");
    static final List<String> NEVER_TAGS = List.of("c:ores");

    /** The fixture's block loot (the loot book from the jars): block -> the items it drops, as vanilla's tables. */
    static final Map<String, Set<String>> DROPS = Map.of(
            "minecraft:coal_ore", Set.of("minecraft:coal", "minecraft:coal_ore"), "minecraft:iron_ore", Set.of("minecraft:raw_iron", "minecraft:iron_ore"),
            "minecraft:redstone_ore", Set.of("minecraft:redstone", "minecraft:redstone_ore"), "minecraft:redstone_wire", Set.of("minecraft:redstone"),
            "minecraft:glowstone", Set.of("minecraft:glowstone_dust"), "minecraft:raw_iron_block", Set.of("minecraft:raw_iron_block"),
            "minecraft:raw_copper_block", Set.of("minecraft:raw_copper_block"), "minecraft:dripstone_block", Set.of("minecraft:dripstone_block"),
            "minecraft:polished_andesite", Set.of("minecraft:polished_andesite"));

    /**
     * The fixture's block universe with the game's facts as vanilla 1.21.1 has them: {block entity, station, crafting
     * recipe}. Crafted: dripstone_block (4 pointed dripstone), amethyst_block (4 shards), moss_carpet (2 moss), andesite /
     * diorite / granite, raw_iron_block / raw_copper_block (9 raw), glowstone (4 dust), redstone_wire (its item, redstone,
     * from a redstone block), the built things (polished_andesite, oak_stairs, candle, the crafting table).
     */
    static Map<String, boolean[]> universe() {
        Map<String, boolean[]> u = new LinkedHashMap<>();
        boolean[] plain = {false, false, false}, crafted = {false, false, true};
        for (String b : List.of("pointed_dripstone", "budding_amethyst", "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud",
                "amethyst_cluster", "calcite", "smooth_basalt", "moss_block", "glow_lichen", "spore_blossom", "azalea", "flowering_azalea",
                "stone", "tuff", "deepslate", "air", "water", "coal_ore", "iron_ore", "redstone_ore", "glass", "orange_stained_glass")) u.put("minecraft:" + b, plain);
        for (String b : List.of("dripstone_block", "amethyst_block", "moss_carpet", "andesite", "diorite", "granite", "raw_iron_block", "raw_copper_block",
                "glowstone", "redstone_wire", "polished_andesite", "oak_stairs", "candle", "crimson_button")) u.put("minecraft:" + b, crafted);
        u.put("minecraft:suspicious_gravel", new boolean[]{true, false, false});
        u.put("minecraft:crafting_table", new boolean[]{false, true, true});
        return u;
    }

    static WorldGenFiles.Read read(boolean withRegistry) {
        List<WorldGenFiles.Entry> es = new ArrayList<>();
        for (var e : files().entrySet()) es.add(StructureBlocksTest.entry(e.getKey(), StructureBlocksTest.utf8(e.getValue())));
        Set<String> registry = universe().keySet();               // the game's block registry, for the fixture
        return withRegistry ? WorldGenFiles.read(es, registry::contains) : WorldGenFiles.read(es);
    }

    static final Set<String> ORE_ITEMS = WorldGenFiles.oreItems(TAGS.get("c:ores"), b -> DROPS.getOrDefault(b, Set.of()));

    /** StructureTable's rule over the fixture; {@code r} null: the world-gen read not done; {@code loot} false: the loot book not in. */
    static Structure fact(String id, WorldGenFiles.Read r, boolean loot) {
        boolean[] f = universe().get(id);
        Boolean ore = loot ? !Collections.disjoint(DROPS.getOrDefault(id, Set.of()), ORE_ITEMS) : null;
        return WorldGenFiles.classify(STRUCTURE_TAGS, NEVER_TAGS, t -> TAGS.getOrDefault(t, Set.of()).contains(id),
                r == null ? null : List.copyOf(r.carverTags()), f[0], f[1], f[2], false, r == null ? null : r.blocks(), ore, id);
    }

    static Set<String> structureSet(WorldGenFiles.Read r) {
        Set<String> out = new TreeSet<>();
        for (String id : universe().keySet()) if (fact(id, r, true) == Structure.YES) out.add(id);
        return out;
    }

    @Test
    void theCauseTheStateNamesAloneMissDripstoneSoTheTableCalledItAStructure() {
        Set<String> namesOnly = read(false).blocks();
        assertFalse(namesOnly.contains("minecraft:dripstone_block"), "no feature JSON names dripstone_block as a block state (the dripstone features place it in code)");
        assertEquals(Structure.YES, WorldGenFiles.fact(false, false, false, false, true, namesOnly, "minecraft:dripstone_block"), "crafted and, by the state Names alone, never world-gen: CJ's refusal");
    }

    /** The manager's test: the structure set holds no block any of the three sources names. */
    @Test
    void noStructureBlockIsNamedByAnyOfTheThreeSources() {
        WorldGenFiles.Read r = read(true);
        assertTrue(r.blocks().contains("minecraft:dripstone_block"), "named by glow_lichen's can_be_placed_on: terrain world gen grows on");
        assertEquals(Set.of("minecraft:overworld_carver_replaceables"), r.carverTags(), "the carver's replaceable tag, from its JSON");
        Set<String> structure = structureSet(r);
        String text = String.join("\n", files().values().stream().filter(t -> !t.contains("debug_settings")).toList());   // (1) the features and noise settings, raw
        for (String id : structure) {
            assertFalse(text.contains("\"" + id + "\""), id + ": a structure block the world-gen features name");
            for (String t : r.carverTags()) assertFalse(TAGS.getOrDefault(t, Set.of()).contains(id) && !universe().get(id)[0], id + ": in the carver tag #" + t);
            assertTrue(Collections.disjoint(DROPS.getOrDefault(id, Set.of()), ORE_ITEMS), id + ": drops an ore family's item");
        }
        assertEquals(Set.of("minecraft:candle", "minecraft:crafting_table", "minecraft:crimson_button", "minecraft:glass", "minecraft:glowstone", "minecraft:oak_stairs",
                        "minecraft:orange_stained_glass", "minecraft:polished_andesite", "minecraft:suspicious_gravel"), structure,
                "the built things stay structure blocks, and glowstone (no source names it); the carver's debug states do not count as terrain");
        for (String id : List.of("minecraft:dripstone_block", "minecraft:amethyst_block", "minecraft:moss_carpet", "minecraft:amethyst_cluster", "minecraft:pointed_dripstone"))
            assertEquals(Structure.NO, fact(id, r, true), id + ": (1) the world-gen names");
        for (String id : List.of("minecraft:raw_iron_block", "minecraft:raw_copper_block"))
            assertEquals(Structure.NO, fact(id, r, true), id + ": (2) the ore veins' block, in the carver tag");
        assertEquals(Structure.NO, fact("minecraft:redstone_wire", r, true), "(3) drops redstone, redstone_ore's item");
        assertEquals(Structure.YES, fact("minecraft:suspicious_gravel", r, true), "a block entity in the carver tag stays a structure block");
    }

    @Test
    void glowstoneStaysRefusedAndSaysWhy() {
        WorldGenFiles.Read r = read(true);
        assertEquals(Structure.YES, fact("minecraft:glowstone", r, true));
        assertEquals("not placed by any feature the jars name", WorldGenFiles.structureWhy(null, false, false), "the dropped list's reason (PolicyRuntime's rule line)");
        assertEquals("tag #minecraft:stairs", WorldGenFiles.structureWhy("minecraft:stairs", false, false));
    }

    @Test
    void eachSourceNotLoadedIsNotLoadedNeverNo() {
        WorldGenFiles.Read r = read(true);
        assertEquals(Structure.NOT_LOADED, fact("minecraft:glowstone", r, false), "no source names it and the loot book is not in: not loaded (rule 15)");
        assertEquals(Structure.NO, fact("minecraft:dripstone_block", r, false), "one source says yes: terrain without the loot book");
        assertEquals(Structure.NO, fact("minecraft:raw_iron_block", r, false), "the carver tag says yes");
        assertEquals(Structure.NOT_LOADED, fact("minecraft:raw_iron_block", null, true), "the world-gen read not done: the carver tags are not known either");
        assertEquals(Structure.YES, fact("minecraft:oak_stairs", null, false), "a tag needs no world-gen source");
        assertNull(WorldGenFiles.natural(Set.of(), Boolean.FALSE, null, "x"));
        assertEquals(Boolean.FALSE, WorldGenFiles.natural(Set.of(), Boolean.FALSE, Boolean.FALSE, "x"));
    }

    @Test
    void typeIdsAndTagsAreNotTakenAsBlocks() {
        Set<String> gen = read(true).blocks();
        assertFalse(gen.contains("minecraft:pointed_dripstone"), "a feature type id names code, not a placed block (pointed_dripstone is terrain by having no recipe)");
        assertFalse(gen.stream().anyMatch(s -> s.startsWith("#")), "tags are not block ids: " + gen);
        assertTrue(gen.contains("minecraft:glow_lichen"), "a multiface feature's plain block id");
        assertTrue(gen.contains("minecraft:air"), "a matching_blocks predicate's block list");
        assertFalse(gen.contains("minecraft:moss_vegetation"), "a feature reference is not in the block registry");
        for (String d : List.of("minecraft:glass", "minecraft:candle", "minecraft:crimson_button", "minecraft:orange_stained_glass"))
            assertFalse(gen.contains(d), d + ": a carver's debug state is not terrain");
    }

    @Test
    void theCacheKeepsTheCarverTagsAndAnOldVersionIsReadAgain() {
        String hash = "0123456789abcdef";
        WorldGenFiles.Read r = read(true);
        String cache = WorldGenFiles.toCache(hash, r);
        WorldGenFiles.Read back = WorldGenFiles.fromCache(cache, hash);
        assertEquals(r.blocks(), back.blocks());
        assertEquals(r.carverTags(), back.carverTags());
        for (int v : new int[]{6, 7}) {
            String old = cache.replaceFirst("\"version\":" + WorldGenFiles.CACHE_VERSION, "\"version\":" + v);
            assertThrows(IllegalArgumentException.class, () -> WorldGenFiles.fromCache(old, hash), "a version-" + v + " cache is not used");
        }
    }

    @Test
    void theOverrideFileIsGone() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/guard/WorldGenFiles.java"));
        assertFalse(src.contains("OVERRIDE_FILE") || src.contains("public static Map<String, Boolean> override("), "no owner's block list (rule 4)");
    }

    static BlockFacts cell(String id, Structure s) {
        return new BlockFacts(true, id, false, false, Fluid.NONE, Fluid.NONE, Fluid.NONE, Fluid.NONE, false, false, false, s, false);
    }

    /** CJ's strip: branch right at 18, feet 6 16 24, next cell 6 16 25 a dripstone_block, outside every area (singleplayer). */
    @Test
    void dripstoneAtTheStripsNextCellIsBroken() {
        Snapshot sp = Snapshot.of(Policy.EMPTY, false);
        WorldGenFiles.Read r = read(true);
        for (String id : List.of("minecraft:dripstone_block", "minecraft:raw_copper_block")) {
            Structure s = fact(id, r, true);
            for (Intent in : List.of(Intent.MINE, Intent.TRAVEL)) {
                Verdict v = BreakPolicy.judge(sp, new Ask(Action.BREAK, in, OW, 6, 16, 25, cell(id, s), Where.DRY));
                assertTrue(v.allowed(), id + " " + in + ": " + v.text());
            }
            assertTrue(BreakPolicy.pathStructure(sp, OW, 6, 16, 25, BreakPolicy.cellStructure(true, s, OW, 6, 16, 25)).allowed(), id + ": the A* hook's ask: open");
        }
        // before: the state Names alone -> CJ's line
        Structure before = WorldGenFiles.fact(false, false, false, false, true, read(false).blocks(), "minecraft:dripstone_block");
        assertEquals(BreakPolicy.STRUCTURE_OUTSIDE, BreakPolicy.pathStructure(sp, OW, 6, 16, 25, BreakPolicy.cellStructure(true, before, OW, 6, 16, 25)).reason());
        // control: a built block in the same cell is still refused
        Structure built = fact("minecraft:polished_andesite", r, true);
        assertEquals(BreakPolicy.STRUCTURE_OUTSIDE, BreakPolicy.judge(sp, new Ask(Action.BREAK, Intent.MINE, OW, 6, 16, 25, cell("minecraft:polished_andesite", built), Where.DRY)).reason());
    }
}
