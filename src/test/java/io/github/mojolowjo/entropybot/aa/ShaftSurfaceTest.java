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

    /** w027's cell -1 53 -7 at the foot of a staircase cut down along z (one column a step, the stair at -1 54 -6). */
    @Test
    void w027_theFootOfAStaircaseCutDown_isNoSurface() {
        Map<String, Integer> dug = new HashMap<>();
        for (int i = 0; i <= 15; i++) dug.put("-1," + (-7 + i), 53 + i);       // -1 53 -7, -1 54 -6, ... up to the ground
        IntBinaryOperator s = land(dug);
        assertEquals(0, SurfaceHeight.holeDepth(53, around(s, -1, -7)), "the lowest-neighbour reading said 0 (the stair a step up): the run's fault");
        int d = SurfaceHeight.pitDepth(new int[]{-1, 53, -7}, s);
        assertEquals(16, d, "walled across: 16 under the ground at 69");
        NeedChain.Surface at = new NeedChain.Surface(SurfaceHeight.belowSurface(0, d), 0, 69, -10, 16, true);
        assertTrue(at.under(), "the deposit's and the orders' one test: under ground, the walk up or the climb first");
    }

    /** The same cell beside a notch dug at the shaft's side (the other way the dig may run): no surface either. */
    @Test
    void w027_aShaftWithANotchBesideIt_isNoSurface() {
        Map<String, Integer> dug = new HashMap<>(Map.of("-1,-7", 53, "-2,-6", 54));
        IntBinaryOperator s = land(dug);
        assertEquals(0, SurfaceHeight.holeDepth(53, around(s, -1, -7)), "the old reading: 0");
        assertEquals(16, SurfaceHeight.pitDepth(new int[]{-1, 53, -7}, s));
        assertEquals(16, SurfaceHeight.pitDepth(new int[]{-1, 53, -7}, land(Map.of("-1,-7", 53))), "a plain 1x1 shaft reads as before");
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

    /** The 1-wide trench rule itself, pair by pair. */
    @Test
    void trenchDepth_pairs() {
        assertEquals(16, SurfaceHeight.trenchDepth(53, 69, 69, 54, 52));
        assertEquals(0, SurfaceHeight.trenchDepth(53, 69, 53, 69, 54), "open on one side of each pair");
        assertEquals(0, SurfaceHeight.trenchDepth(53, 54, 69, SurfaceHeight.NOT_KNOWN, 69), "a step, and a pair not known");
    }
}
