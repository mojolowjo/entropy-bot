package io.github.mojolowjo.entropybot.chop;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.27.1: stumps and lone logs, the candidate order fallback, the stand cells, the bot's own blocks, the skip note. */
class Chop0271Test {

    /** The fake world plus the cells the bot built itself. */
    static final class Own implements TreeFinder.World {
        final ChopTest.Fake w = new ChopTest.Fake();
        final Set<String> own = new HashSet<>();

        @Override public String id(int x, int y, int z) { return w.id(x, y, z); }
        @Override public boolean log(int x, int y, int z) { return w.log(x, y, z); }
        @Override public boolean leaves(int x, int y, int z) { return w.leaves(x, y, z); }
        @Override public boolean persistentLeaves(int x, int y, int z) { return w.persistentLeaves(x, y, z); }
        @Override public boolean soil(int x, int y, int z) { return w.soil(x, y, z); }
        @Override public boolean built(int x, int y, int z) { return w.built(x, y, z); }
        @Override public boolean ownBuilt(int x, int y, int z) { return own.contains(TreeFinder.key(x, y, z)); }
    }

    /** One log on the grass with natural leaves beside it (a felled tree's leftovers) but none above. */
    static void stumpWithLeavesBeside(ChopTest.Fake w, int x, int z) {
        w.set(x, 64, z, "minecraft:oak_log");
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            if (dx != 0 || dz != 0) w.set(x + dx, 64, z + dz, "minecraft:oak_leaves");
    }

    @Test
    void aLoneLogWithLeavesBesideIsAStump() {
        ChopTest.Fake w = new ChopTest.Fake();
        stumpWithLeavesBeside(w, 0, 0);
        TreeFinder.Result r = TreeFinder.find(w, 0, 64, 0);
        assertTrue(r.ok(), r.why());      // the finder still takes it (3+ leaves near) ...
        assertTrue(TreeFinder.stump(w, r.tree()));      // ... the stump rule catches it
    }

    @Test
    void aOneLogTreeWithLeavesAboveIsNoStump() {
        ChopTest.Fake w = new ChopTest.Fake();
        w.set(0, 64, 0, "minecraft:oak_log");
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) w.set(dx, 65, dz, "minecraft:oak_leaves");
        TreeFinder.Result r = TreeFinder.find(w, 0, 64, 0);
        assertTrue(r.ok(), r.why());
        assertFalse(TreeFinder.stump(w, r.tree()));
    }

    @Test
    void placedLeavesAboveDoNotSaveAStump() {
        ChopTest.Fake w = new ChopTest.Fake();
        stumpWithLeavesBeside(w, 0, 0);
        w.set(0, 65, 0, "minecraft:oak_leaves[p]");
        TreeFinder.Result r = TreeFinder.find(w, 0, 64, 0);
        assertTrue(r.ok(), r.why());
        assertTrue(TreeFinder.stump(w, r.tree()));
    }

    @Test
    void aRealTreeIsNoStump() {
        ChopTest.Fake w = new ChopTest.Fake();
        w.oak(0, 0, "minecraft:oak_leaves");
        TreeFinder.Result r = TreeFinder.find(w, 0, 66, 0);
        assertFalse(TreeFinder.stump(w, r.tree()));
    }

    @Test
    void aChoppedLogLeftoverIsAStump() {
        ChopTest.Fake w = new ChopTest.Fake();
        w.set(0, 64, 0, "treechop:chopped_log");
        TreeFinder.Tree t = new TreeFinder.Tree(List.of(new int[]{0, 64, 0}), List.of(new int[]{0, 64, 0}), "treechop:chopped_log", new int[]{0, 64, 0, 0, 64, 0});
        for (int dx = -1; dx <= 1; dx++) w.set(dx, 66, 0, "minecraft:oak_leaves");
        assertTrue(TreeFinder.stump(w, t));
    }

    @Test
    void stumpsGoLastEvenWhenNearer() {
        List<ChopLoop.Cand> o = ChopLoop.order(List.of(
                new ChopLoop.Cand(new int[]{1, 64, 0}, 1, false, true),
                new ChopLoop.Cand(new int[]{9, 64, 0}, 81, true, false)));
        assertEquals(9, o.get(0).base()[0]);
        assertFalse(ChopLoop.onlyStumps(List.of(o.get(0), o.get(1))));
    }

    @Test
    void onlyStumpsLeftAreStillTriedNearestFirst() {
        List<ChopLoop.Cand> in = List.of(new ChopLoop.Cand(new int[]{5, 64, 0}, 25, false, true), new ChopLoop.Cand(new int[]{2, 64, 0}, 4, false, true));
        assertTrue(ChopLoop.onlyStumps(in));
        assertEquals(2, ChopLoop.order(in).get(0).base()[0]);
        assertFalse(ChopLoop.onlyStumps(List.of()));
    }

    @Test
    void reachableFirstWhenAnyRealTreeIsReachable() {
        List<ChopLoop.Cand> o = ChopLoop.order(List.of(
                new ChopLoop.Cand(new int[]{1, 64, 0}, 1, false),
                new ChopLoop.Cand(new int[]{7, 64, 0}, 49, true)));
        assertEquals(7, o.get(0).base()[0]);
    }

    @Test
    void noReachableTreeFallsBackToNearest() {
        List<ChopLoop.Cand> o = ChopLoop.order(List.of(
                new ChopLoop.Cand(new int[]{7, 64, 0}, 49, false),
                new ChopLoop.Cand(new int[]{1, 64, 0}, 1, false),
                new ChopLoop.Cand(new int[]{3, 64, 0}, 9, false)));
        assertEquals(List.of(1, 3, 7), o.stream().map(c -> c.base()[0]).toList());
    }

    @Test
    void aReachableStumpDoesNotTurnOnTheReachOrder() {
        // the stump's reach flag never counts: real trees stay in plain nearest order
        List<ChopLoop.Cand> o = ChopLoop.order(List.of(
                new ChopLoop.Cand(new int[]{9, 64, 0}, 81, false, false),
                new ChopLoop.Cand(new int[]{4, 64, 0}, 16, false, false),
                new ChopLoop.Cand(new int[]{1, 64, 0}, 1, true, true)));
        assertEquals(4, o.get(0).base()[0]);
        assertEquals(1, o.get(2).base()[0]);
    }

    @Test
    void standOffsetsAreTheEightAroundOrTwentyFourWide() {
        List<int[]> n = ChopLoop.standOffsets(false);
        assertEquals(8, n.size());
        assertTrue(n.stream().anyMatch(o -> o[0] == 1 && o[1] == 1));      // diagonals too
        List<int[]> wide = ChopLoop.standOffsets(true);
        assertEquals(24, wide.size());
        assertEquals(1, Math.max(Math.abs(wide.get(0)[0]), Math.abs(wide.get(0)[1])));      // the near ring first
        assertTrue(wide.stream().noneMatch(o -> o[0] == 0 && o[1] == 0));
    }

    @Test
    void ownShelterNextToATreeIsNoBuild() {
        Own w = new Own();
        w.w.oak(0, 0, "minecraft:oak_leaves");
        for (int y = 64; y <= 65; y++) {
            w.w.set(3, y, 3, "minecraft:spruce_planks");
            w.own.add(TreeFinder.key(3, y, 3));
        }
        TreeFinder.Result r = TreeFinder.find(w, 0, 66, 0);
        assertTrue(r.ok(), r.why());
    }

    @Test
    void aForeignBuildNextToATreeStillCounts() {
        Own w = new Own();
        w.w.oak(0, 0, "minecraft:oak_leaves");
        w.w.set(3, 64, 3, "minecraft:spruce_planks");
        w.w.set(4, 64, 3, "minecraft:spruce_planks");
        w.own.add(TreeFinder.key(3, 64, 3));      // one of them is its own, the other not
        TreeFinder.Result r = TreeFinder.find(w, 0, 66, 0);
        assertFalse(r.ok());
        assertTrue(r.why().contains("built blocks at 4 64 3"), r.why());
    }

    @Test
    void foreignBuiltDecision() {
        assertTrue(TreeFinder.foreignBuilt(true, false));
        assertFalse(TreeFinder.foreignBuilt(true, true));
        assertFalse(TreeFinder.foreignBuilt(false, false));
        assertFalse(TreeFinder.foreignBuilt(false, true));
    }

    @Test
    void skipNoteSaysCouldNotGetAtOnlyForAReachFailure() {
        assertEquals("tree at 1 64 2: skipped - nothing came down", ChopLoop.skipNote("1 64 2", false, null));
        assertTrue(ChopLoop.skipNote("1 64 2", false, "1 64 2").contains("couldn't get at its trunk (1 64 2)"));
        assertTrue(ChopLoop.skipNote("1 64 2", true, null).contains("couldn't get at its trunk"));
    }
}
