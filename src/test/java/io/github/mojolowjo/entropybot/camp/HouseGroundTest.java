package io.github.mojolowjo.entropybot.camp;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HouseGroundTest {
    @Test
    void floorIsTheLowestSurface() {
        assertEquals(126, HouseGround.floorY(new int[]{130, 128, 126, 127, HouseGround.NONE}));
        assertEquals(HouseGround.NONE, HouseGround.floorY(new int[]{HouseGround.NONE}));
        assertEquals(HouseGround.NONE, HouseGround.floorY(new int[0]));
    }

    /** Ground at y 63 everywhere, a dip at 1 1 (ground 61) and a hole at 2 2 deeper than 4. */
    @Test
    void pillarsGoDownToTheGroundAtMostFour() {
        HouseGround.Free w = (x, y, z) -> {
            if (x == 2 && z == 2) return y > 50;
            int ground = x == 1 && z == 1 ? 61 : 63;
            return y > ground;
        };
        HouseGround.Pillars p = HouseGround.pillars(0, 0, 1, 1, 64, w);
        assertTrue(p.tooDeep().isEmpty());
        assertEquals(2, p.cells().size());
        assertArrayEquals(new int[]{1, 62, 1}, p.cells().get(0), "lowest first");
        assertArrayEquals(new int[]{1, 63, 1}, p.cells().get(1));
        HouseGround.Pillars d = HouseGround.pillars(0, 0, 2, 2, 64, w);
        assertEquals(1, d.tooDeep().size());
        assertEquals("2 2 (more than 4 deep)", d.tooDeep().get(0));
        assertTrue(d.stopText("house").startsWith("stopped: under the floor of house: 1 column is too deep"), d.stopText("house"));
        assertTrue(d.stopText("house").contains(" - next: "));
    }

    @Test
    void surfaceSkipsSnowLayersLeavesAndGrassAndStopsAtWater() {
        assertEquals(HouseGround.Surface.SKIP, HouseGround.surface(true, false, false, false, true), "air");
        assertEquals(HouseGround.Surface.SKIP, HouseGround.surface(false, true, false, false, true), "snow layer");
        assertEquals(HouseGround.Surface.SKIP, HouseGround.surface(false, false, true, false, false), "leaves");
        assertEquals(HouseGround.Surface.SKIP, HouseGround.surface(false, false, false, false, true), "grass");
        assertEquals(HouseGround.Surface.WATER, HouseGround.surface(false, false, false, true, true), "water");
        assertEquals(HouseGround.Surface.GROUND, HouseGround.surface(false, false, false, false, false), "stone");
        assertTrue(HouseGround.waterText("hut", 0, 0, 6, 6).contains("give heights (area 0 0 6 6 hut house y1 y2)"));
        assertTrue(HouseGround.TOO_LARGE.contains("too large"));
    }

    @Test
    void exactlyFourIsFine() {
        HouseGround.Pillars p = HouseGround.pillars(0, 0, 0, 0, 64, (x, y, z) -> y > 59);
        assertEquals(4, p.cells().size());
        assertTrue(p.tooDeep().isEmpty());
        assertTrue(HouseGround.pillars(0, 0, 0, 0, 64, (x, y, z) -> y > 58).tooDeep().size() == 1);
        assertTrue(HouseGround.pillars(0, 0, 0, 0, 64, (x, y, z) -> Set.of(70).contains(y)).cells().isEmpty());
    }
}
