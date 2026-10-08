package io.github.mojolowjo.entropybot.commands;

import static org.junit.jupiter.api.Assertions.*;

import io.github.mojolowjo.entropybot.restore.RestoreRules;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.24.4: the staircase cells and chain, the shaft test, and the restore ledger's purpose blocks. */
class StairPlanTest {
    @Test
    void cellsAreThreeHighOneStepPerBlock() {
        List<int[]> c = StairPlan.cells(0, 64, 0, 0, -1, 3);
        assertEquals(9, c.size());
        assertArrayEquals(new int[]{0, 63, -1}, c.get(0));
        assertArrayEquals(new int[]{0, 65, -1}, c.get(2));
        assertArrayEquals(new int[]{0, 61, -3}, c.get(6));
        assertArrayEquals(new int[]{0, 63, -3}, c.get(8));
        assertArrayEquals(new int[]{0, -1}, StairPlan.dir("north"));
        assertArrayEquals(new int[]{1, 0}, StairPlan.dir("e"));
        assertNull(StairPlan.dir("up"));
    }

    @Test
    void chainDigsWalksAndLightsEverySixth() {
        List<String> s = StairPlan.steps(10, 70, 5, 1, 0, 12);
        assertEquals("dig 11 69 5 11 71 5", s.get(0));
        assertEquals("goto 11 69 5", s.get(1));
        assertEquals(12 * 2 + 2, s.size(), "two torches");
        assertTrue(s.contains("place torch 15 65 5"), s.toString());   // step 6: the torch in step 5's feet cell
        List<int[]> t = StairPlan.torches(10, 70, 5, 1, 0, 12);
        assertArrayEquals(new int[]{15, 65, 5}, t.get(0));
        assertArrayEquals(new int[]{21, 59, 5}, t.get(1));
    }

    /** A sheer 1x1 shaft at (0, z 0) from y 50 up to the ground at y 70 (the ground: solid at y <= 69). */
    static StairPlan.Cells shaftWorld() {
        return (x, y, z) -> {
            if (y >= 70) return true;
            return x == 0 && z == 0 && y >= 50;
        };
    }

    @Test
    void shaftFoundWithItsRimAndTheWayDown() {
        int[] rim = StairPlan.shaft(shaftWorld(), 0, 50, 0);
        assertNotNull(rim);
        assertEquals(70, rim[1]);
        assertEquals(20, rim[5]);
        assertEquals(1, Math.abs(rim[0]) + Math.abs(rim[2]));
        List<String> d = StairPlan.down(rim, 0, 50, 0);
        assertEquals("goto " + rim[0] + " 70 " + rim[2], d.get(0));
        assertTrue(d.get(d.size() - 1).equals("goto 0 50 0"), d.toString());
        assertTrue(d.get(d.size() - 2).startsWith("dig "), "the tunnel back along the bottom");
        assertTrue(d.size() <= 100, "fits a chain");
        // a drop of 3 is no shaft; a roofed hole is none either
        StairPlan.Cells shallow = (x, y, z) -> y >= 53 || (x == 0 && z == 0 && y >= 50);
        assertNull(StairPlan.shaft(shallow, 0, 50, 0));
        StairPlan.Cells roofed = (x, y, z) -> x == 0 && z == 0 && y >= 50 && y < 60;
        assertNull(StairPlan.shaft(roofed, 0, 50, 0));
    }

    @Test
    void restoreKeepsPurposeBlocks() {
        assertTrue(RestoreRules.purposeBreak("minecraft:stone", "minecraft:stone"));
        assertTrue(RestoreRules.purposeBreak("minecraft:stone", "minecraft:cobblestone"));
        assertTrue(RestoreRules.purposeBreak("minecraft:cobblestone", "minecraft:deepslate"));
        assertTrue(RestoreRules.purposeBreak("minecraft:dirt", "minecraft:grass_block"));
        assertFalse(RestoreRules.purposeBreak("minecraft:stone", "minecraft:dirt"), "a path block of another kind is still put back");
        assertFalse(RestoreRules.purposeBreak("minecraft:iron_ore", "minecraft:stone"), "mine X dig: path blocks restored");
        assertFalse(RestoreRules.purposeBreak("minecraft:stone", "minecraft:andesite"));
        assertFalse(RestoreRules.purposeBreak(null, "minecraft:stone"));
    }
}
