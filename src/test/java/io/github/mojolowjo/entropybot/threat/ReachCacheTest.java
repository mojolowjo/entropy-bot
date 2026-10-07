package io.github.mojolowjo.entropybot.threat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.2: the event-driven grid (patches, dirty/coalesce, slice copies, new-mob reads, escape headings). */
class ReachCacheTest {
    /** A world: stone floor at y 0, air above, plus overrides; counts its reads. */
    static final class World implements ReachCache.Source {
        final Map<Long, Byte> set = new HashMap<>();
        int reads;

        static long k(int x, int y, int z) { return ((long) x & 0xfffff) << 40 | ((long) y & 0xfffff) << 20 | ((long) z & 0xfffff); }

        void put(int x, int y, int z, byte c) { set.put(k(x, y, z), c); }

        @Override
        public byte code(int x, int y, int z) {
            reads++;
            Byte c = set.get(k(x, y, z));
            if (c != null) return c;
            return y <= 0 ? ReachGrid.SOLID : ReachGrid.AIR;
        }

        @Override
        public byte light(int x, int y, int z) { return 15; }
    }

    static final int S = 17, H = 8;

    /** A closed stone house around (8, 1, 8): walls x/z 4..12, y 1..3, roof y 4. */
    static World house() {
        World w = new World();
        for (int y = 1; y <= 4; y++) for (int z = 4; z <= 12; z++) for (int x = 4; x <= 12; x++) {
            boolean inside = y <= 3 && x > 4 && x < 12 && z > 4 && z < 12;
            if (!inside) w.put(x, y, z, ReachGrid.SOLID);
        }
        return w;
    }

    @Test
    void aPatchMarksDirtyAndTheNextEnsureSearchesOnce() {
        World w = house();
        ReachCache c = new ReachCache(S, H, S);
        c.full(w, 0, 0, 0);
        assertTrue(c.ensure(8, 1, 8, false));
        assertEquals(-1, c.dist(1, 1, 8, false), "outside the closed house: no path");
        assertFalse(c.ensure(8, 1, 8, false), "clean: no search");
        // dig a 1x2 hole in the west wall
        assertTrue(c.patch(4, 1, 8, ReachGrid.AIR, w));
        assertTrue(c.patch(4, 2, 8, ReachGrid.AIR, w));
        assertFalse(c.patch(4, 2, 8, ReachGrid.AIR, w), "unchanged: no patch");
        assertFalse(c.patch(40, 2, 8, ReachGrid.AIR, w), "outside the box");
        assertTrue(c.dirty());
        long before = c.searches;
        assertTrue(c.ensure(8, 1, 8, false));
        assertFalse(c.ensure(8, 1, 8, false));
        assertEquals(before + 1, c.searches, "two patches, one search");
        assertTrue(c.dist(1, 1, 8, false) > 0, "the hole is a path now");
        assertEquals(2, c.patches);
        assertEquals(1, c.fulls);
    }

    @Test
    void aBurstOfTenNewMobsIsOneSearch() {
        World w = house();
        ReachCache c = new ReachCache(S, H, S);
        c.full(w, 0, 0, 0);
        c.ensure(8, 1, 8, false);
        c.patch(4, 1, 8, ReachGrid.AIR, w);
        c.patch(4, 2, 8, ReachGrid.AIR, w);
        long before = c.searches;
        int ran = 0;
        for (int i = 0; i < 10; i++) {                       // ten mobs appear in one tick: each asks for its reach
            if (c.ensure(8, 1, 8, false)) ran++;
            assertTrue(c.dist(1 + (i % 3), 1, 8, false) >= 0);
        }
        assertEquals(1, ran);
        assertEquals(before + 1, c.searches);
        // a clean array: twenty more mobs are twenty reads, no search
        for (int i = 0; i < 20; i++) assertFalse(c.ensure(8, 1, 8, false));
        assertEquals(before + 1, c.searches);
    }

    @Test
    void aFullRefreshOfTheSameBlocksKeepsTheSearch() {
        World w = house();
        ReachCache c = new ReachCache(S, H, S);
        c.full(w, 0, 0, 0);
        c.ensure(8, 1, 8, false);
        c.full(w, 0, 0, 0);
        assertFalse(c.dirty(), "nothing changed: no new search");
        w.put(4, 1, 8, ReachGrid.AIR);                       // a change the hook missed
        c.full(w, 0, 0, 0);
        assertTrue(c.dirty());
    }

    @Test
    void aSpiderAnswerIsSearchedOnceOnDemand() {
        World w = house();
        ReachCache c = new ReachCache(S, H, S);
        c.full(w, 0, 0, 0);
        assertTrue(c.ensure(8, 1, 8, false));
        assertTrue(c.ensure(8, 1, 8, true), "first spider ask");
        assertFalse(c.ensure(8, 1, 8, true));
    }

    @Test
    void theBotMovingIsANewSearchButNoCopy() {
        World w = house();
        ReachCache c = new ReachCache(S, H, S);
        c.full(w, 0, 0, 0);
        c.ensure(8, 1, 8, false);
        int reads = w.reads;
        assertTrue(c.ensure(9, 1, 8, false));
        assertEquals(reads, w.reads, "no block read");
    }

    @Test
    void aBoxShiftCopiesOnlyTheNewSlice() {
        World w = house();
        ReachCache c = new ReachCache(S, H, S);
        c.full(w, 0, 0, 0);
        int reads = w.reads;
        c.patch(6, 1, 6, ReachGrid.SOLID, null);             // a patched cell survives the shift
        assertTrue(c.moveTo(w, 1, 0, 0));
        assertEquals(H * S, w.reads - reads, "one new x slice: sy*sz cells");
        assertEquals(H * S, c.sliceCells);
        ReachGrid g = c.grid();
        assertEquals(1, g.ox);
        assertEquals(ReachGrid.SOLID, g.codes[g.idx(6 - 1, 1, 6)]);
        assertEquals(ReachGrid.SOLID, g.codes[g.idx(4 - 1, 2, 8)], "the wall copied over");
        assertEquals(ReachGrid.SOLID, g.codes[g.idx(S - 1, 0, 3)], "the new slice's floor read");
        assertTrue(c.dirty());
        assertFalse(c.moveTo(w, 1, 0, 0), "same origin");
        // diagonal + vertical shift: (sx*sy*sz) - (sx-1)(sy-1)(sz-2) new cells
        reads = w.reads;
        c.moveTo(w, 2, 1, -2);
        assertEquals(S * H * S - (S - 1) * (H - 1) * (S - 2), w.reads - reads);
        // a jump past the box is a full copy
        long fulls = c.fulls;
        c.moveTo(w, 100, 0, 0);
        assertEquals(fulls + 1, c.fulls);
    }

    @Test
    void chunkOverlap() {
        ReachCache c = new ReachCache(33, 17, 33);
        c.full(new World(), -16, 0, -16);                    // x/z -16..16
        assertTrue(c.overlapsChunk(0, 0));
        assertTrue(c.overlapsChunk(-1, -1));
        assertTrue(c.overlapsChunk(1, 0), "x 16 is in chunk 1");
        assertFalse(c.overlapsChunk(2, 0));
        assertFalse(c.overlapsChunk(-2, 0));
    }

    // ---- escape headings ----

    @Test
    void escapeTakesTheLongestFreeRunAway() {
        // flat floor; a wall straight east (away) 2 blocks out; the creeper is west
        World w = new World();
        // the wall runs z 5..16: open to the north-east (z below 5)
        for (int z = 5; z < S; z++) for (int y = 1; y <= 3; y++) w.put(10, y, z, ReachGrid.SOLID);
        ReachCache c = new ReachCache(S, H, S);
        c.full(w, 0, 0, 0);
        double[] d = EscapeDir.choose(c.grid(), 8, 1, 8, 1, 0);
        assertTrue(d[0] > 0, "still away from the creeper (west)");
        assertTrue(Math.abs(d[2]) >= 2, "a run of at least 2: " + d[2]);
        assertTrue(d[1] < -0.3, "turned north round the wall's end, not into it: " + d[0] + "," + d[1]);
        assertTrue(d[2] >= 4, "a long run: " + d[2]);
    }

    @Test
    void escapeNeverIntoLavaWaterOrABigDrop() {
        World w = new World();
        // east: lava floor row in front; north-east (z-): water; south-east (z+): a 5-deep pit
        for (int z = 6; z <= 10; z++) w.put(10, 1, z, ReachGrid.LAVA);
        for (int x = 9; x < S; x++) for (int z = 0; z < 6; z++) w.put(x, 1, z, ReachGrid.WATER);
        ReachCache c = new ReachCache(S, H, S);
        c.full(w, 0, -5, 0);                                  // floor at world y 0 = grid y 5
        double[] straight = EscapeDir.choose(c.grid(), 8, 1, 8, 1, 0);
        // walk the chosen heading: never a lava or water cell
        ReachGrid g = c.grid();
        int run = EscapeDir.run(g, 8, 6, 8, straight[0], straight[1]);
        assertTrue(run <= EscapeDir.MAX_RUN);
        for (int s = 1; s <= run * 2; s++) {
            int x = (int) Math.floor(8.5 + straight[0] * s * 0.5), z = (int) Math.floor(8.5 + straight[1] * s * 0.5);
            assertNotEquals(ReachGrid.LAVA, g.code(x, 6, z));
            assertNotEquals(ReachGrid.WATER, g.code(x, 6, z));
        }
        // a drop of 5: the run stops at the edge
        World pit = new World();
        for (int x = 10; x < S; x++) for (int z = 0; z < S; z++) for (int y = -4; y <= 0; y++) pit.put(x, y, z, ReachGrid.AIR);
        ReachCache c2 = new ReachCache(S, H + 4, S);
        c2.full(pit, 0, -6, 0);
        assertEquals(1, EscapeDir.run(c2.grid(), 8, 7, 8, 1, 0), "one step, then the edge of a 5 drop");
        // a drop of 2 is fine
        World step = new World();
        for (int x = 10; x < S; x++) for (int z = 0; z < S; z++) for (int y = -1; y <= 0; y++) step.put(x, y, z, ReachGrid.AIR);
        ReachCache c3 = new ReachCache(S, H + 4, S);
        c3.full(step, 0, -6, 0);
        assertTrue(EscapeDir.run(c3.grid(), 8, 7, 8, 1, 0) > 3);
    }

    @Test
    void noGridIsStraightAway() {
        double[] d = EscapeDir.choose(null, 0, 0, 0, 3, 4);
        assertEquals(0.6, d[0], 1e-9);
        assertEquals(0.8, d[1], 1e-9);
        assertEquals(-1, d[2]);
    }

    // ---- creeper duel margin, sprint flag ----

    @Test
    void creeperDuelNeedsArmourAndHealth() {
        assertFalse(FightOrFlee.creeperDuelOk(new FightOrFlee.Me(20, 20, 0, 6, 0, -1, 6)), "no armour");
        assertTrue(FightOrFlee.creeperDuelOk(new FightOrFlee.Me(20, 20, 15, 6, 0, -1, 6)), "full iron, full health");
        assertFalse(FightOrFlee.creeperDuelOk(new FightOrFlee.Me(12, 20, 15, 6, 0, -1, 6)), "full iron, low health");
    }

    @Test
    void sprintFlag() {
        assertTrue(FightOrFlee.sprint(true, 20));
        assertFalse(FightOrFlee.sprint(true, 6), "vanilla: no sprint at food 6");
        assertFalse(FightOrFlee.sprint(false, 20));
    }
}
