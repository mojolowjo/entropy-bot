package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.Nearby;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Action;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Ask;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.BlockFacts;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Snapshot;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Structure;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Verdict;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Where;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** STRUCTURE_BLOCKS.md (owner 2026-10-23, RULES 16): scenarios S27-S30 and the structure fact's three states. */
class StructureBlocksTest {
    static final String OW = "minecraft:overworld";
    /** camp (work, tagged break-structures) holds house (base, untagged); yard (work, use-stations) far off; shrine no-break. */
    static final String POLICY = "{\"areas\":["
            + "{\"name\":\"camp\",\"x1\":0,\"z1\":0,\"x2\":40,\"z2\":40,\"tags\":[\"break-structures\"]},"
            + "{\"name\":\"house\",\"type\":\"base\",\"x1\":10,\"z1\":10,\"x2\":15,\"z2\":15},"
            + "{\"name\":\"yard\",\"x1\":100,\"z1\":0,\"x2\":120,\"z2\":20,\"tags\":[\"use-stations\"]}],"
            + "\"protect\":[{\"name\":\"shrine\",\"x1\":300,\"z1\":300,\"x2\":310,\"z2\":310,\"tags\":[\"break-structures\"]}]}";
    static final Snapshot SP = Snapshot.of(Policy.parse(POLICY), false);
    /** A plank wall block: a structure block (crafted, no world-gen placement). */
    static final BlockFacts WALL = BlockFacts.STONE.withStructure(Structure.YES);

    static Verdict brk(int x, int y, int z, BlockFacts f) {
        return BreakPolicy.judge(SP, new Ask(Action.BREAK, Intent.TRAVEL, OW, x, y, z, f, Where.DRY));
    }

    @Test
    void s27aPlankWallOutsideEveryAreaIsRefusedSoThePathGoesAround() {
        Verdict v = brk(500, 64, 500, WALL);
        assertFalse(v.allowed());
        assertEquals("structure block outside my areas", v.reason());
        assertTrue(Double.isInfinite(v.multiplier()), "the path cost: never through, the walk around is priced longer");
        assertTrue(brk(500, 64, 500, BlockFacts.STONE).allowed(), "plain stone in the same spot is open");
    }

    @Test
    void s28theSameWallPlacedByMeBreaksAtItsPrice() {
        Verdict v = brk(500, 64, 500, WALL.withPlacedByMe(true));
        assertTrue(v.allowed(), v.text());
        assertEquals(brk(500, 64, 500, BlockFacts.STONE).multiplier(), v.multiplier(), 1e-9, "priced like any block");
    }

    @Test
    void s29theWallInsideAnAreaTaggedBreakStructuresBreaks() {
        assertTrue(brk(5, 64, 5, WALL).allowed(), "camp is tagged break-structures");
        Verdict house = brk(12, 64, 12, WALL);
        assertFalse(house.allowed(), "the innermost area (house, untagged) wins over camp's tag");
        assertEquals("structure block in house (no break-structures tag)", house.reason());
        Verdict shrine = brk(305, 64, 305, WALL);
        assertFalse(shrine.allowed(), "no-break stays absolute, whatever its tags");
        assertTrue(shrine.reason().contains("no-break"), shrine.reason());
    }

    @Test
    void s30aWildFurnaceIsNoOptionMyFurnaceInAUseStationsAreaRanksFirst() {
        Policy p = Policy.parse(POLICY);
        Verdict wild = BreakPolicy.stationUse(p, OW, 3, 64, -50, false);
        assertFalse(wild.allowed());
        assertEquals("wild station (not placed by me, outside my areas)", wild.reason());
        assertTrue(BreakPolicy.stationPreferred(BreakPolicy.stationUse(p, OW, 110, 64, 10, true)));
        Set<String> furnace = Set.of("minecraft:furnace");
        Map<String, String> world = Map.of("3 64 -50", "minecraft:furnace", "110 64 10", "minecraft:furnace", "20 64 20", "minecraft:furnace");
        var s = new Nearby.Scan() {
            @Override public List<int[]> logs(int radius) { return List.of(); }
            @Override public List<int[]> find(Set<String> blocks, int radius) { return List.of(new int[]{3, 64, -50}, new int[]{20, 64, 20}); }
            @Override public boolean isLog(String block) { return false; }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
            @Override public boolean allowed(int x, int y, int z) { return true; }
            @Override public String blockAt(int x, int y, int z) { return world.get(x + " " + y + " " + z); }
            @Override public Boolean open(int x, int y, int z) { return true; }   // stated: every face open (legit sources: NearbyExposureTest)
            @Override public List<Nearby.Mine> own() { return List.of(new Nearby.Mine(110, 64, 10, "minecraft:furnace")); }
            // no stationUse override: the scan states the owner's policy, Scan.stationUse asks BreakPolicy itself
            @Override public Policy stationPolicy() { return p; }
            @Override public String stationDim() { return OW; }
        };
        Nearby.Lookup l = Nearby.lookup(s, new int[]{0, 64, 0}, furnace);
        assertEquals(List.of(110, 20), l.spots().stream().map(Spot::x).toList(), "mine in yard (use-stations bonus) first, then the one in camp; the wild one is gone");
        assertEquals(1, l.skipped().size());
        assertTrue(l.skipped().get(0).contains("3 64 -50") && l.skipped().get(0).contains("wild station"), l.skipped().get(0));
        assertTrue(l.priced().get(0).startsWith("110 64 10: use-stations -"), "the bonus is said in the lookup's notes: " + l.priced());
        assertEquals(io.github.mojolowjo.entropybot.aa.brain.Tuning.get("nearby.useStationsS"), l.spots().get(0).bonusS(), 1e-9, "a price in seconds, from Tuning");
    }

    @Test
    void aUseStationsStationFarAwayLosesToOneAtHand() {
        // a furnace of mine in yard 500 blocks out (yard moved there) against one in camp at hand
        Policy far = Policy.parse(POLICY.replace("\"x1\":100,\"z1\":0,\"x2\":120,\"z2\":20", "\"x1\":490,\"z1\":0,\"x2\":510,\"z2\":20"));
        Map<String, String> world = Map.of("500 64 10", "minecraft:furnace", "5 64 5", "minecraft:furnace");
        var s = new Nearby.Scan() {
            @Override public List<int[]> logs(int radius) { return List.of(); }
            @Override public List<int[]> find(Set<String> blocks, int radius) { return List.of(new int[]{5, 64, 5}); }
            @Override public boolean isLog(String block) { return false; }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
            @Override public boolean allowed(int x, int y, int z) { return true; }
            @Override public String blockAt(int x, int y, int z) { return world.get(x + " " + y + " " + z); }
            @Override public Boolean open(int x, int y, int z) { return true; }   // stated: every face open (legit sources: NearbyExposureTest)
            @Override public List<Nearby.Mine> own() { return List.of(new Nearby.Mine(500, 64, 10, "minecraft:furnace")); }
            @Override public Policy stationPolicy() { return far; }
            @Override public String stationDim() { return OW; }
        };
        Nearby.Lookup l = Nearby.lookup(s, new int[]{0, 64, 0}, Set.of("minecraft:furnace"));
        assertEquals(List.of(5, 500), l.spots().stream().map(Spot::x).toList(), "the bonus is seconds off the walk, not a rank: 500 blocks lose");
        assertEquals(1, l.priced().size(), "the far one still carries its bonus, said");
    }

    @Test
    void aScanWithNoPolicyRefusesEveryStation() {
        var s = new Nearby.Scan() {
            @Override public List<int[]> logs(int radius) { return List.of(); }
            @Override public List<int[]> find(Set<String> blocks, int radius) { return List.of(new int[]{1, 64, 1}); }
            @Override public boolean isLog(String block) { return false; }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
            @Override public boolean allowed(int x, int y, int z) { return true; }
            @Override public String blockAt(int x, int y, int z) { return "minecraft:furnace"; }
            @Override public Boolean open(int x, int y, int z) { return true; }   // stated: every face open (legit sources: NearbyExposureTest)
            @Override public List<Nearby.Mine> own() { return List.of(); }
        };
        Nearby.Lookup l = Nearby.lookup(s, new int[]{0, 64, 0}, Set.of("minecraft:furnace"));
        assertTrue(l.spots().isEmpty());
        assertTrue(l.skipped().get(0).endsWith("no policy"), l.skipped().toString());
    }

    // ---- 0.39.z26 fix round: the click, the path line and the facts reader through their real halves ----

    static final Policy P = Policy.parse(POLICY);

    /** The click: GuardCore's break branch with the guard's real policy and the facts the one reader gives (no withStructure shortcut). */
    @Test
    void theClicksStructureBranchAsksThePolicyWithTheReadersFacts() {
        GuardCore g = new GuardCore();
        g.setPolicy(P);
        OwnPlaced.cells = (d, x, y, z) -> OW.equals(d) && x == 500 && y == 64 && z == 501;
        try {
            // Guard.infoFor's pure half: a structure block outside every area, not mine
            GuardCore.BlockInfo wall = GuardCore.BlockInfo.ofFacts(true, BreakPolicy.cellStructure(true, Structure.YES, OW, 500, 64, 500));
            assertEquals("structure block outside my areas", g.checkUnlogged(OW, 500, 64, 500, "break", wall).reason());
            GuardCore.BlockInfo mine = GuardCore.BlockInfo.ofFacts(true, BreakPolicy.cellStructure(true, Structure.YES, OW, 500, 64, 501));
            assertTrue(mine.placedByMe(), "the ledger of the caller's dimension");
            assertTrue(g.checkUnlogged(OW, 500, 64, 501, "break", mine).allowed(), "placed by me: breaks anywhere");
            GuardCore.BlockInfo nether = GuardCore.BlockInfo.ofFacts(true, BreakPolicy.cellStructure(true, Structure.YES, "minecraft:the_nether", 500, 64, 501));
            assertFalse(nether.placedByMe(), "another dimension's cell is not my placement");
            assertTrue(g.checkUnlogged(OW, 5, 64, 5, "break", wall).allowed(), "camp is tagged break-structures");
            assertEquals("not loaded yet", g.checkUnlogged(OW, 5, 64, 5, "break", GuardCore.BlockInfo.ofFacts(false, null)).reason(), "infoFor of an unloaded cell");
            assertEquals("not loaded yet", g.checkUnlogged(OW, 5, 64, 5, "break", GuardCore.BlockInfo.ofFacts(true, BreakPolicy.cellStructure(true, null, OW, 5, 64, 5))).reason(),
                    "the table not built: not loaded");
            assertTrue(g.checkUnlogged(OW, 500, 64, 500, "break", GuardCore.BlockInfo.ofFacts(true, BreakPolicy.cellStructure(true, Structure.NO, OW, 500, 64, 500))).allowed());
        } finally {
            OwnPlaced.cells = null;
        }
    }

    /** CostAdvisorHook's structure line and the non-travel A* hook (BreakPolicy.pathStructure): no snapshot refuses. */
    @Test
    void thePathLineRefusesWithNoSnapshotAndAsksTheSameRule() {
        Verdict none = BreakPolicy.pathStructure(null, OW, 0, 64, 0, BreakPolicy.cellStructure(true, Structure.NO, OW, 0, 64, 0));
        assertFalse(none.allowed());
        assertEquals("no snapshot", none.reason());
        assertFalse(Double.isNaN(none.multiplier()));
        assertEquals("structure block outside my areas", BreakPolicy.pathStructure(SP, OW, 500, 64, 500, BreakPolicy.cellStructure(true, Structure.YES, OW, 500, 64, 500)).reason());
        assertTrue(BreakPolicy.pathStructure(SP, OW, 5, 64, 5, BreakPolicy.cellStructure(true, Structure.YES, OW, 5, 64, 5)).allowed());
        assertEquals("not loaded yet", BreakPolicy.pathStructure(SP, OW, 5, 64, 5, BreakPolicy.cellStructure(false, Structure.NO, OW, 5, 64, 5)).reason());
        assertTrue(BreakPolicy.structureRefusal(BreakPolicy.pathStructure(SP, OW, 500, 64, 500, new BreakPolicy.StructureFacts(Structure.YES, false))), "typed kind, not a string");
    }

    /** #planks through the table's own rule (WorldGenFiles.classify) over a fixture tag holder. */
    @Test
    void planksAreNeverStructureThroughTheTablesTagRule() throws Exception {
        // the table's own never list (the unit tests have no game classes, so its source line is read): it must hold BlockTags.PLANKS
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/guard/StructureTable.java"));
        var m = java.util.regex.Pattern.compile("NEVER_TAGS = List\\.of\\(([^)]*)\\)").matcher(src);
        assertTrue(m.find(), "StructureTable keeps its NEVER_TAGS list");
        for (String k : List.of("BlockTags.PLANKS", "BlockTags.LOGS", "Tags.Blocks.ORES")) assertTrue(m.group(1).contains(k), "NEVER_TAGS holds " + k + ": " + m.group(1));
        assertTrue(src.contains("WorldGenFiles.classify(STRUCTURE_TAGS, NEVER_TAGS, s::is"), "the table classifies through the rule tested below");
        // fixture: a small tag holder like the game's (planks.json, impermeable.json, stairs.json), the table's rule over it
        Map<String, Set<String>> tags = Map.of("#planks", Set.of("oak_planks", "spruce_planks"), "#impermeable", Set.of("glass"), "#stairs", Set.of("oak_stairs"));
        List<String> structure = List.of("#stairs", "#impermeable"), never = List.of("#ores", "#logs", "#planks");
        java.util.function.Function<String, java.util.function.Predicate<String>> of = b -> t -> tags.getOrDefault(t, Set.of()).contains(b);
        Set<String> gen = Set.of("stone");
        assertEquals(Structure.NO, WorldGenFiles.classify(structure, never, of.apply("oak_planks"), false, false, true, false, gen, "oak_planks"), "crafted, never world gen, but #planks");
        assertEquals(Structure.YES, WorldGenFiles.classify(structure, never, of.apply("oak_stairs"), false, false, true, false, gen, "oak_stairs"));
        assertEquals(Structure.YES, WorldGenFiles.classify(structure, never, of.apply("glass"), false, false, false, false, gen, "glass"));
        assertEquals(Structure.NOT_LOADED, WorldGenFiles.classify(structure, never, of.apply("oak_stairs"), false, false, true, true, gen, "oak_stairs"),
                "a recipe of it could not be read: not loaded, never not crafted");
    }

    @Test
    void badTagsInTheFileAreSaidNotDropped() {
        Box b = Box.fromJson(com.google.gson.JsonParser.parseString("{\"name\":\"odd\",\"x1\":0,\"z1\":0,\"x2\":1,\"z2\":1,\"tags\":[\"use-stations\",3,{\"a\":1}]}").getAsJsonObject(), OW);
        assertEquals(List.of("use-stations"), b.tags, "the words kept; the two others counted and said (Quiet)");
        Box n = Box.fromJson(com.google.gson.JsonParser.parseString("{\"name\":\"odd2\",\"x1\":0,\"z1\":0,\"x2\":1,\"z2\":1,\"tags\":\"use-stations\"}").getAsJsonObject(), OW);
        assertEquals(List.of(), n.tags);
    }

    @Test
    void theFactHasThreeStatesAndOresAndLogsAreNeverStructures() {
        assertEquals(Structure.NOT_LOADED, BreakPolicy.structureOf(false, true, false, true, true, true, Boolean.FALSE));
        assertEquals(Structure.NO, BreakPolicy.structureOf(true, false, true, true, true, true, Boolean.FALSE), "an ore or a log is never a structure");
        assertEquals(Structure.YES, BreakPolicy.structureOf(true, true, false, false, false, false, Boolean.TRUE), "a tagged block");
        assertEquals(Structure.YES, BreakPolicy.structureOf(true, false, false, true, false, false, Boolean.TRUE), "a block entity");
        assertEquals(Structure.YES, BreakPolicy.structureOf(true, false, false, false, true, false, Boolean.TRUE), "a station");
        assertEquals(Structure.YES, BreakPolicy.structureOf(true, false, false, false, false, true, Boolean.FALSE), "crafted, never placed by world gen");
        assertEquals(Structure.NO, BreakPolicy.structureOf(true, false, false, false, false, true, Boolean.TRUE), "crafted but world gen places it (diorite)");
        assertEquals(Structure.NOT_LOADED, BreakPolicy.structureOf(true, false, false, false, false, true, null), "world gen not loaded yet: refused as not loaded");
        assertEquals(Structure.NO, BreakPolicy.structureOf(true, false, false, false, false, false, null), "not crafted: plain terrain");
        Verdict v = brk(500, 64, 500, BlockFacts.STONE.withStructure(Structure.NOT_LOADED));
        assertFalse(v.allowed());
        assertEquals("not loaded yet", v.reason());
    }

    // ---- 0.39.z26 (2): the world-gen set from the client's jars (a server's client has no world-gen registries) ----

    static WorldGenFiles.Entry entry(String id, byte[] b) {
        return new WorldGenFiles.Entry() {
            @Override public String id() { return id; }
            @Override public java.io.InputStream open() { return new java.io.ByteArrayInputStream(b); }
        };
    }

    static byte[] utf8(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    /**
     * A small in-memory jar laid out like vanilla's data (file names and block states as the vanilla configured features
     * and overworld noise settings write them), plus a village house and a fossil template that must not be read. Every
     * file under the read's own folders (WorldGenFiles.JSON_DIRS) is taken, as JarPacks' listResources gives them.
     */
    static WorldGenFiles.Read jarRead() throws java.io.IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        for (String rock : List.of("diorite", "andesite", "granite"))
            files.put("data/minecraft/worldgen/configured_feature/ore_" + rock + "_upper.json", "{\"type\":\"minecraft:ore\",\"config\":{\"discard_chance_on_air_exposure\":0.0,\"size\":64,\"targets\":["
                    + "{\"state\":{\"Name\":\"minecraft:" + rock + "\"},\"target\":{\"predicate_type\":\"minecraft:tag_match\",\"tag\":\"minecraft:base_stone_overworld\"}}]}}");
        files.put("data/minecraft/worldgen/configured_feature/forest_rock.json", "{\"type\":\"minecraft:forest_rock\",\"config\":{\"state\":{\"Name\":\"minecraft:mossy_cobblestone\"}}}");
        files.put("data/minecraft/worldgen/noise_settings/overworld.json", "{\"default_block\":{\"Name\":\"minecraft:stone\"},\"default_fluid\":{\"Name\":\"minecraft:water\",\"Properties\":{\"level\":\"0\"}},"
                + "\"surface_rule\":{\"type\":\"minecraft:sequence\",\"sequence\":[{\"type\":\"minecraft:condition\",\"if_true\":{\"type\":\"minecraft:biome\",\"biome_is\":[\"minecraft:wooded_badlands\"]},"
                + "\"then_run\":{\"type\":\"minecraft:block\",\"result_state\":{\"Name\":\"minecraft:coarse_dirt\"}}},"
                + "{\"type\":\"minecraft:block\",\"result_state\":{\"Name\":\"minecraft:grass_block\",\"Properties\":{\"snowy\":\"false\"}}}]}}");
        files.put("data/minecraft/worldgen/configured_feature/broken.json", "{not json");
        files.put("data/minecraft/structure/village/plains/houses/plains_small_house_1.nbt", "minecraft:oak_planks");
        files.put("data/minecraft/structure/fossil/spine_1.nbt", "minecraft:bone_block");
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (var e : files.entrySet()) { zip.putNextEntry(new java.util.zip.ZipEntry(e.getKey())); zip.write(utf8(e.getValue())); zip.closeEntry(); }
        }
        List<WorldGenFiles.Entry> json = new java.util.ArrayList<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            for (java.util.zip.ZipEntry z; (z = zip.getNextEntry()) != null; ) {
                String[] p = z.getName().split("/", 3);  // data / ns / path
                if (!p[2].endsWith(".json")) continue;
                for (String d : WorldGenFiles.JSON_DIRS) if (p[2].startsWith(d + "/")) json.add(entry(p[1] + ":" + p[2], zip.readAllBytes()));
            }
        }
        return WorldGenFiles.read(json);
    }

    @Test
    void aServerClientWithTheJarSetDigsThroughCraftedTerrainButNotBuiltBlocks() throws Exception {
        WorldGenFiles.Read r = jarRead();
        assertEquals(5, r.files(), "the five world-gen JSON files read; the templates are not");
        assertEquals(1, r.bad(), "the broken file is counted, not thrown");
        assertEquals(Set.of("minecraft:diorite", "minecraft:andesite", "minecraft:granite", "minecraft:mossy_cobblestone", "minecraft:stone",
                "minecraft:water", "minecraft:coarse_dirt", "minecraft:grass_block"), r.blocks(), "the feature and noise JSON's block-state names, nothing from the templates");
        Set<String> gen = r.blocks();
        for (String b : List.of("diorite", "andesite", "granite", "coarse_dirt", "mossy_cobblestone"))
            assertEquals(Structure.NO, WorldGenFiles.fact(false, false, false, false, true, gen, "minecraft:" + b), b + ": crafted, but world gen places it");
        // owner 2026-10-23: #minecraft:planks is never structure (StructureTable.NEVER_TAGS). The unit tests have no game jar,
        // so this is the 1.21 game's data/minecraft/tags/block/planks.json as a test fixture (the mod reads the tag, not this).
        List<String> planks = new java.util.ArrayList<>();
        for (var v : com.google.gson.JsonParser.parseString("{\"values\":[\"minecraft:oak_planks\",\"minecraft:spruce_planks\",\"minecraft:birch_planks\","
                + "\"minecraft:jungle_planks\",\"minecraft:acacia_planks\",\"minecraft:dark_oak_planks\",\"minecraft:crimson_planks\",\"minecraft:warped_planks\","
                + "\"minecraft:mangrove_planks\",\"minecraft:bamboo_planks\",\"minecraft:cherry_planks\"]}").getAsJsonObject().getAsJsonArray("values")) planks.add(v.getAsString());
        for (String p : planks)
            assertEquals(Structure.NO, WorldGenFiles.fact(false, true, false, false, true, gen, p), p + ": in #planks, never structure");
        assertEquals(Structure.YES, WorldGenFiles.fact(false, false, false, true, true, gen, "minecraft:crafting_table"), "a station");
        assertEquals(Structure.YES, WorldGenFiles.fact(true, false, false, false, false, gen, "minecraft:glass"), "glass: #impermeable");
        assertEquals(Structure.YES, WorldGenFiles.fact(false, false, false, false, true, gen, "minecraft:bone_block"), "bone block: crafted; the fossil template is a built thing, not read");
        String hash = "0123456789abcdef";
        assertEquals(gen, WorldGenFiles.fromCache(WorldGenFiles.toCache(hash, r), hash).blocks(), "the cache gives the same set back");
        assertThrows(IllegalArgumentException.class, () -> WorldGenFiles.fromCache(WorldGenFiles.toCache(hash, r), "fedcba9876543210"), "another mod list's cache is not used");
    }

    @Test
    void notLoadedRefusesAndCachesNothing() {
        Structure s = WorldGenFiles.fact(false, false, false, false, true, null, "minecraft:diorite");
        assertEquals(Structure.NOT_LOADED, s, "a crafted block before the read: not loaded, never no");
        assertEquals("not loaded yet", brk(500, 64, 500, BlockFacts.STONE.withStructure(s)).reason());
        assertEquals(Structure.NO, WorldGenFiles.fact(false, false, false, false, false, null, "minecraft:dirt"), "not crafted: no world-gen set needed");
        assertEquals(Structure.YES, WorldGenFiles.fact(false, false, false, false, true, Set.of(), "minecraft:diorite"), "loaded and empty: crafted blocks refused");
    }

    @Test
    void theOverrideFileAddsAndRemovesOneIdEach() throws Exception {
        var o = WorldGenFiles.override("{\"minecraft:bone_block\": true, \"diorite\": false}");
        Set<String> gen = WorldGenFiles.apply(jarRead().blocks(), o);
        assertEquals(Structure.NO, WorldGenFiles.fact(false, false, false, false, true, gen, "minecraft:bone_block"), "added");
        assertEquals(Structure.YES, WorldGenFiles.fact(false, false, false, false, true, gen, "minecraft:diorite"), "removed");
        assertThrows(IllegalArgumentException.class, () -> WorldGenFiles.override("{\"minecraft:glass\": 1}"), "a broken entry is named, the file not used");
    }

    @Test
    void areaTagsParseSaveAndRefuseUnknownWords() {
        Policy p = Policy.parse(POLICY);
        assertEquals(List.of("break-structures"), p.innermostAreaAt(OW, 5, 64, 5).tags);
        assertEquals(List.of(), p.innermostAreaAt(OW, 12, 64, 12).tags, "no tags in the file: an empty list");
        assertTrue(p.areas.get(0).toJson().toString().contains("\"tags\":[\"break-structures\"]"));
        assertNull(AreaTags.unknown(List.of("use-stations", "BREAK-STRUCTURES")));
        String bad = AreaTags.unknown(List.of("burn"));
        assertTrue(bad.contains("burn") && bad.contains("break-structures") && bad.contains("use-stations"), bad);
    }
}
