package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.guard.BreakPolicy.Structure;
import io.github.mojolowjo.entropybot.guard.BuiltLogs.Mark;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner's ruling 2026-11-23 (worlds run 2, w058: the bot cut at a savanna village building's 51 acacia_wood cells):
 * village logs are protected, a natural tree stays cuttable. The two halves: the block kind (WorldGenFiles.classify with
 * the tree tags, WorldGenFiles.mark) and the cell (BuiltLogs.of). The unit tests have no game classes, so the tags and
 * the world-gen set are fixtures with vanilla 1.21.1's facts.
 */
class BuiltLogsTest {
    static final String OAK = "minecraft:oak_log", ACACIA = "minecraft:acacia_log", WOOD = "minecraft:acacia_wood", STRIPPED = "minecraft:stripped_oak_log",
            PLANKS = "minecraft:oak_planks", STAIRS = "minecraft:oak_stairs", TABLE = "minecraft:crafting_table", NEST = "minecraft:bee_nest",
            LEAVES = "minecraft:oak_leaves", DIRT = "minecraft:dirt", CARPET = "minecraft:moss_carpet", IRON = "minecraft:iron_ore", PANE = "minecraft:glass_pane";

    /** The fixture's tags (the game's members, trimmed). */
    static final Map<String, Set<String>> TAGS = Map.of(
            "#logs", Set.of(OAK, ACACIA, WOOD, STRIPPED),
            "#planks", Set.of(PLANKS),
            "#stairs", Set.of(STAIRS),
            "c:ores", Set.of(IRON));
    static final List<String> STRUCTURE = List.of("#stairs"), NEVER = List.of("c:ores", "#planks"), TREE = List.of("#logs"), CARVER = List.of();
    /** What the jars' world gen names: the tree features' logs and leaves, the ground, the ore, the moss patch's carpet. */
    static final Set<String> GEN = Set.of(OAK, ACACIA, LEAVES, DIRT, IRON, CARPET);
    /** Blocks a crafting recipe makes. */
    static final Set<String> CRAFTED = Set.of(WOOD, PLANKS, STAIRS, TABLE, CARPET, PANE);

    static Structure fact(String id, Set<String> gen) {
        return WorldGenFiles.classify(STRUCTURE, NEVER, t -> TAGS.getOrDefault(t, Set.of()).contains(id), gen == null ? null : CARVER,
                id.equals(NEST), id.equals(TABLE), CRAFTED.contains(id), false, gen, id, TREE);
    }

    static Mark mark(String id, Set<String> gen) {
        return WorldGenFiles.mark(STRUCTURE, TREE, t -> TAGS.getOrDefault(t, Set.of()).contains(id), gen == null ? null : CARVER,
                id.equals(TABLE), CRAFTED.contains(id), false, gen, id);
    }

    // ---- the block kind ----

    @Test
    void aLogWorldGenPlacesIsATreesLog_aLogNoFeatureNamesIsAStructureBlock() {
        assertEquals(Structure.NO, fact(OAK, GEN), "the tree features name oak_log: a tree's log by its block");
        assertEquals(Structure.NO, fact(ACACIA, GEN));
        assertEquals(Structure.YES, fact(WOOD, GEN), "w058: acacia_wood, placed by no tree feature, stands only in a built thing");
        assertEquals(Structure.YES, fact(STRIPPED, GEN), "a stripped log has no recipe and no feature: a built thing's all the same");
        assertEquals(Structure.NOT_LOADED, fact(OAK, null), "rule 15: the world-gen read not done, a log is not known, never a tree and never a structure");
        assertEquals(Structure.NOT_LOADED, fact(WOOD, null));
        assertEquals(Structure.NO, fact(IRON, GEN), "an ore stays never a structure");
        assertEquals(Structure.NO, fact(IRON, null));
        assertEquals(Structure.NO, fact(PLANKS, GEN), "planks stay never a structure (owner 2026-10-23): still breakable");
        assertEquals(Structure.YES, fact(STAIRS, GEN));
        assertEquals(WorldGenFiles.LOG_NOT_PLACED, WorldGenFiles.structureWhy(null, false, false, true), "the reason of such a log");
        assertEquals("tag #minecraft:stairs", WorldGenFiles.structureWhy("minecraft:stairs", false, false, false));
    }

    @Test
    void theMarksComeFromTheGamesFacts() {
        assertEquals(Mark.TREE_LOG, mark(OAK, GEN));
        assertEquals(Mark.BUILT, mark(WOOD, GEN), "a log no feature names is itself a block only a build has");
        assertEquals(Mark.BUILT, mark(STRIPPED, GEN));
        assertEquals(Mark.BUILT, mark(PLANKS, GEN), "planks: crafted, named by no world-gen source; the never tag is about breaking them, not about what they show");
        assertEquals(Mark.BUILT, mark(STAIRS, GEN));
        assertEquals(Mark.BUILT, mark(TABLE, GEN));
        assertEquals(Mark.BUILT, mark(PANE, GEN));
        assertEquals(Mark.PLAIN, mark(NEST, GEN), "a bee nest is a block entity no recipe makes: it hangs on a tree, no sign of a build");
        assertEquals(Mark.PLAIN, mark(CARPET, GEN), "crafted, but world gen places it");
        assertEquals(Mark.PLAIN, mark(LEAVES, GEN));
        assertEquals(Mark.PLAIN, mark(DIRT, GEN));
        // rule 15: before the world-gen read a made block is not known; a block nothing makes is plain either way
        assertEquals(Mark.NOT_KNOWN, mark(OAK, null));
        assertEquals(Mark.NOT_KNOWN, mark(PLANKS, null));
        assertEquals(Mark.PLAIN, mark(DIRT, null));
        assertEquals(Mark.NOT_KNOWN, WorldGenFiles.mark(STRUCTURE, TREE, t -> false, CARVER, false, false, true, GEN, "mod:thing"),
                "a recipe of it could not be read: not known, never 'not crafted'");
    }

    @Test
    void theTableAndTheOneReaderUseTheRule() throws Exception {
        String table = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/guard/StructureTable.java"));
        assertTrue(table.contains("TREE_TAGS = List.of(BlockTags.LOGS)"), "the tree tags are the game's logs tag key");
        assertTrue(java.util.regex.Pattern.compile("WorldGenFiles\\.classify\\(STRUCTURE_TAGS, NEVER_TAGS, s::is,[^;]*TREE_TAGS\\)").matcher(table).find(), "the table classifies with the tree tags");
        assertTrue(table.contains("WorldGenFiles.mark(STRUCTURE_TAGS, TREE_TAGS, s::is"), "the marks come from the same facts");
        String reader = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/guard/PolicyRuntime.java"));
        assertTrue(reader.contains("StructureTable.mark(st.getBlock()) == BuiltLogs.Mark.TREE_LOG) s = builtLog("), "the one structure-facts reader asks the cells of a tree's log");
    }

    // ---- the cell ----

    /** A small world of block ids; {@code mine}: the cells the bot placed itself; {@code unloaded}: the cells not loaded. */
    static final class World implements BuiltLogs.Cells {
        final Map<Long, String> cells = new HashMap<>();
        final Set<Long> mine = new HashSet<>(), unloaded = new HashSet<>();
        int reads;

        World put(int x, int y, int z, String id) { cells.put(BuiltLogs.key(x, y, z), id); return this; }
        World column(int x, int y0, int y1, int z, String id) { for (int y = y0; y <= y1; y++) put(x, y, z, id); return this; }

        @Override public Mark at(int x, int y, int z) {
            reads++;
            long k = BuiltLogs.key(x, y, z);
            if (unloaded.contains(k)) return null;
            String id = cells.get(k);
            return id == null ? Mark.PLAIN : id.equals("?") ? Mark.NOT_KNOWN : mark(id, GEN);
        }

        @Override public boolean mine(int x, int y, int z) { return mine.contains(BuiltLogs.key(x, y, z)); }
    }

    /** An oak: five logs, leaves around the top, a bee nest on the trunk, dirt under it. */
    static World tree(int x, int z) {
        World w = new World().column(x, 64, 68, z, OAK).put(x, 63, z, DIRT).put(x + 1, 66, z, NEST);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) w.put(x + dx, 68, z + dz, LEAVES);
        return w;
    }

    @Test
    void aNaturalTreeStaysATree() {
        World w = tree(0, 0);
        BuiltLogs.Found f = BuiltLogs.of(w, 0, 64, 0, 96);
        assertEquals(Structure.NO, f.fact(), "leaves, ground and a bee nest are no blocks of a build");
        assertEquals(5, f.logs());
        assertTrue(f.whole());
        Set<Long> walked = new HashSet<>();
        BuiltLogs.of(w, 0, 66, 0, 96, walked);
        assertEquals(5, walked.size(), "the walk hands back the group's logs (the reader keeps one answer for them all)");
    }

    @Test
    void aVillageHousesLogsAreAStructure_everyCellOfTheGroup() {
        // a house corner: a pillar of three oak logs, plank walls beside the lower two, a stair on top of the wall
        World w = new World().column(0, 64, 66, 0, OAK).column(1, 64, 65, 0, PLANKS).put(1, 66, 0, STAIRS);
        BuiltLogs.Found f = BuiltLogs.of(w, 0, 64, 0, 96);
        assertEquals(Structure.YES, f.fact());
        assertEquals(PLANKS, w.cells.get(BuiltLogs.key(f.bx(), f.by(), f.bz())), "the built block that decided is named");
        // a beam of logs out from the pillar's top: its far cell touches no built block itself, its group does
        w.put(-1, 66, 0, OAK).put(-2, 66, 0, OAK).put(-3, 66, 0, OAK);
        assertEquals(Structure.YES, BuiltLogs.of(w, -3, 66, 0, 96).fact(), "joined logs are one thing: the beam's end is the house's log");
        // w058: a regular log joined to wood blocks (a log kind no feature names) is the same building
        World savanna = new World().put(0, 64, 0, ACACIA).column(0, 65, 70, 0, WOOD);
        assertEquals(Structure.YES, BuiltLogs.of(savanna, 0, 64, 0, 96).fact());
    }

    @Test
    void whatTheBotPlacedItselfIsNoStrangersBuild() {
        World w = tree(0, 0).put(1, 64, 0, TABLE);
        assertEquals(Structure.YES, BuiltLogs.of(w, 0, 64, 0, 96).fact(), "a stranger's table against the trunk: someone's place");
        w.mine.add(BuiltLogs.key(1, 64, 0));
        assertEquals(Structure.NO, BuiltLogs.of(w, 0, 64, 0, 96).fact(), "the bot's own table beside the tree it cuts changes nothing");
    }

    @Test
    void notKnownIsNeverANo() {
        World w = tree(0, 0);
        w.unloaded.add(BuiltLogs.key(1, 65, 1));
        assertEquals(Structure.NOT_LOADED, BuiltLogs.of(w, 0, 64, 0, 96).fact(), "a neighbour cell not loaded: the tree is not known yet");
        World k = tree(0, 0).put(-1, 64, 0, "?");
        assertEquals(Structure.NOT_LOADED, BuiltLogs.of(k, 0, 64, 0, 96).fact(), "a neighbour whose kind is not known yet");
        k.put(0, 64, 1, PLANKS);
        assertEquals(Structure.YES, BuiltLogs.of(k, 0, 64, 0, 96).fact(), "a built block found is an answer whatever else is unknown");
    }

    @Test
    void theWalkIsCapped() {
        // a trunk of 200 logs with planks against its top: from the foot the capped walk never reaches them
        World w = new World().column(0, 0, 199, 0, OAK).put(1, 199, 0, PLANKS);
        BuiltLogs.Found far = BuiltLogs.of(w, 0, 0, 0, 10);
        assertEquals(Structure.NO, far.fact());
        assertEquals(10, far.logs());
        assertFalse(far.whole(), "said: the group is bigger than the walk");
        assertTrue(w.reads <= 10 * 26, "at most the cap's logs and their neighbours are read: " + w.reads);
        Set<Long> walked = new HashSet<>();
        BuiltLogs.of(w, 0, 0, 0, 10, walked);
        assertEquals(10, walked.size(), "only the logs walked are handed back");
        assertEquals(Structure.YES, BuiltLogs.of(w, 0, 195, 0, 10).fact(), "near the planks the same trunk is a built thing");
        assertEquals(Structure.YES, BuiltLogs.of(w, 0, 0, 0, 500).fact(), "a walk long enough finds them from the foot too");
    }
}
