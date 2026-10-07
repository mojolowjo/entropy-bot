package io.github.mojolowjo.entropybot.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.6 run guard on fake grids: drops, lava, water, fire, turns and stops. */
class RunGuardTest {
    /** 10 x 8 high x 5 z, ground at y 5 everywhere (feet at y 6), bedrock at y 0. */
    static ReachGrid flat() {
        ReachGrid g = new ReachGrid(10, 8, 5, 0, 0, 0);
        for (int x = 0; x < 10; x++) for (int z = 0; z < 5; z++) {
            g.codes[g.idx(x, 0, z)] = ReachGrid.SOLID;
            g.codes[g.idx(x, 5, z)] = ReachGrid.SOLID;
        }
        return g;
    }

    /** A pit from x 4 on (ground only at y 0: a drop of 5). */
    static ReachGrid pit() {
        ReachGrid g = flat();
        for (int x = 4; x < 10; x++) for (int z = 0; z < 5; z++) g.codes[g.idx(x, 5, z)] = ReachGrid.AIR;
        return g;
    }

    @Test
    void clearGround() {
        assertTrue(RunGuard.check(flat(), 1.5, 6, 2.5, 1, 0, null).ok());
        assertTrue(RunGuard.decide(flat(), 1.5, 6, 2.5, 1, 0, null).go());
    }

    @Test
    void aDropOverThreeTwoCellsAheadStops() {
        assertTrue(RunGuard.check(pit(), 1.5, 6, 2.5, 1, 0, null).ok(), "the pit is 3 cells away");
        RunGuard.Check c = RunGuard.check(pit(), 2.5, 6, 2.5, 1, 0, null);
        assertFalse(c.ok());
        assertEquals("drop 5", c.why());
        assertEquals(4, c.x());
    }

    @Test
    void aSmallDropIsFine() {
        ReachGrid g = flat();
        for (int x = 4; x < 10; x++) for (int z = 0; z < 5; z++) {
            g.codes[g.idx(x, 5, z)] = ReachGrid.AIR;
            g.codes[g.idx(x, 4, z)] = ReachGrid.AIR;
            g.codes[g.idx(x, 2, z)] = ReachGrid.SOLID;    // 3 down
        }
        assertTrue(RunGuard.check(g, 2.5, 6, 2.5, 1, 0, null).ok());
    }

    @Test
    void lavaWaterAndFire() {
        ReachGrid g = flat();
        g.codes[g.idx(3, 5, 2)] = ReachGrid.AIR;
        g.codes[g.idx(3, 4, 2)] = ReachGrid.LAVA;
        assertEquals("lava", RunGuard.check(g, 1.5, 6, 2.5, 1, 0, null).why());
        g = flat();
        g.codes[g.idx(2, 6, 2)] = ReachGrid.WATER;
        assertEquals("water", RunGuard.check(g, 1.5, 6, 2.5, 1, 0, null).why());
        RunGuard.Fire fire = (x, y, z) -> x == 3 && y == 6 && z == 2;
        assertEquals("fire", RunGuard.check(flat(), 1.5, 6, 2.5, 1, 0, fire).why());
    }

    @Test
    void turnsAlongTheEdgeOrStops() {
        // the pit edge runs along z; running +x at 2.5 turns toward a clear diagonal (the cells ahead stay at x 3)
        RunGuard.Verdict v = RunGuard.decide(pit(), 2.5, 6, 2.5, 1, 0, null);
        assertEquals("turn", v.act(), v.why());
        assertTrue(v.dirX() < 0.9 && Math.abs(v.dirZ()) > 0.5, v.why());
        assertTrue(RunGuard.check(pit(), 2.5, 6, 2.5, v.dirX(), v.dirZ(), null).ok());
        // a 1x1 pillar: nowhere to go
        ReachGrid g = new ReachGrid(9, 8, 9, 0, 0, 0);
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) g.codes[g.idx(x, 0, z)] = ReachGrid.SOLID;
        for (int y = 1; y <= 5; y++) g.codes[g.idx(4, y, 4)] = ReachGrid.SOLID;
        RunGuard.Verdict s = RunGuard.decide(g, 4.5, 6, 4.5, 1, 0, null);
        assertTrue(s.stop(), s.why());
    }

    @Test
    void noGridMeansGo() {
        assertTrue(RunGuard.decide(null, 0, 0, 0, 1, 0, null).go());
    }
}
