package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.commands.AskSweep;
import io.github.mojolowjo.entropybot.sense.Sightings;
import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z76 surface (runs AK, AL, AO: every walk-up target "surface at x -64 z"; SightingsRuntime's roof and cave-side
 * checks read the same -64): one answer for the surface height from the heightmaps the client has - MOTION_BLOCKING,
 * walked down past leaves - and "not known" (rule 15) where the client's chunk holds no data, never the world's bottom.
 * Plus the review fix: the running band walk is told and protected by its own "in:x,y,z" key.
 */
class Z76SurfaceTest {
    @AfterEach
    void clear() { FactChange.clear(); }

    static final int MIN = -64;

    /** A column: leaves at 69 and 68 under a MOTION_BLOCKING top of 70, a dirt block at 67: the leaf-free surface is 68. */
    @Test
    void theSurfaceIsReadFromTheHeightmapsTheClientHas() {
        Set<Integer> skip = Set.of(69, 68);
        assertEquals(68, SurfaceHeight.noLeaves(true, 70, MIN, skip::contains));
        assertEquals(64, SurfaceHeight.noLeaves(true, 64, MIN, y -> false), "no leaves: the heightmap's own top");
    }

    /**
     * The client lacks this heightmap (run AO 23:54: "surface at -375 -64 -264"): a heightmap at the bottom of the world
     * holds nothing - not known, never y -64; a column not loaded is not known too.
     */
    @Test
    void aHeightmapTheClientLacksIsNotKnown() {
        assertEquals(SurfaceHeight.NOT_KNOWN, SurfaceHeight.noLeaves(true, MIN, MIN, y -> false), "the bottom of the world: no data");
        assertEquals(SurfaceHeight.NOT_KNOWN, SurfaceHeight.noLeaves(false, 70, MIN, y -> false), "not loaded");
        assertFalse(SurfaceHeight.known(SurfaceHeight.NOT_KNOWN));
        assertTrue(SurfaceHeight.known(MIN + 1));
    }

    /**
     * The cave rule on the real surface: an air pocket at y 60 under a surface of 80 is "dark" (with -64, as the client's
     * missing heightmap read, it never was); a side column not known (NOT_KNOWN in the max) never counts as ground 6+ higher.
     */
    @Test
    void theCaveRuleSeesTheSurface() {
        assertEquals("dark", Sightings.caveRule(true, 0, 0, 60, 80, null, false));
        assertNull(Sightings.caveRule(true, 0, 0, 60, MIN, null, false), "the old -64 reading: never a dark cave");
        int nk = SurfaceHeight.NOT_KNOWN;
        assertNull(Sightings.caveRule(true, 15, 0, 70, 70, new int[]{nk, nk, nk, nk}, false), "sides not known: no ravine");
        assertEquals("ravine", Sightings.caveRule(true, 15, 0, 70, 70, new int[]{80, 80, nk, nk}, false));
    }

    /** Review: the running band walk is "in:x,y,z" (feet IN the band's cell), told and spared by that key, not "x,y,z". */
    @Test
    void theRunningBandWalkKeepsItsOwnKey() {
        int[] feet = {5, 60, 7};
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) { return Car.Status.running("walking down"); }
            @Override public void cancel(String why) {}
            @Override public int[] feet() { return feet; }
        });
        run.start("goal.torch", List.of(new Step.ToBand(56, 56)), 0, null);
        Steps.Ahead a = run.ahead();
        assertNotNull(a);
        assertTrue(a.into(), "the band walk's feet end IN its cell");
        assertArrayEquals(new int[]{5, 56, 7}, a.cell());
        String key = AskSweep.key(5, 56, 7, a.into());
        assertEquals("in:5,56,7", key);
        List<AskSweep.Ask> pending = List.of(new AskSweep.Ask(key, new int[]{5, 90, 7}));
        assertEquals(List.of(), AskSweep.due(pending, Set.of(), Set.of(key), new int[]{5, 60, 7}, 16, key), "the running band walk is never withdrawn");
        assertEquals(List.of(key), AskSweep.due(pending, Set.of(), Set.of(key), new int[]{5, 60, 7}, 16, "5,56,7"), "a stand-by key would not have spared it");
        Steps walk = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) { return Car.Status.running("walking"); }
            @Override public void cancel(String why) {}
        });
        walk.start("goal.torch", List.of(new Step.Walk(1, 64, 2, 0)), 0, null);
        assertFalse(walk.ahead().into(), "a plain walk stands by its cell");
    }
}
