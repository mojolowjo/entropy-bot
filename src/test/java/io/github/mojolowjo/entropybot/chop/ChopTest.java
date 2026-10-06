package io.github.mojolowjo.entropybot.chop;

import io.github.mojolowjo.entropybot.surface.SurfaceColumns;
import io.github.mojolowjo.entropybot.surface.SurfaceFamily;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P3: the tree finder on a fake world, the candidate order, the sapling and replant rules, the grammar, the end text. */
class ChopTest {
    /** A sparse world: unset cells are air; y 63 and below is grass. */
    static final class Fake implements TreeFinder.World, SurfaceColumns.Source {
        final Map<String, String> blocks = new HashMap<>();

        void set(int x, int y, int z, String id) { blocks.put(TreeFinder.key(x, y, z), id); }

        @Override public String id(int x, int y, int z) {
            String b = blocks.get(TreeFinder.key(x, y, z));
            return b != null ? b : y <= 63 ? "minecraft:grass_block" : "minecraft:air";
        }

        @Override public boolean log(int x, int y, int z) { return id(x, y, z).endsWith("_log"); }
        @Override public boolean leaves(int x, int y, int z) { return id(x, y, z).contains("leaves"); }
        @Override public boolean persistentLeaves(int x, int y, int z) { return id(x, y, z).endsWith("leaves[p]"); }
        @Override public boolean soil(int x, int y, int z) { return id(x, y, z).endsWith("grass_block") || id(x, y, z).endsWith("dirt"); }
        @Override public boolean built(int x, int y, int z) { return id(x, y, z).endsWith("_planks") || id(x, y, z).endsWith("chest"); }

        @Override public int kind(int x, int y, int z) {
            String i = id(x, y, z);
            if (i.endsWith("air")) return SurfaceColumns.AIR;
            if (i.contains("leaves")) return SurfaceColumns.LEAVES;
            return SurfaceColumns.GROUND;
        }

        @Override public int family(int x, int y, int z) { return SurfaceFamily.of(id(x, y, z), null); }
        @Override public int top(int x, int z) { return 80; }

        /** An oak: a 5-log trunk on the grass at x z and a 5x5x3 crown around its top. */
        void oak(int x, int z, String leaves) {
            for (int y = 64; y <= 68; y++) set(x, y, z, "minecraft:oak_log");
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int y = 67; y <= 69; y++) if (!(dx == 0 && dz == 0 && y <= 68)) set(x + dx, y, z + dz, leaves);
        }
    }

    @Test
    void aTrunkWithLeavesIsATree() {
        Fake w = new Fake();
        w.oak(0, 0, "minecraft:oak_leaves");
        TreeFinder.Result r = TreeFinder.find(w, 0, 66, 0);
        assertTrue(r.ok(), r.why());
        assertEquals(5, r.tree().logs().size());
        assertEquals("minecraft:oak_log", r.tree().logId());
        assertArrayEquals(new int[]{0, 64, 0}, r.tree().base());
        assertEquals(1, TreeFinder.lowest(r.tree().logs()).size());
    }

    @Test
    void aLogPileIsNoTree() {
        Fake w = new Fake();
        for (int x = 0; x < 3; x++) for (int y = 64; y < 66; y++) w.set(x, y, 0, "minecraft:oak_log");
        TreeFinder.Result r = TreeFinder.find(w, 1, 65, 0);
        assertFalse(r.ok());
        assertTrue(r.why().contains("no leaves"), r.why());
    }

    @Test
    void logsOnPlanksAreNoTree() {
        Fake w = new Fake();
        w.set(0, 64, 0, "minecraft:oak_planks");
        for (int y = 65; y <= 68; y++) w.set(0, y, 0, "minecraft:oak_log");
        assertTrue(TreeFinder.find(w, 0, 66, 0).why().contains("don't stand on dirt"));
    }

    @Test
    void placedLeavesAreABuild() {
        Fake w = new Fake();
        w.oak(0, 0, "minecraft:oak_leaves[p]");
        TreeFinder.Result r = TreeFinder.find(w, 0, 64, 0);
        assertFalse(r.ok());
        assertTrue(r.why().contains("someone placed"), r.why());
    }

    @Test
    void aTreeNextToAHutIsSkipped() {
        Fake w = new Fake();
        w.oak(0, 0, "minecraft:oak_leaves");
        for (int y = 64; y <= 66; y++) w.set(4, y, 0, "minecraft:oak_planks");
        TreeFinder.Result r = TreeFinder.find(w, 0, 64, 0);
        assertFalse(r.ok());
        assertTrue(r.why().contains("built blocks at 4 64 0"), r.why());
        // 7 away is fine
        Fake w2 = new Fake();
        w2.oak(0, 0, "minecraft:oak_leaves");
        w2.set(9, 64, 0, "minecraft:oak_planks");
        assertTrue(TreeFinder.find(w2, 0, 64, 0).ok());
    }

    @Test
    void aRoofOverTheTreeIsABuild() {
        Fake w = new Fake();
        w.oak(0, 0, "minecraft:oak_leaves");
        w.set(0, 74, 0, "minecraft:spruce_planks");
        assertTrue(TreeFinder.find(w, 0, 64, 0).why().contains("built blocks at 0 74 0"));
    }

    @Test
    void twoByTwoTrunkHasFourBases() {
        Fake w = new Fake();
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) for (int y = 64; y < 72; y++) w.set(x, y, z, "minecraft:spruce_log");
        for (int dx = -2; dx <= 3; dx++) for (int dz = -2; dz <= 3; dz++) w.set(dx, 72, dz, "minecraft:spruce_leaves");
        TreeFinder.Result r = TreeFinder.find(w, 1, 70, 1);
        assertTrue(r.ok(), r.why());
        assertEquals(4, r.tree().bases().size());
        assertEquals(32, r.tree().logs().size());
    }

    @Test
    void leftOnlyCountsTheTreesOwnLogs() {
        Fake w = new Fake();
        w.oak(0, 0, "minecraft:oak_leaves");
        TreeFinder.Tree t = TreeFinder.find(w, 0, 64, 0).tree();
        w.set(0, 64, 0, "minecraft:air");
        w.set(0, 65, 0, "minecraft:air");
        w.set(5, 64, 5, "minecraft:oak_log");      // a neighbour is never chased
        assertEquals(3, TreeFinder.left(w, t).size());
    }

    @Test
    void candidatesFromColumnsNearestFirst() {
        Fake w = new Fake();
        w.oak(10, 0, "minecraft:oak_leaves");
        w.oak(3, 0, "minecraft:oak_leaves");
        assertEquals(68, ChopRules.logTop(w, 3, 0, 100, 40));
        assertEquals(ChopRules.NONE, ChopRules.logTop(w, 4, 0, 100, 40));
        // a superflat world: the logs are below y 0 (the old -1 for "none" hid them)
        SurfaceColumns.Source deep = new SurfaceColumns.Source() {
            @Override public int kind(int x, int y, int z) { return y > -56 ? SurfaceColumns.AIR : SurfaceColumns.GROUND; }
            @Override public int family(int x, int y, int z) { return y >= -60 ? SurfaceFamily.LOG : SurfaceFamily.DIRT; }
            @Override public int top(int x, int z) { return -50; }
        };
        assertEquals(-56, ChopRules.logTop(deep, 0, 0, -20, -84));      // leaves over grass
        List<int[]> c = new ArrayList<>(List.of(new int[]{10, 68, 0}, new int[]{3, 68, 0}, new int[]{4, 68, 0}, new int[]{0, 90, 1}));
        List<int[]> o = ChopRules.order(c, 0, 64, 0);
        assertArrayEquals(new int[]{3, 68, 0}, o.get(0));
        assertArrayEquals(new int[]{10, 68, 0}, o.get(1));      // 4 68 0 next to the first: dropped
        assertEquals(3, o.size());
        assertArrayEquals(new int[]{0, 90, 1}, o.get(2));       // high up counts double
    }

    @Test
    void saplingsAndReplant() {
        assertEquals("minecraft:oak_sapling", ChopRules.sapling("minecraft:oak_log"));
        assertEquals("minecraft:dark_oak_sapling", ChopRules.sapling("minecraft:dark_oak_log"));
        assertEquals("minecraft:mangrove_propagule", ChopRules.sapling("minecraft:mangrove_log"));
        assertNull(ChopRules.sapling("minecraft:crimson_stem"));
        List<int[]> one = List.of(new int[]{0, 64, 0});
        assertEquals(1, ChopRules.replant(one, "minecraft:oak_sapling", 3).cells().size());
        assertNull(ChopRules.replant(one, "minecraft:oak_sapling", 3).note());
        ChopRules.Replant none = ChopRules.replant(one, "minecraft:oak_sapling", 0);
        assertTrue(none.cells().isEmpty());
        assertEquals("no oak_sapling - not replanted", none.note());
        List<int[]> four = List.of(new int[]{0, 64, 0}, new int[]{1, 64, 0}, new int[]{0, 64, 1}, new int[]{1, 64, 1});
        assertEquals(4, ChopRules.replant(four, "minecraft:spruce_sapling", 5).cells().size());
        ChopRules.Replant few = ChopRules.replant(four, "minecraft:dark_oak_sapling", 2);
        assertEquals(1, few.cells().size());
        assertTrue(few.note().contains("needs 4"), few.note());
    }

    @Test
    void grammar() {
        ChopRules.Args a = ChopRules.parse("");
        assertEquals(ChopRules.DEFAULT_LOGS, a.n());
        a = ChopRules.parse("16 oak");
        assertEquals(16, a.n());
        assertFalse(a.trees());
        assertEquals("oak", a.type());
        a = ChopRules.parse("trees 3 birch");
        assertTrue(a.trees());
        assertEquals(3, a.n());
        assertTrue(ChopRules.parse("status").status());
        assertNotNull(ChopRules.parse("trees").error());
        assertNotNull(ChopRules.parse("0").error());
        assertNotNull(ChopRules.parse("5 oak extra").error());
        assertTrue(ChopRules.matches("oak", "minecraft:oak_log"));
        assertFalse(ChopRules.matches("oak", "minecraft:dark_oak_log"));
        assertTrue(ChopRules.matches("minecraft:birch_log", "minecraft:birch_log"));
        assertTrue(ChopRules.matches(null, "x:y_log"));
    }

    @Test
    void stopsAndEndText() {
        ChopRules.Args a = ChopRules.parse("16");
        assertEquals("", ChopRules.stopReason(a, 16, 2, 0, 100, 0));
        assertNull(ChopRules.stopReason(a, 15, 2, 0, 100, 0));
        assertEquals("the 20 minutes are up", ChopRules.stopReason(a, 1, 1, 100, 100, 0));
        assertNotNull(ChopRules.stopReason(a, 1, 1, 0, 100, ChopRules.FAILS));
        assertEquals("", ChopRules.stopReason(ChopRules.parse("trees 2"), 3, 2, 0, 100, 0));
        Map<String, Integer> got = new LinkedHashMap<>();
        got.put("minecraft:oak_log", 23);
        assertEquals("ok: chopped 4 trees, 23 oak_log, replanted 4", ChopRules.endText(4, got, 4, "", List.of(), null));
        assertEquals("ok: chopped 2 trees, 23 oak_log - no more trees in my areas (replanted 2)",
                ChopRules.endText(2, got, 2, "no more trees in my areas", List.of(), null));
        String none = ChopRules.endText(0, new LinkedHashMap<>(), 0, "no trees in my areas", List.of(), "area add");
        assertTrue(none.startsWith("error: chopped nothing - no trees in my areas"), none);
        assertTrue(none.endsWith("- next: area add"), none);
        Map<String, Integer> before = Map.of("minecraft:oak_log", 5), after = Map.of("minecraft:oak_log", 9, "minecraft:stick", 3);
        assertEquals(Map.of("minecraft:oak_log", 4), ChopRules.gained(before, after, id -> id.endsWith("_log")));
    }
}
