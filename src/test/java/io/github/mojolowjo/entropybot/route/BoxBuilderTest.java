package io.github.mojolowjo.entropybot.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.mojolowjo.entropybot.route.FakeWorld.SOLID;
import static io.github.mojolowjo.entropybot.route.FakeWorld.WATER;
import static org.junit.jupiter.api.Assertions.*;

class BoxBuilderTest {
    static final SectionKey BOX = new SectionKey(0, 0, 4, 0); // x 0..15, y 64..79, z 0..15

    static SectionRecord build(CellMoves w, SectionKey k) {
        return BoxBuilder.build(new BuildInput(k, w, SectionRecord.Quality.LIVE, false, 1L));
    }

    static int door(SectionRecord r, int face) {
        int found = -1;
        for (int i = 0; i < r.doorCount(); i++) {
            if (r.doors().get(i).face() == face) {
                assertEquals(-1, found, "more than one door on face " + face);
                found = i;
            }
        }
        assertTrue(found >= 0, "no door on face " + face);
        return found;
    }

    static long doorsOn(SectionRecord r, int face) {
        return r.doors().stream().filter(d -> d.face() == face).count();
    }

    /** Cheapest crossing from any door on face a to any door on face b. */
    static double cheapest(SectionRecord r, int a, int b) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < r.doorCount(); i++)
            for (int j = 0; j < r.doorCount(); j++)
                if (r.doors().get(i).face() == a && r.doors().get(j).face() == b) best = Math.min(best, r.ticks(i, j));
        return best;
    }

    @Test
    void flatBoxHasFourSideDoorsWithSymmetricCosts() {
        SectionRecord r = build(new FakeWorld(64), BOX);
        // four sides, each cut into 4 pieces of 4 cells
        for (int f : new int[]{0, 1, 4, 5}) assertEquals(4, doorsOn(r, f), "face " + f);
        assertEquals(16, r.doorCount());
        for (Door d : r.doors()) {
            assertTrue(d.canLeave() && d.canEnter());
            assertEquals(4, d.maskCount(), "4 cells a piece");
            assertEquals(64, d.repY());
        }
        for (int i = 0; i < r.doorCount(); i++)
            for (int j = 0; j < r.doorCount(); j++)
                assertEquals(r.ticks(i, j), r.ticks(j, i), 1e-9, "symmetric " + i + " " + j);
        assertEquals(cheapest(r, 0, 1), cheapest(r, 4, 5), 1e-9);
        // 15 steps inside plus the exit move, at 4.633 a block
        assertEquals(16 * FakeWorld.WALK, cheapest(r, 0, 1), 0.5);
        // turning a corner is cheaper than crossing
        assertTrue(cheapest(r, 0, 4) < cheapest(r, 0, 1));
        assertEquals(SectionRecord.Quality.LIVE, r.quality());
    }

    @Test
    void wallWithOneGapHasOneDoorOnThatFace() {
        FakeWorld w = new FakeWorld(64);
        w.fill(15, 64, -5, 16, 79, 20, SOLID);
        w.set(15, 64, 5, FakeWorld.AIR).set(15, 65, 5, FakeWorld.AIR);
        w.set(16, 64, 5, FakeWorld.AIR).set(16, 65, 5, FakeWorld.AIR);
        SectionRecord r = build(w, BOX);
        int e = door(r, 1);
        Door d = r.doors().get(e);
        assertEquals(15, d.repX());
        assertEquals(5, d.repZ());
        assertEquals(64, d.repY());
        assertTrue(d.maskBit(5, 0));
        assertTrue(d.maskCount() <= 3);
        // west side to the gap: around the wall end costs more than straight across the flat box
        assertTrue(cheapest(r, 0, 1) < Double.POSITIVE_INFINITY);
        assertTrue(cheapest(r, 0, 1) > 15 * FakeWorld.WALK);
    }

    @Test
    void oneWayDropIsLeaveOnlyAndDirectional() {
        FakeWorld w = new FakeWorld(69);              // the east side stands at y 69
        w.fill(-20, 69, -20, 15, 71, 35, SOLID);      // the west side (this box) stands at y 72
        SectionRecord r = build(w, BOX);
        SectionRecord n = build(w, new SectionKey(0, 1, 4, 0));
        assertEquals(4, doorsOn(r, 1));
        boolean matched = false;
        for (int e = 0; e < r.doorCount(); e++) {
            Door d = r.doors().get(e);
            if (d.face() != 1) continue;
            assertTrue(d.canLeave(), "can drop down to the east");
            assertFalse(d.canEnter(), "cannot climb 3 back up");
            for (int j = 0; j < r.doorCount(); j++) assertEquals(Double.POSITIVE_INFINITY, r.ticks(e, j));
            for (int g = 0; g < n.doorCount(); g++)
                if (n.doors().get(g).face() == 0 && DoorGraphRouter.linkTo(d, n, g, 3.563) < 20) matched = true;
        }
        assertTrue(matched, "the drop links to a door below at a small cost (no plane point in common)");
        assertTrue(cheapest(r, 0, 1) < Double.POSITIVE_INFINITY);
        for (Door back : n.doors()) {
            if (back.face() != 0) continue;
            assertTrue(back.canEnter());
            assertFalse(back.canLeave());
        }
    }

    @Test
    void staircaseLeavesThroughTheTopFace() {
        FakeWorld w = new FakeWorld(66);                                       // ground: feet at y 66
        for (int i = 0; i <= 15; i++) w.fill(i, 66, 3, i, 66 + i, 5, SOLID);  // feet at y 67+i: 79 at x 12, 80 at x 13
        SectionRecord r = build(w, BOX);
        assertTrue(doorsOn(r, 3) >= 1);
        for (Door d : r.doors()) {
            if (d.face() != 3) continue;
            assertTrue(d.canLeave() && d.canEnter());
            assertEquals(79, d.maxY());
            assertEquals(12, d.minX());
        }
        // from the ground on the west side up the stairs and out the top, and back down
        assertTrue(cheapest(r, 0, 3) < Double.POSITIVE_INFINITY);
        assertTrue(cheapest(r, 3, 0) < Double.POSITIVE_INFINITY);
        assertTrue(cheapest(r, 3, 0) < cheapest(r, 0, 3), "down is cheaper than up");
        assertEquals(0, doorsOn(r, 2), "nothing through the floor");
    }

    @Test
    void waterIsSlow() {
        SectionRecord flat = build(new FakeWorld(64), BOX);
        FakeWorld w = new FakeWorld(64);
        w.fill(-5, 64, -5, 20, 65, 20, WATER);
        SectionRecord wet = build(w, BOX);
        double dry = cheapest(flat, 0, 1), swim = cheapest(wet, 0, 1);
        assertTrue(swim > 2 * dry, "swim " + swim + " vs walk " + dry);
        assertTrue(swim < Double.POSITIVE_INFINITY);
    }

    @Test
    void allSolidBoxHasNoDoors() {
        SectionRecord r = build(new FakeWorld(64), new SectionKey(0, 0, 3, 0)); // y 48..63, all solid
        assertTrue(r.empty());
        assertEquals(0, r.crossing().length);
    }

    @Test
    void boxBuiltWithBreakingIsNeverStored() {
        RouteStore store = RouteCore.memoryStore();
        RouteCounters c = new RouteCounters();
        assertFalse(BoxBuilder.buildInto(new BuildInput(BOX, new FakeWorld(64), SectionRecord.Quality.LIVE, true, 1),
                store, c, null));
        assertEquals(0, store.size());
        assertEquals(1, c.snapshot(0, 0, 0).refusedBreaking());
        assertTrue(BoxBuilder.buildInto(new BuildInput(BOX, new FakeWorld(64), SectionRecord.Quality.LIVE, false, 1),
                store, c, null));
        assertEquals(1, store.size());
        assertEquals(1, c.snapshot(0, 0, 0).boxesBuilt());
    }

    @Test
    void brokenMovesAreCountedNotThrown() {
        CellMoves bad = new CellMoves() {
            @Override
            public boolean standable(int x, int y, int z) {
                throw new IllegalStateException("chunk gone");
            }

            @Override
            public void forEachMove(int x, int y, int z, MoveSink s) {
            }
        };
        RouteCounters c = new RouteCounters();
        List<String> lines = new java.util.ArrayList<>();
        assertFalse(BoxBuilder.buildInto(new BuildInput(BOX, bad, SectionRecord.Quality.LIVE, false, 1),
                RouteCore.memoryStore(), c, RouteLog.of(lines::add)));
        RouteStats s = c.snapshot(0, 0, 0);
        assertEquals(1, s.workerExceptions());
        assertTrue(s.lastError().contains("chunk gone"));
        assertEquals(1, lines.size());
    }

    @Test
    void quarterTicksRoundDownAndCap() {
        assertEquals(18, SectionRecord.quarterTicks(4.6));
        assertEquals(SectionRecord.IMPOSSIBLE, SectionRecord.quarterTicks(Double.POSITIVE_INFINITY));
        assertEquals(16383 * 4, SectionRecord.quarterTicks(1e9));
    }
}
