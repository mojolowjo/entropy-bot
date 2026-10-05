package io.github.mojolowjo.entropybot.route;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.github.mojolowjo.entropybot.route.FakeWorld.SOLID;
import static io.github.mojolowjo.entropybot.route.FakeWorld.WATER;
import static org.junit.jupiter.api.Assertions.*;

class RouterTest {
    static final double COEF = 3.563;
    static final Cell START = new Cell(8, 64, 8);
    static final Cell GOAL = new Cell(72, 64, 8);

    static RouteStore buildBoxes(FakeWorld w, int sx1, int sx2, int sz1, int sz2) {
        RouteStore store = RouteCore.memoryStore();
        for (int sx = sx1; sx <= sx2; sx++)
            for (int sz = sz1; sz <= sz2; sz++)
                assertTrue(BoxBuilder.buildInto(new BuildInput(new SectionKey(0, sx, 4, sz), w,
                        SectionRecord.Quality.LIVE, false, 1), store, null, null));
        return store;
    }

    static RoutePlan plan(RouteStore store, CellMoves moves, Cell s, Cell g) {
        return RouteCore.router(store, null, null).plan(new RouteRequest(0, s, g, 0, moves, COEF));
    }

    static double plain(Cell p, Cell g) {
        return p.dist(g) * COEF;
    }

    @Test
    void flatCorridorPlansAndTheTableGuides() {
        FakeWorld w = new FakeWorld(64);
        RouteStore store = buildBoxes(w, 0, 4, 0, 0);
        for (CellMoves m : new CellMoves[]{w, null}) {
            RoutePlan p = plan(store, m, START, GOAL);
            assertEquals(RoutePlan.Status.OK, p.status(), p.reason());
            assertEquals(4, p.path().size());
            for (int i = 0; i < 4; i++) assertEquals(i, p.path().get(i).box().sx());
            assertTrue(p.ticks() > 50 * FakeWorld.WALK && p.ticks() < 75 * FakeWorld.WALK, "ticks " + p.ticks());
            // the cost to go shrinks toward the goal
            double h0 = RouteGoalMath.heuristic(p.table(), 8, 64, 8, plain(START, GOAL), COEF);
            double h2 = RouteGoalMath.heuristic(p.table(), 40, 64, 8, plain(new Cell(40, 64, 8), GOAL), COEF);
            assertTrue(h0 > h2 && h2 > 0);
            assertTrue(h0 >= plain(START, GOAL));
        }
    }

    @Test
    void detourAroundALakeBeatsTheStraightLine() {
        FakeWorld w = new FakeWorld(64);
        w.fill(16, 64, -16, 63, 64, 31, WATER); // a lake over boxes sx 1..3, sz 0..1
        RouteStore store = buildBoxes(w, 0, 4, 0, 2);
        RoutePlan p = plan(store, w, START, GOAL);
        assertEquals(RoutePlan.Status.OK, p.status(), p.reason());
        boolean north = false;
        for (RoutePlan.Step s : p.path()) {
            SectionKey k = s.box();
            if (k.sz() == 2) north = true;
            assertFalse(k.sx() >= 1 && k.sx() <= 3 && k.sz() <= 1,
                    "crossed the lake at " + k + ", ticks " + p.ticks() + ", path " + p.path());
        }
        assertTrue(north, "went round by the dry row: " + p.path());
        // swimming straight across: 48 blocks of water alone
        assertTrue(p.ticks() < 48 * FakeWorld.WATER_WALK);
        // and the heuristic in the lake's first box points round it: more than the straight line says
        Cell inLake = new Cell(20, 64, 8);
        assertTrue(RouteGoalMath.heuristic(p.table(), 20, 64, 8, plain(inLake, GOAL), COEF) > plain(inLake, GOAL));
    }

    @Test
    void unreachableGoalSaysSoAndTheHeuristicStaysPlain() {
        FakeWorld w = new FakeWorld(64);
        w.fill(32, 64, -16, 47, 100, 63, SOLID); // a wall taller than the box, boxes sx 2 all solid
        RouteStore store = buildBoxes(w, 0, 4, 0, 2);
        RoutePlan p = plan(store, w, START, GOAL);
        assertEquals(RoutePlan.Status.UNREACHABLE, p.status());
        assertTrue(p.path().isEmpty());
        double pl = plain(START, GOAL);
        assertEquals(pl, RouteGoalMath.heuristic(p.table(), 8, 64, 8, pl, COEF), 1e-9, "never infinity");
    }

    @Test
    void aStaleBoxIsStillUsedButItsHeuristicIsPlain() {
        FakeWorld w = new FakeWorld(64);
        RouteStore store = buildBoxes(w, 0, 4, 0, 0);
        SectionKey mid = new SectionKey(0, 2, 4, 0);
        store.markStale(mid);
        RoutePlan p = plan(store, w, START, GOAL);
        assertEquals(RoutePlan.Status.OK, p.status());
        assertTrue(p.path().stream().anyMatch(s -> s.box().equals(mid)));
        Cell inMid = new Cell(40, 64, 8);
        assertEquals(plain(inMid, GOAL), RouteGoalMath.heuristic(p.table(), 40, 64, 8, plain(inMid, GOAL), COEF));
        Cell inFirst = new Cell(20, 64, 8);
        assertTrue(RouteGoalMath.heuristic(p.table(), 20, 64, 8, plain(inFirst, GOAL), COEF) >= plain(inFirst, GOAL));
    }

    @Test
    void missingBoxesAreReported() {
        FakeWorld w = new FakeWorld(64);
        RouteStore store = buildBoxes(w, 1, 4, 0, 0);
        assertEquals(RoutePlan.Status.NO_START_BOX, plan(store, w, START, GOAL).status());
        assertTrue(plan(store, w, START, GOAL).table().size() > 0, "the table still guides");
        assertEquals(RoutePlan.Status.NO_GOAL_BOX, plan(store, w, START, new Cell(200, 64, 8)).status());
        RoutePlan same = plan(store, w, new Cell(20, 64, 8), new Cell(25, 64, 9));
        assertEquals(RoutePlan.Status.OK, same.status());
        assertTrue(same.path().isEmpty());
    }

    @Test
    void goalRadiusAndABadGoalCellStillWork() {
        FakeWorld w = new FakeWorld(64);
        RouteStore store = buildBoxes(w, 0, 4, 0, 0);
        // goal is a solid block (a chest-like target): no standable cell -> straight-line seeds
        w.set(72, 64, 8, SOLID);
        RoutePlan p = plan(store, w, START, new Cell(72, 64, 8));
        assertEquals(RoutePlan.Status.OK, p.status(), p.reason());
        RoutePlan near = RouteCore.router(store, null, null)
                .plan(new RouteRequest(0, START, new Cell(72, 64, 8), 2, w, COEF));
        assertEquals(RoutePlan.Status.OK, near.status());
    }

    @Test
    void theHeuristicNeverMissesACheaperDoorInTheSameBox() {
        SectionKey k = new SectionKey(0, 0, 4, 0);
        long[] m = {1, 0, 0, 0};
        Door nearDear = new Door(0, m, 0, 64, 8, 0, 64, 0, 0, 64, 15, true, true);
        Door farCheap = new Door(1, m, 15, 64, 8, 15, 64, 0, 15, 64, 15, true, true);
        Map<SectionKey, CostToGo.BoxCosts> boxes = new HashMap<>();
        boxes.put(k, new CostToGo.BoxCosts(k, false, new Door[]{nearDear, farCheap}, new double[]{500, 10}));
        CostToGo t = new CostToGo(0, new SectionKey(0, 9, 4, 0), new Cell(150, 64, 8), boxes);
        double h = RouteGoalMath.heuristic(t, 1, 64, 8, 0, COEF);
        assertEquals(14 * COEF + 10, h, 1e-9);

        // property over a real table: equals the brute-force minimum over the box's doors, never below plain
        FakeWorld w = new FakeWorld(64);
        w.fill(16, 64, -16, 63, 64, 31, WATER);
        RouteStore store = buildBoxes(w, 0, 4, 0, 2);
        RoutePlan p = plan(store, w, START, GOAL);
        Random rnd = new Random(7);
        for (int n = 0; n < 2000; n++) {
            int x = rnd.nextInt(80), y = 64 + rnd.nextInt(16), z = rnd.nextInt(48);
            double pl = plain(new Cell(x, y, z), GOAL);
            double got = RouteGoalMath.heuristic(p.table(), x, y, z, pl, COEF);
            CostToGo.BoxCosts b = p.table().box(x, y, z);
            double brute = Double.POSITIVE_INFINITY;
            if (b != null && !p.table().inGoalBox(x, y, z))
                for (int e = 0; e < b.doors().length; e++)
                    brute = Math.min(brute, b.doors()[e].distanceTo(x, y, z) * COEF + b.exitTicks()[e]);
            double want = brute < Double.POSITIVE_INFINITY ? Math.max(pl, brute) : pl;
            assertEquals(want, got, 1e-9, "at " + x + " " + y + " " + z);
            assertTrue(got >= pl);
        }
    }

    @Test
    void emptyTableAndBadRequests() {
        assertEquals(5.0, RouteGoalMath.heuristic(null, 0, 0, 0, 5.0, COEF));
        assertEquals(5.0, RouteGoalMath.heuristic(CostToGo.empty(), 0, 0, 0, 5.0, COEF));
        RouteStore store = RouteCore.memoryStore();
        RoutePlan p = RouteCore.router(store, null, null).plan(new RouteRequest(0, null, GOAL, 0, null, COEF));
        assertEquals(RoutePlan.Status.BAD_REQUEST, p.status());
        assertNotNull(p.table());
    }

    @Test
    void plannerExceptionsAreCounted() {
        RouteStore broken = new RouteStore() {
            public SectionRecord get(SectionKey key) { return null; }
            public void put(SectionRecord rec) { }
            public void markStale(SectionKey key) { }
            public boolean isStale(SectionKey key) { return false; }
            public void markAllStale() { }
            public java.util.Collection<SectionKey> staleKeys() { return List.of(); }
            public void forEach(java.util.function.Consumer<SectionRecord> c) { throw new IllegalStateException("boom"); }
            public int size() { return 0; }
            public java.util.Collection<int[]> takeDirtyTiles() { return List.of(); }
        };
        RouteCounters c = new RouteCounters();
        RoutePlan p = RouteCore.router(broken, c, RouteLog.of(s -> { }))
                .plan(new RouteRequest(0, START, GOAL, 0, null, COEF));
        assertEquals(RoutePlan.Status.ERROR, p.status());
        assertEquals(1, c.snapshot(0, 0, 0).workerExceptions());
    }

    @Test
    void planningIsFastOnAFewHundredBoxes() {
        FakeWorld w = new FakeWorld(64);
        w.fill(16, 64, -16, 63, 64, 31, WATER);
        RouteStore store = buildBoxes(w, 0, 15, 0, 15); // 256 boxes, ~1000 doors
        long t0 = System.nanoTime();
        RoutePlan p = plan(store, null, START, new Cell(250, 64, 250));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(RoutePlan.Status.OK, p.status());
        assertTrue(ms < 500, "took " + ms + " ms");
    }
}
