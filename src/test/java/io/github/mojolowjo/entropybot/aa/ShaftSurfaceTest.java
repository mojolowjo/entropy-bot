package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-hold-sight point 2, replaying worlds run 4 w027 (log lines 1099-1124): the orders read the surface at 0 69 -10; with
 * the bot at -2 52 -4 (17 under ground) the deposit walked "up to the surface at -1 53 -7", one block - a sky-lit cell of
 * the bot's own dig, whose neighbour column of the dig was a step away, so the lowest-neighbour hole read 0 and the cell
 * passed as the surface; then "no path to the chest at 2 67 -7 from -2 54 -6". The one hole reader
 * (SurfaceHeight.pitDepth, Jobs.holeDepth and the simulator's) and the one under-ground test (NeedChain.Surface.under)
 * must call such a cell what it is: 16 under the ground at 69.
 */
class ShaftSurfaceTest {
    @AfterEach
    void clear() { Tuning.reset(); }

    /** The ground at 69 round, with the given columns dug lower (x,z -> the surface y over them). */
    private static IntBinaryOperator land(Map<String, Integer> dug) {
        return (x, z) -> dug.getOrDefault(x + "," + z, 69);
    }

    private static int[] around(IntBinaryOperator s, int x, int z) {
        int[] n = new int[8];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) n[i++] = s.applyAsInt(x + dx, z + dz);
        return n;
    }

    /**
     * Send-back (the reviewer: 8 worlds of the 100-world table 2-25 s slower): a staircase cut down along z that a player
     * walks up (one column a step, from -1 53 -7 to the ground) is no hole - the walk by steps reaches the ring's border,
     * as the base read it, so no walk up leg before the deposit or the explore. (The ring's rim stays the leg's target for
     * a boxed-in way out, as in the base: legNeeded sees only the ring and the stair still deep at its border.)
     */
    @Test
    void aStaircaseAPlayerWalksUp_isNoHole() {
        Map<String, Integer> dug = new HashMap<>();
        for (int i = 0; i <= 15; i++) dug.put("-1," + (-7 + i), 53 + i);       // -1 53 -7, -1 54 -6, ... up to the ground
        IntBinaryOperator s = land(dug);
        int[] feet = {-1, 53, -7};
        assertEquals(0, SurfaceHeight.pitDepth(feet, s), "walks out by steps: no hole");
        assertEquals(0, SurfaceHeight.holeDepth(53, around(s, -1, -7)), "as the base read it (the stair a step up)");
        assertFalse(new NeedChain.Surface(SurfaceHeight.belowSurface(0, 0), feet[0], feet[1], feet[2], 0, false).under(), "not under ground");
    }
    /** w027's cell beside a notch dug at the shaft's side: walled in, 16 under the ground at 69 - the deposit's and the orders' one test says under ground. */
    @Test
    void w027_aShaftWithANotchBesideIt_isNoSurface() {
        Map<String, Integer> dug = new HashMap<>(Map.of("-1,-7", 53, "-2,-6", 54));
        IntBinaryOperator s = land(dug);
        assertEquals(0, SurfaceHeight.holeDepth(53, around(s, -1, -7)), "the old reading: 0");
        assertEquals(16, SurfaceHeight.pitDepth(new int[]{-1, 53, -7}, s));
        assertEquals(16, SurfaceHeight.pitDepth(new int[]{-1, 53, -7}, land(Map.of("-1,-7", 53))), "a plain 1x1 shaft reads as before");
        NeedChain.Surface at = new NeedChain.Surface(SurfaceHeight.belowSurface(0, 16), 0, 69, -10, 16, true);
        assertTrue(at.under(), "the deposit's and the orders' one test: under ground, the walk up or the climb first");
    }

    /** Ground a player walks out of stays the surface: flat land, a cliff's foot, a street between houses, a slope. */
    @Test
    void openGround_staysTheSurface() {
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, 69, 0}, land(Map.of())), "flat land");
        IntBinaryOperator cliff = (x, z) -> x >= 1 ? 80 : 69;
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, 69, 0}, cliff), "a cliff's foot: open ground on the other side");
        IntBinaryOperator corner = (x, z) -> x >= 1 || z >= 1 ? 80 : 69;
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, 69, 0}, corner), "the inner corner of a cliff");
        IntBinaryOperator street = (x, z) -> Math.abs(x) >= 2 ? 75 : 69;
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, 69, 0}, street), "a 3-wide street between houses");
        IntBinaryOperator slope = (x, z) -> 69 + x;
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, 69, 0}, slope), "a slope of 1-block steps");
        IntBinaryOperator unknown = (x, z) -> x == 0 && z == 0 ? 53 : SurfaceHeight.NOT_KNOWN;
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, 53, 0}, unknown), "nothing known round it: no depth claimed (rule 15)");
    }

    /**
     * Send-back, the 100-world table's w080 (the one world still slower after the walled-across rule went: a walk up to
     * 0 132 6 before the chest at -7 127 -1): feet at 0 126 6 in its own dig, a 1x1 dig down to 124 beside them, the
     * ground round at 130-132. A drop beside the feet reads no hole, as the base's lowest neighbour did.
     */
    @Test
    void w080_aDropBesideTheFeet_isNoHole() {
        IntBinaryOperator s = (x, z) -> x == 1 && z == 7 ? 124 : x == 0 && z == 6 ? 126 : 131;
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, 126, 6}, s));
        assertEquals(0, SurfaceHeight.holeDepth(126, around(s, 0, 6)), "as the base read it");
    }

    /** The hole reader and the leg reader agree in the w027 shapes (rule 23 (2)): a hole is where the no-break walk cannot get out. */
    @Test
    void theHoleAndTheLeg_agree() {
        java.util.List<Map<String, Integer>> shapes = java.util.List.of(
                Map.of("-1,-7", 53),                                   // a 1x1 shaft
                Map.of("-1,-7", 53, "-2,-6", 54),                      // with a notch beside it (w027)
                Map.of("-1,-7", 53, "-1,-6", 53),                      // a 1x2 shaft
                Map.of("-1,-7", 66, "-1,-6", 66));                     // a shallow 2-cell dig, 3 deep
        for (Map<String, Integer> dug : shapes) {
            IntBinaryOperator s = land(dug);
            int[] feet = {-1, dug.get("-1,-7"), -7};
            int d = SurfaceHeight.pitDepth(feet, s);
            assertTrue(d > 0, "walled in: " + dug);
            assertTrue(SurfaceHeight.legNeeded(feet, s, SurfaceHeight.ringTarget(feet, s, (x, y, z) -> true)), "the leg too: " + dug);
        }
    }
}