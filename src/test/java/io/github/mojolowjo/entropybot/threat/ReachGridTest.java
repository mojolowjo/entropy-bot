package io.github.mojolowjo.entropybot.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReachGridTest {
    static final int S = 17, H = 10;

    /** Flat stone floor at y = 0, air above. */
    static ReachGrid flat() {
        ReachGrid g = new ReachGrid(S, H, S, 0, 0, 0);
        for (int z = 0; z < S; z++) for (int x = 0; x < S; x++) g.codes[g.idx(x, 0, z)] = ReachGrid.SOLID;
        return g;
    }

    static void set(ReachGrid g, int x, int y, int z, byte c) { g.codes[g.idx(x, y, z)] = c; }

    static void box(ReachGrid g, int x1, int y1, int z1, int x2, int y2, int z2, byte c) {
        for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) set(g, x, y, z, c);
    }

    /** A closed stone house x/z 4..12, walls y 1..3, roof y 4; the bot inside at (8, 1, 8). */
    static ReachGrid house() {
        ReachGrid g = flat();
        box(g, 4, 1, 4, 12, 4, 12, ReachGrid.SOLID);
        box(g, 5, 1, 5, 11, 3, 11, ReachGrid.AIR);
        return g;
    }

    @Test
    void openGroundIsReachable() {
        ReachGrid g = flat();
        int[] d = g.search(8, 1, 8, false);
        assertEquals(0, g.distAt(d, 8, 1, 8));
        assertEquals(5, g.distAt(d, 3, 1, 8));
        assertEquals(10, g.distAt(d, 3, 1, 3));
    }

    @Test
    void zombieOnTheRoofOfAClosedHouseHasNoPath() {
        ReachGrid g = house();
        int[] d = g.search(8, 1, 8, false);
        assertEquals(-1, g.distAt(d, 8, 5, 8), "on the roof, 4 above, a solid roof between");
        assertEquals(-1, g.distAt(d, 1, 1, 8), "outside the closed walls");
        assertEquals(3, g.distAt(d, 5, 1, 8), "inside");
    }

    @Test
    void anOpenDoorletsTheOutsideIn_butTheRoofStaysCut() {
        ReachGrid g = house();
        set(g, 4, 1, 8, ReachGrid.AIR);         // a doorway (an open door snapshots as air)
        set(g, 4, 2, 8, ReachGrid.AIR);
        int[] d = g.search(8, 1, 8, false);
        assertEquals(6, g.distAt(d, 2, 1, 8));
        assertEquals(-1, g.distAt(d, 8, 5, 8), "a drop of 4 off the roof is too far");
    }

    @Test
    void aClosedDoorIsAWall() {
        ReachGrid g = house();
        set(g, 4, 1, 8, ReachGrid.SOLID);       // a closed door snapshots as solid: zombies don't open doors
        int[] d = g.search(8, 1, 8, false);
        assertEquals(-1, g.distAt(d, 2, 1, 8));
    }

    @Test
    void aFenceBlocksAndCantBeJumped() {
        ReachGrid g = flat();
        box(g, 0, 1, 10, S - 1, 1, 10, ReachGrid.TALL);
        int[] d = g.search(8, 1, 8, false);
        assertEquals(-1, g.distAt(d, 8, 1, 13), "behind a fence line");
        set(g, 3, 1, 10, ReachGrid.AIR);        // a gap (an open gate)
        d = g.search(8, 1, 8, false);
        assertTrue(g.distAt(d, 8, 1, 13) > 0);
    }

    @Test
    void stepsUpOneAndDropsThree_butNotUpTwo() {
        ReachGrid g = flat();
        box(g, 0, 1, 0, S - 1, 3, 4, ReachGrid.SOLID);       // a cliff 3 high north of z = 5
        int[] d = g.search(8, 1, 8, false);
        assertTrue(g.distAt(d, 8, 4, 2) > 0, "a mob on top drops 3 to the bot");
        ReachGrid up = flat();
        box(up, 0, 1, 0, S - 1, 3, 4, ReachGrid.SOLID);
        int[] d2 = up.search(8, 4, 2, false);                   // bot on top, mob below: it can't climb 3
        assertEquals(-1, up.distAt(d2, 8, 1, 8));
        ReachGrid stair = flat();
        box(stair, 0, 1, 0, S - 1, 1, 4, ReachGrid.SOLID);     // one step
        int[] d3 = stair.search(8, 2, 2, false);
        assertTrue(stair.distAt(d3, 8, 1, 8) > 0, "steps up 1");
    }

    @Test
    void aPitThreeDeep() {
        ReachGrid g = flat();
        box(g, 0, 1, 0, S - 1, 3, S - 1, ReachGrid.SOLID);   // ground at y = 3 top, walk at y = 4
        box(g, 7, 1, 7, 9, 3, 9, ReachGrid.AIR);              // a pit 3 deep, bot at its bottom
        int[] d = g.search(8, 1, 8, false);
        assertTrue(g.distAt(d, 12, 4, 8) > 0, "a mob up top drops in");
        int[] d2 = g.search(12, 4, 8, false);                 // bot up top: the mob in the pit can't climb out
        assertEquals(-1, g.distAt(d2, 8, 1, 8));
    }

    @Test
    void spidersClimbWalls() {
        ReachGrid g = flat();
        box(g, 0, 1, 0, S - 1, 5, 4, ReachGrid.SOLID);       // a cliff 5 high
        int[] walk = g.search(8, 1, 8, false);
        int[] spider = g.search(8, 1, 8, true);
        assertEquals(-1, g.distAt(walk, 8, 6, 2), "a zombie can't drop 5");
        assertTrue(g.distAt(spider, 8, 6, 2) > 0, "a spider climbs down");
    }

    @Test
    void aSpiderOnTheRoofOfAClosedHouseStillHasNoPath() {
        ReachGrid g = house();
        int[] spider = g.search(8, 1, 8, true);
        assertEquals(-1, g.distAt(spider, 8, 5, 8));
    }

    @Test
    void waterIsSwum_lavaNever() {
        ReachGrid g = flat();
        box(g, 0, 0, 10, S - 1, 1, 11, ReachGrid.WATER);     // a channel 2 deep
        int[] d = g.search(8, 1, 8, false);
        assertTrue(g.distAt(d, 8, 1, 14) > 0, "swims across");
        ReachGrid l = flat();
        box(l, 0, 1, 10, S - 1, 2, 11, ReachGrid.LAVA);
        int[] dl = l.search(8, 1, 8, false);
        assertEquals(-1, l.distAt(dl, 8, 1, 14));
    }

    @Test
    void outsideTheGridIsUnknown() {
        ReachGrid g = flat();
        int[] d = g.search(8, 1, 8, false);
        assertEquals(-2, g.distAt(d, 40, 1, 8));
    }

    @Test
    void nearestLitSpot() {
        ReachGrid g = flat();
        g.light[g.idx(12, 1, 8)] = 12;
        g.light[g.idx(2, 1, 8)] = 14;
        int[] d = g.search(8, 1, 8, false);
        assertArrayEquals(new int[]{12, 1, 8, 4}, g.nearestLit(d, 8, 24));
        assertNull(g.nearestLit(d, 8, 3));
    }

    @Test
    void searchBudget_fullGridUnderFiveMs() {
        ReachGrid g = new ReachGrid(33, 17, 33, 0, 0, 0);
        for (int z = 0; z < 33; z++) for (int x = 0; x < 33; x++) g.codes[g.idx(x, 0, z)] = ReachGrid.SOLID;
        for (int i = 0; i < 20; i++) { g.search(16, 1, 16, false); g.search(16, 1, 16, true); }   // warm up
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) { g.search(16, 1, 16, false); g.search(16, 1, 16, true); }
        double ms = (System.nanoTime() - t0) / 1e6 / 20;
        System.out.println("ReachGrid 33x17x33 walk+spider search: " + ms + " ms");
        assertTrue(ms < 5, "took " + ms + " ms");
    }
}
