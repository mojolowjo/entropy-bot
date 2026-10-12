package io.github.mojolowjo.entropybot.move;

import io.github.mojolowjo.entropybot.camp.ShelterPlan;
import io.github.mojolowjo.entropybot.chop.ChopLoop;
import io.github.mojolowjo.entropybot.restore.EscapePlan;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SurfaceSealTest {
    // ---- what may be broken ----
    @Test void naturalLeavesMayBreak() { assertEquals(SurfaceSeal.Kind.LEAVES, SurfaceSeal.kind("minecraft:oak_leaves", true, false, false, false, false, false)); }
    @Test void placedLeavesNever() { assertEquals(SurfaceSeal.Kind.NO, SurfaceSeal.kind("minecraft:oak_leaves", true, true, false, false, false, false)); }
    @Test void grassIsPlant() { assertEquals(SurfaceSeal.Kind.PLANT, SurfaceSeal.kind("minecraft:short_grass", false, false, false, false, false, false)); }
    @Test void snowLayerIsPlant() { assertEquals(SurfaceSeal.Kind.PLANT, SurfaceSeal.kind("minecraft:snow", false, false, false, false, false, false)); }
    @Test void vinesArePlant() { assertEquals(SurfaceSeal.Kind.PLANT, SurfaceSeal.kind("minecraft:vine", false, false, false, false, false, false)); }
    @Test void ownPlanksMayBreak() { assertEquals(SurfaceSeal.Kind.OWN, SurfaceSeal.kind("minecraft:oak_planks", false, false, false, true, false, false)); }
    @Test void strangersPlanksNever() { assertEquals(SurfaceSeal.Kind.NO, SurfaceSeal.kind("minecraft:oak_planks", false, false, false, false, false, false)); }
    @Test void stoneNever() { assertEquals(SurfaceSeal.Kind.NO, SurfaceSeal.kind("minecraft:stone", false, false, false, false, false, false)); }
    @Test void containerNever() { assertEquals(SurfaceSeal.Kind.NO, SurfaceSeal.kind("minecraft:chest", false, false, false, true, true, false)); }
    @Test void byWaterNever() { assertEquals(SurfaceSeal.Kind.NO, SurfaceSeal.kind("minecraft:oak_leaves", true, false, false, false, false, true)); }
    @Test void ownByLavaNever() { assertEquals(SurfaceSeal.Kind.NO, SurfaceSeal.kind("minecraft:oak_planks", false, false, false, true, false, true)); }

    // ---- the sealed test over a fake grid ----
    /** A flat world: ground at y 63, air above, plus the given solid cells. */
    static SurfaceSeal.Grid grid(Set<String> solid) {
        return new SurfaceSeal.Grid() {
            public boolean open(int x, int y, int z) { return y >= 64 && !solid.contains(x + " " + y + " " + z); }
            public boolean floor(int x, int y, int z) { return y <= 63 || solid.contains(x + " " + y + " " + z); }
        };
    }

    static Set<String> leafCube(int[] me) {
        Set<String> s = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0) s.add((me[0] + dx) + " " + (me[1] + dy) + " " + (me[2] + dz));
        return s;
    }

    @Test void openFieldNotSealed() { assertFalse(SurfaceSeal.sealed(grid(Set.of()), new int[]{0, 64, 0})); }

    @Test void leafCubeSealed() {
        int[] me = {0, 64, 0};
        assertTrue(SurfaceSeal.sealed(grid(leafCube(me)), me));
    }

    @Test void oneGapNotSealed() {
        int[] me = {0, 64, 0};
        Set<String> s = leafCube(me);
        s.remove("1 64 0");
        s.remove("1 65 0");
        assertFalse(SurfaceSeal.sealed(grid(s), me));
    }

    @Test void leafCubeEscapePlanBreaksTwo() {
        int[] me = {0, 64, 0};
        Set<String> s = leafCube(me);
        EscapePlan.Escape e = EscapePlan.plan((x, y, z) -> {
            String k = x + " " + y + " " + z;
            if (s.contains(k)) return EscapePlan.Kind.BREAKABLE;
            if (y <= 63) return EscapePlan.Kind.FLOOR_ONLY;
            return EscapePlan.Kind.OPEN;
        }, me, new int[]{10, 64, 0}, false);
        assertNotNull(e);
        assertTrue(e.breaks().size() <= 2);
    }

    // ---- the shelter recognised from stored cells ----
    static Set<String> keys(List<int[]> cells) {
        Set<String> s = new HashSet<>();
        for (int[] c : cells) s.add(ShelterPlan.key(c));
        return s;
    }

    @Test void fullOwnShelterRecognised() {
        int[] f = {5, 70, 5};
        Set<String> own = keys(ShelterPlan.allCells(f));
        assertTrue(SurfaceSeal.inOwnShelter(f, own, c -> true));
        assertEquals(ShelterPlan.FULL, SurfaceSeal.ownCount(f, own));
    }

    @Test void shelterInLeavesRecognised() {
        // run1: some sides were leaves before it built; one side is its own planks
        int[] f = {5, 70, 5};
        Set<String> own = new HashSet<>();
        own.add(ShelterPlan.key(new int[]{5, 70, 4}));
        own.add(ShelterPlan.key(new int[]{5, 71, 4}));
        assertTrue(SurfaceSeal.inOwnShelter(f, own, c -> true));
    }

    @Test void notInShelterWhenOpen() {
        int[] f = {5, 70, 5};
        Set<String> own = keys(ShelterPlan.allCells(f));
        assertFalse(SurfaceSeal.inOwnShelter(f, own, c -> !(c[0] == 6 && c[1] == 70)));
    }

    @Test void notInShelterWhenNoOwnSide() {
        int[] f = {5, 70, 5};
        Set<String> own = Set.of(ShelterPlan.key(new int[]{5, 72, 5}));       // only the roof
        assertFalse(SurfaceSeal.inOwnShelter(f, own, c -> true));
    }

    @Test void standingElsewhereNotInShelter() {
        Set<String> own = keys(ShelterPlan.allCells(new int[]{5, 70, 5}));
        assertFalse(SurfaceSeal.inOwnShelter(new int[]{20, 70, 5}, own, c -> true));
    }

    @Test void emptyStoreNotInShelter() { assertFalse(SurfaceSeal.inOwnShelter(new int[]{0, 0, 0}, Set.of(), c -> true)); }

    // ---- status, check ----
    @Test void statusLineFormat() { assertEquals("dig-out (surface): 5 blocks (leaves 3, own blocks 2)", SurfaceSeal.statusLine(3, 0, 2)); }

    @Test void tooOftenOverThree() {
        long now = 10_000_000L;
        assertFalse(SurfaceSeal.tooOften(new long[]{now - 1, now - 2, now - 3, 0, 0, 0, 0, 0}, now));
        assertTrue(SurfaceSeal.tooOften(new long[]{now - 1, now - 2, now - 3, now - 4, 0, 0, 0, 0}, now));
        assertFalse(SurfaceSeal.tooOften(new long[]{now - 1, now - 2, now - 3, now - 4_000_000L, 0, 0, 0, 0}, now));
    }

    // ---- stumps last ----
    @Test void oneLogIsStumpWhateverLeaves() {
        assertTrue(ChopLoop.isStump(1, false));
        assertFalse(ChopLoop.isStump(2, false));
        assertTrue(ChopLoop.isStump(3, true));
    }

    @Test void stumpsSkippedNearRealTree() {
        int[] me = {0, 64, 0};
        ChopLoop.Cand stump = new ChopLoop.Cand(new int[]{1, 64, 0}, 1, true, true);
        ChopLoop.Cand tree = new ChopLoop.Cand(new int[]{30, 64, 0}, 900, false, false);
        List<ChopLoop.Cand> o = ChopLoop.order(List.of(stump, tree), me);
        assertEquals(1, o.size());
        assertSame(tree, o.get(0));
    }

    @Test void stumpsAfterFarRealTree() {
        int[] me = {0, 64, 0};
        ChopLoop.Cand stump = new ChopLoop.Cand(new int[]{1, 64, 0}, 1, true, true);
        ChopLoop.Cand tree = new ChopLoop.Cand(new int[]{60, 64, 0}, 3600, true, false);
        List<ChopLoop.Cand> o = ChopLoop.order(List.of(stump, tree), me);
        assertEquals(2, o.size());
        assertSame(tree, o.get(0));
    }

    @Test void onlyStumpsStillTried() {
        int[] me = {0, 64, 0};
        ChopLoop.Cand a = new ChopLoop.Cand(new int[]{5, 64, 0}, 25, false, true), b = new ChopLoop.Cand(new int[]{2, 64, 0}, 4, false, true);
        List<ChopLoop.Cand> o = ChopLoop.order(List.of(a, b), me);
        assertSame(b, o.get(0));
    }
}
