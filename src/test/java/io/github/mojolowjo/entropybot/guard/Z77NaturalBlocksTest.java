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
 * The fixture files are the vanilla 1.21.1 configured features' shapes (trimmed to the keys that hold blocks).
 */
class Z77NaturalBlocksTest {
    static final String OW = "minecraft:overworld";

    /** The fixture jar's configured features and noise settings, as vanilla 1.21.1 writes them. */
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
        f.put("minecraft:worldgen/noise_settings/overworld.json", "{\"default_block\":{\"Name\":\"minecraft:stone\"},\"default_fluid\":{\"Name\":\"minecraft:water\",\"Properties\":{\"level\":\"0\"}}}");
        return f;
    }

    /**
     * The fixture's block universe with the game's facts as vanilla 1.21.1 has them: {tagged, oreOrLog, block entity,
     * station, crafting recipe}. dripstone_block (4 pointed dripstone), amethyst_block (4 shards), moss_carpet (2 moss),
     * andesite / diorite / granite are crafted; the last three rows are built things (a crafted leftover, #stairs, a station).
     */
    static Map<String, boolean[]> universe() {
        Map<String, boolean[]> u = new LinkedHashMap<>();
        boolean[] plain = {false, false, false, false, false}, crafted = {false, false, false, false, true};
        for (String b : List.of("pointed_dripstone", "budding_amethyst", "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud",
                "amethyst_cluster", "calcite", "smooth_basalt", "moss_block", "glow_lichen", "spore_blossom", "azalea", "flowering_azalea",
                "stone", "tuff", "deepslate", "air", "water")) u.put("minecraft:" + b, plain);
        for (String b : List.of("dripstone_block", "amethyst_block", "moss_carpet", "andesite", "diorite", "granite")) u.put("minecraft:" + b, crafted);
        u.put("minecraft:polished_andesite", crafted);
        u.put("minecraft:oak_stairs", new boolean[]{true, false, false, false, true});
        u.put("minecraft:crafting_table", new boolean[]{false, false, false, true, true});
        return u;
    }

    static WorldGenFiles.Read read(boolean withRegistry) {
        List<WorldGenFiles.Entry> es = new ArrayList<>();
        for (var e : files().entrySet()) es.add(StructureBlocksTest.entry(e.getKey(), StructureBlocksTest.utf8(e.getValue())));
        Set<String> registry = universe().keySet();               // the game's block registry, for the fixture
        return withRegistry ? WorldGenFiles.read(es, registry::contains) : WorldGenFiles.read(es);
    }

    static Structure fact(String id, Set<String> gen) {
        boolean[] f = universe().get(id);
        return WorldGenFiles.fact(f[0], f[1], f[2], f[3], f[4], gen, id);
    }

    @Test
    void theCauseTheStateNamesAloneMissDripstoneSoTheTableCalledItAStructure() {
        Set<String> namesOnly = read(false).blocks();
        assertFalse(namesOnly.contains("minecraft:dripstone_block"), "no feature JSON names dripstone_block as a block state (the dripstone features place it in code)");
        assertEquals(Structure.YES, fact("minecraft:dripstone_block", namesOnly), "crafted and, by the state Names alone, never world-gen: CJ's refusal");
    }

    @Test
    void noBlockTheTableCallsStructureIsNamedByTheLoadedWorldGenData() {
        Set<String> gen = read(true).blocks();
        assertTrue(gen.contains("minecraft:dripstone_block"), "named by glow_lichen's can_be_placed_on: terrain world gen grows on");
        Set<String> structure = new TreeSet<>();
        for (String id : universe().keySet()) if (fact(id, gen) == Structure.YES) structure.add(id);
        // independent of the reader: the raw text of every loaded world-gen file
        String text = String.join("\n", files().values());
        for (String id : structure) assertFalse(text.contains("\"" + id + "\""), id + " is a structure block but the world-gen data names it");
        assertEquals(Set.of("minecraft:crafting_table", "minecraft:oak_stairs", "minecraft:polished_andesite"), structure,
                "the built things stay structure blocks; every natural cave block (dripstone, amethyst, moss, lush) is terrain");
        for (String id : List.of("minecraft:dripstone_block", "minecraft:amethyst_block", "minecraft:moss_carpet", "minecraft:amethyst_cluster", "minecraft:pointed_dripstone"))
            assertEquals(Structure.NO, fact(id, gen), id);
    }

    @Test
    void typeIdsAndTagsAreNotTakenAsBlocks() {
        Set<String> gen = read(true).blocks();
        assertFalse(gen.contains("minecraft:pointed_dripstone"), "a feature type id names code, not a placed block (pointed_dripstone is terrain by having no recipe)");
        assertFalse(gen.stream().anyMatch(s -> s.startsWith("#")), "tags are not block ids: " + gen);
        assertTrue(gen.contains("minecraft:glow_lichen"), "a multiface feature's plain block id");
        assertTrue(gen.contains("minecraft:air"), "a matching_blocks predicate's block list");
        assertFalse(gen.contains("minecraft:moss_vegetation"), "a feature reference is not in the block registry");
    }

    @Test
    void theCacheVersionMovedSoAnOldSetIsReadAgain() {
        String hash = "0123456789abcdef";
        WorldGenFiles.Read r = read(true);
        String cache = WorldGenFiles.toCache(hash, r);
        assertEquals(r.blocks(), WorldGenFiles.fromCache(cache, hash).blocks());
        String old = cache.replaceFirst("\"version\":" + WorldGenFiles.CACHE_VERSION, "\"version\":6");
        assertThrows(IllegalArgumentException.class, () -> WorldGenFiles.fromCache(old, hash), "a version-6 cache (state Names only) is not used");
    }

    static BlockFacts cell(String id, Structure s) {
        return new BlockFacts(true, id, false, false, Fluid.NONE, Fluid.NONE, Fluid.NONE, Fluid.NONE, false, false, false, s, false);
    }

    /** CJ's strip: branch right at 18, feet 6 16 24, next cell 6 16 25 a dripstone_block, outside every area (singleplayer). */
    @Test
    void dripstoneAtTheStripsNextCellIsBroken() {
        Snapshot sp = Snapshot.of(Policy.EMPTY, false);
        Set<String> gen = read(true).blocks();
        Structure drip = fact("minecraft:dripstone_block", gen);
        for (Intent in : List.of(Intent.MINE, Intent.TRAVEL)) {
            Verdict v = BreakPolicy.judge(sp, new Ask(Action.BREAK, in, OW, 6, 16, 25, cell("minecraft:dripstone_block", drip), Where.DRY));
            assertTrue(v.allowed(), in + ": " + v.text());
        }
        assertTrue(BreakPolicy.pathStructure(sp, OW, 6, 16, 25, BreakPolicy.cellStructure(true, drip, OW, 6, 16, 25)).allowed(), "the A* hook's ask: open");
        // before: the state Names alone -> CJ's line
        Structure before = fact("minecraft:dripstone_block", read(false).blocks());
        assertEquals(BreakPolicy.STRUCTURE_OUTSIDE, BreakPolicy.pathStructure(sp, OW, 6, 16, 25, BreakPolicy.cellStructure(true, before, OW, 6, 16, 25)).reason());
        // control: a built block in the same cell is still refused
        Structure built = fact("minecraft:polished_andesite", gen);
        assertEquals(BreakPolicy.STRUCTURE_OUTSIDE, BreakPolicy.judge(sp, new Ask(Action.BREAK, Intent.MINE, OW, 6, 16, 25, cell("minecraft:polished_andesite", built), Where.DRY)).reason());
    }
}
