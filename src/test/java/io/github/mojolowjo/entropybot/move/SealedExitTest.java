package io.github.mojolowjo.entropybot.move;

import static org.junit.jupiter.api.Assertions.*;

import io.github.mojolowjo.entropybot.camp.ShelterPlan;
import io.github.mojolowjo.entropybot.commands.StairPlanUpAccess;
import io.github.mojolowjo.entropybot.restore.EscapePlan;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 0.27.3 (d1-d3): the shell's open corners are no way out, a pit shelter with one own wall, the staircase up, the refusal text. */
class SealedExitTest {
    static final int[] F = {-9, 70, 30};

    /** A 3x3 shell of 4 sides at feet and head with the corners open, flat ground at y 69. */
    static boolean shellOpen(int x, int y, int z) {
        if (y < 70) return false;
        int dx = x - F[0], dz = z - F[2];
        boolean side = Math.abs(dx) + Math.abs(dz) == 1 && y <= 71;
        boolean roof = dx == 0 && dz == 0 && y == 72 || dx == 0 && dz == -1 && y == 72;
        return !side && !roof;
    }

    @Test void cornersOfAShellAreNoWayOut() {
        SurfaceSeal.Grid g = new SurfaceSeal.Grid() {
            public boolean open(int x, int y, int z) { return shellOpen(x, y, z); }
            public boolean floor(int x, int y, int z) { return y == 69; }
        };
        assertTrue(SurfaceSeal.sealed(g, F), "the corners -10 70 31 etc. are open but the walls block the body");
        assertFalse(SurfaceSeal.diagonalPass(SealedExitTest::shellOpen, F, -1, 0, 1));
        // one side wall gone: the diagonal passes through it
        SurfaceSeal.Open oneGone = (x, y, z) -> shellOpen(x, y, z) || (x == -10 && z == 30 && y <= 71 && y >= 70);
        assertTrue(SurfaceSeal.diagonalPass(oneGone, F, -1, 0, 1));
    }

    /** 0.27.3 fix (live d1): a closed own shell on the surface: the escape is my own wall, not "underground: no way out". */
    @Test void closedOwnShellOnTheSurfaceOpensTheOwnWall() {
        int[] f = {-9, 70, 42};
        Set<String> own = new HashSet<>();
        for (int[] c : ShelterPlan.allCells(f)) own.add(ShelterPlan.key(c));
        List<int[]> ex = SurfaceSeal.ownExit(f, own, c -> true);
        assertNotNull(ex);
        assertEquals("-9 70 41", ShelterPlan.key(ex.get(0)), "the north wall's feet cell is the step");
        assertEquals(List.of("-9 71 41", "-9 70 41"), ex.subList(1, ex.size()).stream().map(ShelterPlan::key).toList(), "head first");
        assertNull(SurfaceSeal.ownExit(f, Set.of(), c -> true), "not mine: no own exit");
        assertNull(SurfaceSeal.ownExit(f, own, c -> c[1] != 72), "an open roof: not sealed in");
    }

    @Test void pitShelterWithOneOwnWallIsRecognisedAndHasAMixedExit() {
        int[] f = {21, 45, 83};
        Set<String> own = new HashSet<>(List.of("21 47 83", "20 45 83"));
        assertFalse(SurfaceSeal.inOwnShelter(f, own, c -> true), "the old rule wanted feet and head both own");
        assertTrue(SurfaceSeal.inOwnShelterLoose(f, own, c -> true));
        assertNull(ShelterPlan.exit(f, own));
        int[][] ex = ShelterPlan.exit(f, own, c -> c[1] == 46);       // the head cells are natural stone the rules allow
        assertNotNull(ex);
        assertEquals("20 45 83", ShelterPlan.key(ex[0]));
        assertEquals("20 46 83", ShelterPlan.key(ex[1]));
        assertNull(ShelterPlan.exit(f, own, c -> false));
        assertFalse(SurfaceSeal.inOwnShelterLoose(f, Set.of("99 99 99"), c -> true));
        assertFalse(SurfaceSeal.inOwnShelterLoose(f, own, c -> c[1] != 46), "an open cell: no shelter");
    }

    /**
     * 0.27.4 (live: a 1x1 pit, my roof and one wall, the rest natural stone; the planks were no longer in the stored list):
     * the escape opens my wall and the natural cell above it: head first, then the step cell.
     */
    @Test void pitWithOwnRoofAndWallGivesAMixedExit() {
        int[] f = {21, 45, 83};
        Set<String> own = Set.of("21 47 83", "20 45 83");
        java.util.function.Predicate<int[]> natural = c -> c[1] == 46;
        List<int[]> ex = SurfaceSeal.ownExit(f, own, c -> true, natural);
        assertNotNull(ex, "a closed pit with my roof and wall is my shelter");
        assertEquals(List.of("20 45 83", "20 46 83", "20 45 83"), ex.stream().map(ShelterPlan::key).toList(),
                "step cell first, then the natural head cell, then my wall");
        assertNull(SurfaceSeal.ownExit(f, own, c -> true), "without the natural cell the rules allow there is no exit");
        assertNull(SurfaceSeal.ownExit(f, own, c -> c[1] != 46, natural), "an open cell: not sealed in");
        assertNull(SurfaceSeal.ownExit(f, Set.of("99 99 99"), c -> true, natural), "no own cell: not mine");
    }

    @Test void stairSideAndUpSteps() {
        int[] f = {0, 40, 0};
        // natural stone everywhere except the bot's own cells; the roof over the head is an own plank (FLOOR_ONLY)
        EscapePlan.Grid g = (x, y, z) -> (x == 0 && z == 0 && (y == 40 || y == 41)) ? EscapePlan.Kind.OPEN
                : (x == 0 && z == 0 && y == 42) ? EscapePlan.Kind.FLOOR_ONLY : EscapePlan.Kind.BREAKABLE;
        assertArrayEquals(new int[]{0, 1}, EscapePlan.stairSide(g, f, new int[]{0, 60, 50}), "toward the goal (south)");
        assertNotNull(EscapePlan.stairSide(g, f, null));
        EscapePlan.Grid water = (x, y, z) -> (x == 0 && z == 0 && y <= 41) ? EscapePlan.Kind.OPEN : EscapePlan.Kind.BLOCKED;
        assertNull(EscapePlan.stairSide(water, f, null));
        List<String> s = StairPlanUpAccess.upSteps(0, 40, 0, 0, 1, 3);
        assertEquals(List.of("dig 0 41 0 0 42 1 via engine", "goto 0 41 1", "dig 0 42 1 0 43 2 via engine", "goto 0 42 2", "dig 0 43 2 0 44 3 via engine", "goto 0 43 3"), s);
        assertEquals(30, StairPlanUpAccess.upCount(40, 70));
        assertEquals(40, StairPlanUpAccess.upCount(-20, 70));
        assertEquals(0, StairPlanUpAccess.upCount(70, 70));
    }

    @Test void refusalNamesTheCell() {
        int[] f = {-9, 70, 42};
        // feet cells open (short grass), head cells persistent leaves (not breakable: FLOOR_ONLY), ground below
        EscapePlan.Grid g = (x, y, z) -> y <= 69 ? EscapePlan.Kind.FLOOR_ONLY : y == 70 ? EscapePlan.Kind.OPEN
                : (x == f[0] && z == f[2] && y == 71) ? EscapePlan.Kind.OPEN : EscapePlan.Kind.FLOOR_ONLY;
        assertNull(EscapePlan.plan(g, f, null));
        String why = EscapePlan.explain(g, c -> "oak_leaves (persistent)", f);
        assertTrue(why.contains("east: oak_leaves (persistent) at -8 71 42"), why);
        assertEquals(4, why.split("; ").length);
    }
}
