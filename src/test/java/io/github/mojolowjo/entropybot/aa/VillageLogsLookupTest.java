package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.guard.BreakPolicy;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Structure;
import io.github.mojolowjo.entropybot.guard.BuiltLogs;
import io.github.mojolowjo.entropybot.guard.Policy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner's ruling 2026-11-23 (village logs are protected): the planner's tree lookups read the guard's own answer for a
 * log cell (Scan#sourceBreak: BreakPolicy.structure over the cell's structure fact, here BuiltLogs over a fixture world
 * as PolicyRuntime.structureFacts runs it over the game's), so no tree is planned, priced or sized on a log the hand
 * would be refused on (rule 23), and a natural tree is still found.
 */
class VillageLogsLookupTest {
    static final String OAK = "minecraft:oak_log", WOOD = "minecraft:acacia_wood", PLANKS = "minecraft:oak_planks", LEAVES = "minecraft:oak_leaves";
    static final String OW = "minecraft:overworld";
    static final int[] ME = {0, 64, 0};

    @BeforeEach
    void data() { TestData.planner().install(); }

    @AfterEach
    void clear() { TestData.clear(); }

    /** A scan over a map of cells whose structure rule is the guard's: the table's fact by block, BuiltLogs for a tree's log. */
    static class World implements Nearby.Scan {
        final Map<String, String> cells = new LinkedHashMap<>();
        boolean genLoaded = true;

        static String k(int x, int y, int z) { return x + " " + y + " " + z; }
        World put(int x, int y, int z, String block) { cells.put(k(x, y, z), block); return this; }

        /** An oak of {@code n} logs with leaves beside its top. */
        World tree(int x, int z, int n) {
            for (int i = 0; i < n; i++) put(x, 64 + i, z, OAK);
            return put(x + 1, 64 + n - 1, z, LEAVES).put(x - 1, 64 + n - 1, z, LEAVES);
        }

        private List<int[]> all(java.util.function.Predicate<String> test) {
            List<int[]> out = new ArrayList<>();
            for (var e : cells.entrySet()) {
                if (!test.test(e.getValue())) continue;
                String[] p = e.getKey().split(" ");
                out.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
            }
            return out;
        }

        /** The marks as StructureTable gives them for these blocks (BuiltLogsTest checks the rule itself). */
        BuiltLogs.Mark mark(String block) {
            if (block == null || block.equals(LEAVES)) return BuiltLogs.Mark.PLAIN;
            if (!genLoaded) return BuiltLogs.Mark.NOT_KNOWN;
            return block.equals(OAK) ? BuiltLogs.Mark.TREE_LOG : BuiltLogs.Mark.BUILT;
        }

        @Override public List<int[]> logs(int radius) { return all(this::isLog); }
        @Override public List<int[]> find(Set<String> blocks, int radius) { return all(blocks::contains); }
        @Override public boolean isLog(String block) { return block.endsWith("_log") || block.endsWith("_wood"); }
        @Override public boolean fairOk(int x, int y, int z) { return true; }
        @Override public boolean leavesNear(int x, int y, int z, int reach) { return true; }   // a village has trees beside it: leaves are no test of a build
        @Override public boolean allowed(int x, int y, int z) { return true; }
        @Override public String blockAt(int x, int y, int z) { return cells.get(k(x, y, z)); }
        @Override public Boolean open(int x, int y, int z) { return true; }

        @Override public BreakPolicy.Verdict sourceBreak(int x, int y, int z) {
            String b = blockAt(x, y, z);
            Structure s = !genLoaded && isLog(b) ? Structure.NOT_LOADED : WOOD.equals(b) ? Structure.YES : Structure.NO;
            if (s == Structure.NO && mark(b) == BuiltLogs.Mark.TREE_LOG) s = BuiltLogs.of(new BuiltLogs.Cells() {
                @Override public BuiltLogs.Mark at(int cx, int cy, int cz) { return mark(blockAt(cx, cy, cz)); }
                @Override public boolean mine(int cx, int cy, int cz) { return false; }
            }, x, y, z, 96).fact();
            return BreakPolicy.structure(Policy.EMPTY, OW, x, y, z, s, false);
        }
    }

    /** A house corner 3 m away: a pillar of three logs with plank walls; a natural oak of four logs 20 m away. */
    static World village() {
        World w = new World();
        for (int y = 64; y <= 66; y++) w.put(3, y, 0, OAK).put(4, y, 0, PLANKS).put(3, y, 1, PLANKS);
        return w.tree(20, 0, 4);
    }

    @Test
    void theHousesLogsAreNoSource_theNaturalTreeBehindThemIs() {
        World w = village();
        Nearby.Source s = Nearby.source(w, ME, Set.of(OAK), () -> null);
        assertEquals(20, s.spot().x(), "the pillar 3 m away is a village house's: the oak 20 m away is the tree");
        assertEquals(4, s.spot().count());
        assertEquals(Map.of(OAK + " refused (" + BreakPolicy.STRUCTURE_OUTSIDE + ")", 3), s.refused(), "the three pillar cells, with the guard's own reason");
        Spot tree = Nearby.log(w, ME, () -> null);
        assertEquals(20, tree.x(), "the 'a tree nearby' fact passes the pillar over too");
        assertEquals(4, tree.count());
    }

    @Test
    void aVillageWithNoTreeGivesNoSource_notAHouse() {
        World w = new World();
        for (int y = 64; y <= 66; y++) w.put(3, y, 0, OAK).put(4, y, 0, PLANKS);
        assertNull(Nearby.source(w, ME, Set.of(OAK), () -> null).spot(), "no tree is priced that the hand will be refused on");
        assertNull(Nearby.log(w, ME, () -> null));
        // a sighted log of the house (the fallback) is refused the same way
        assertNull(Nearby.log(w, ME, () -> new Spot(3, 64, 0, 3, OAK)));
        assertNull(Nearby.source(w, ME, Set.of(OAK), () -> new Spot(3, 64, 0, 3, OAK)).spot());
    }

    @Test
    void w058_theWoodBlocksAndTheLogJoinedToThemAreNoTree() {
        World w = new World().put(5, 64, 0, OAK);
        for (int i = 1; i <= 51; i++) w.put(5, 64 + i, 0, WOOD);
        w.tree(30, 0, 5);
        Nearby.Source s = Nearby.source(w, ME, Set.of(OAK, WOOD), () -> null);
        assertEquals(30, s.spot().x(), "the 51 wood cells are a log kind no tree feature places, the log under them their building's");
        assertEquals(5, s.spot().count());
        assertEquals(30, Nearby.log(w, ME, () -> null).x());
    }

    @Test
    void aTreesSizeLeavesOutACellTheHandIsRefusedOn() {
        // a tree whose top log the guard refuses (here: by a scan that says so for that one cell)
        World w = new World() {
            @Override public BreakPolicy.Verdict sourceBreak(int x, int y, int z) {
                return BreakPolicy.structure(Policy.EMPTY, OW, x, y, z, y >= 67 ? Structure.YES : Structure.NO, false);
            }
        };
        w.tree(5, 0, 5);
        List<int[]> logs = w.logs(0);
        assertEquals(3, Nearby.treeCut(w, logs, null, new int[]{5, 64, 0}), "the two refused cells are not counted into the tree's size");
        assertEquals(3, Nearby.source(w, ME, Set.of(OAK), () -> null).spot().count());
    }

    @Test
    void beforeTheWorldGenReadALogIsNotKnown_neverATreeAndNeverRefused() {
        World w = new World().tree(6, 0, 4);
        w.genLoaded = false;
        Nearby.Source s = Nearby.source(w, ME, Set.of(OAK), () -> null);
        assertEquals("not loaded", s.from(), "rule 15: the chain waits on the spot with the reason");
        assertTrue(s.refused().isEmpty(), "not known is not a refusal");
        assertNull(Nearby.log(w, ME, () -> null), "no tree is claimed on a fact not known");
        w.genLoaded = true;
        assertEquals(6, Nearby.source(w, ME, Set.of(OAK), () -> null).spot().x(), "the read done: the tree is the source");
        assertEquals(6, Nearby.log(w, ME, () -> null).x());
    }
}
