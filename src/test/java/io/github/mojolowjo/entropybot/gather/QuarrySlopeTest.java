package io.github.mojolowjo.entropybot.gather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Package 4 (trial 8): the staircase quarry on a slope rising ahead, and gather's face-dig fallback. */
class QuarrySlopeTest {
    /** A slope rising east: the ground top is at y 63 + x (feet at 0 64 0); stone from 4 below the top. */
    private static StonePit.Cell slope(int x, int y, int z) {
        int top = 63 + Math.max(0, x);
        if (y > top) return StonePit.Cell.AIR;
        return y > top - 4 ? StonePit.Cell.SOFT : StonePit.Cell.STONE;
    }

    @Test void theFaceAheadIsDugForRoomNeverUnderTheFeet() {
        StonePit.Plan p = StonePit.plan(QuarrySlopeTest::slope, new int[]{0, 64, 0}, new int[]{1, 0}, 64);
        assertNull(p.err());
        boolean face = false;
        for (int[] c : p.cells()) {
            assertTrue(c[0] >= 1, "nothing at or behind my feet: " + c[0] + " " + c[1] + " " + c[2]);
            assertTrue(c[1] <= 65, "nothing above head height");
            if (c[0] == 1 && c[1] == 64) face = true;
        }
        assertTrue(face, "the solid face one block ahead at feet level is in the list");
        // column 1 (x 2) gets room for the walk down: feet and head height
        assertTrue(p.cells().stream().anyMatch(c -> c[0] == 2 && c[1] == 65 && c[2] == 0));
        assertTrue(p.cells().stream().anyMatch(c -> c[0] == 2 && c[1] == 64 && c[2] == 0));
    }

    @Test void aChestOrWaterAtBodyHeightAheadIsLeftNotTheEnd() {
        StonePit.Plan p = StonePit.plan((x, y, z) -> x == 1 && y == 64 && z == 0 ? StonePit.Cell.HARD
                : x == 1 && y == 65 && z == 1 ? StonePit.Cell.LIQUID : slope(x, y, z), new int[]{0, 64, 0}, new int[]{1, 0}, 64);
        assertNull(p.err(), String.valueOf(p.err()));
        assertTrue(p.cells().stream().noneMatch(c -> c[0] == 1 && c[1] == 64 && c[2] == 0));
    }

    @Test void flatGroundIsUnchanged() {
        // flat: everything above y 63 is air, so the room cells add nothing (column 1 starts at x 2, y 63)
        StonePit.Plan p = StonePit.plan((x, y, z) -> y >= 64 ? StonePit.Cell.AIR : StonePit.Cell.STONE, new int[]{0, 64, 0}, new int[]{1, 0}, 3);
        assertEquals(3, p.cells().size());
        for (int[] c : p.cells()) assertArrayEquals(new int[]{2, 63}, new int[]{c[0], c[1]});
        assertEquals(1, p.columns());
    }

    @Test void faceBoxIsFiveWideThreeDeepThreeHighAhead() {
        int[] b = StonePit.faceBox(new int[]{10, 70, -5}, new int[]{0, -1});
        assertArrayEquals(new int[]{8, 69, -8, 12, 71, -6}, b);
        assertEquals("dig 11 69 -2 13 71 2", StonePit.faceDig(new int[]{10, 70, 0}, new int[]{1, 0}));
    }

    @Test void twoGiveUpsInARowThenTheFaceDig() {
        String gave = "stopped: quarrying stone ...: broke 12 of 40 - gave up (Baritone found no way to reach the rest)";
        int n = GatherRules.quarryGiveUps(0, "dig stone 64", gave);
        assertFalse(GatherRules.faceDigDue(n));
        n = GatherRules.quarryGiveUps(n, "craft stone_pickaxe 1", "ok: crafted");
        n = GatherRules.quarryGiveUps(n, "dig stone 64", gave);
        assertTrue(GatherRules.faceDigDue(n));
        assertEquals(0, GatherRules.quarryGiveUps(n, "dig stone 64", "done: dug 40; got 38 cobblestone"));
        assertFalse(GatherRules.quarryGaveUp("dig stone 64", "stopped: needs a pickaxe for stone"));
    }
}
