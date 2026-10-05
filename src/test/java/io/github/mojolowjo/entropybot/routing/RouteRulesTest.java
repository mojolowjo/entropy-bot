package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.mojolowjo.entropybot.routing.RouteRules.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteRulesTest {

    @Test
    void walkabilityFilter() {
        assertTrue(walkChanged(PASSABLE, SOLID, false), "placed");
        assertTrue(walkChanged(SOLID, PASSABLE, false), "broken");
        assertTrue(walkChanged(PASSABLE, LIQUID_FLOW, false), "water flows in");
        assertTrue(walkChanged(LIQUID_FLOW, LIQUID_SOURCE, false), "flowing becomes a source");
        assertTrue(walkChanged(PASSABLE, AVOID, false), "fire");
        assertTrue(walkChanged(SOLID, PARTIAL, false), "a slab");
        assertTrue(walkChanged(PARTIAL, PARTIAL, false), "a door opens, a slab flips");
        assertFalse(walkChanged(PARTIAL, PARTIAL, true));
        assertFalse(walkChanged(PASSABLE, PASSABLE, false), "a crop grows");
        assertFalse(walkChanged(SOLID, SOLID, false), "a furnace lights");
        assertFalse(walkChanged(LIQUID_FLOW, LIQUID_FLOW, false), "a water level");
        assertFalse(walkChanged(AVOID, AVOID, false), "fire ages");
    }

    @Test
    void standable() {
        assertTrue(RouteRules.standable(true, false, false, true, true), "on the ground");
        assertTrue(RouteRules.standable(false, true, false, true, true), "in water");
        assertTrue(RouteRules.standable(false, false, true, true, true), "on a ladder");
        assertFalse(RouteRules.standable(false, false, false, true, true), "mid-air");
        assertFalse(RouteRules.standable(true, false, false, false, true), "inside a block");
        assertFalse(RouteRules.standable(true, false, false, true, false), "no headroom");
    }

    @Test
    void usableMoves() {
        double inf = 1_000_000;
        assertTrue(usableMove(4.6, inf, 0, 0, 0, 1, 0, 0));
        assertFalse(usableMove(inf, inf, 0, 0, 0, 1, 0, 0));
        assertFalse(usableMove(0, inf, 0, 0, 0, 1, 0, 0));
        assertFalse(usableMove(Double.NaN, inf, 0, 0, 0, 1, 0, 0));
        assertFalse(usableMove(3, inf, 1, 2, 3, 1, 2, 3), "goes nowhere");
    }

    @Test
    void quality() {
        boolean[] all = new boolean[9];
        java.util.Arrays.fill(all, true);
        assertEquals(SectionRecord.Quality.LIVE, RouteRules.quality(all, false));
        boolean[] centre = new boolean[9];
        centre[4] = true;
        assertEquals(SectionRecord.Quality.COARSE, RouteRules.quality(centre, false), "edge of the loaded ground");
        assertEquals(SectionRecord.Quality.COARSE, RouteRules.quality(new boolean[9], true), "Baritone's cache");
        assertNull(RouteRules.quality(new boolean[9], false), "nothing knows it");
    }

    @Test
    void areaBoxes() {
        AreaBoxes a = new AreaBoxes(List.of(new int[]{0, -272, -64, 223, 431, -2048, 2047}), -64, 319);
        assertTrue(a.wanted(new SectionKey(0, -17, -4, -4)));
        assertFalse(a.wanted(new SectionKey(0, -18, 0, 0)), "west of x -272");
        assertFalse(a.wanted(new SectionKey(0, 0, -5, 0)), "below the world");
        assertFalse(a.wanted(new SectionKey(0, 0, 20, 0)), "above the world");
        assertFalse(a.wanted(new SectionKey(1, 0, 0, 0)), "another dim");
        assertEquals(31 * 31 * 24, a.all(0).size(), "the owner's map area: 31 x 31 columns x 24 sections");
        assertEquals(24, a.column(0, 0, 0).size());
        assertTrue(a.column(0, 100, 0).isEmpty());
        List<SectionKey> near = a.near(0, 8, 70, 8, 2, 1);
        assertEquals(new SectionKey(0, 0, 4, 0), near.get(0));
        assertEquals(5 * 5 * 3, near.size());
        AreaBoxes thin = new AreaBoxes(List.of(new int[]{0, 10, 10, 0, 0, 40, 33}), -64, 319);  // swapped corners
        assertTrue(thin.wanted(new SectionKey(0, 0, 2, 0)));
        assertFalse(thin.wanted(new SectionKey(0, 0, 3, 0)));
    }
}
