package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.BuildQueue.Priority;
import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.CostToGo;
import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RoutePlan;
import io.github.mojolowjo.entropybot.route.RouteRequest;
import io.github.mojolowjo.entropybot.route.Router;
import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.github.mojolowjo.entropybot.routing.RouteRules.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteEngineTest {
    final RouteFakes.Store store = new RouteFakes.Store();
    final RouteFakes.Queue queue = new RouteFakes.Queue();
    final RouteCounters counters = new RouteCounters();
    final List<String> logLines = new CopyOnWriteArrayList<>();
    final SectionKey k1 = new SectionKey(0, 1, 4, 1), k2 = new SectionKey(0, 2, 4, 1);
    volatile Router router = req -> new RoutePlan(RoutePlan.Status.OK, 12, List.of(), CostToGo.empty(), 3, 5, "ok");
    RouteEngine engine;

    RouteEngine engine() {
        engine = new RouteEngine(store, queue, req -> router.plan(req), counters, RouteLog.of(logLines::add), 2,
                new RouteSchedulerTest.Work(), (d, x, y, z) -> List.of(k1, k2), (d, a, b) -> List.of(k1), () -> 0L);
        return engine;
    }

    @AfterEach
    void stop() {
        if (engine != null) assertTrue(engine.stop(2000));
    }

    @Test
    void onlyAWalkabilityChangeMarksBuiltBoxesStale() {
        RouteEngine e = engine();
        store.put(RouteFakes.record(k1, SectionRecord.Quality.LIVE));
        assertEquals(0, e.blockChanged(0, 20, 70, 20, PASSABLE, PASSABLE, false), "a crop growing");
        assertEquals(0, e.blockChanged(0, 20, 70, 20, SOLID, SOLID, false), "a furnace lighting");
        assertEquals(0, e.blockChanged(0, 20, 70, 20, LIQUID_FLOW, LIQUID_FLOW, false), "a water level");
        assertFalse(store.isStale(k1));
        assertEquals(1, e.blockChanged(0, 20, 70, 20, PASSABLE, SOLID, false), "a block placed");
        assertTrue(store.isStale(k1));
        assertNull(store.get(k2));
        assertFalse(store.isStale(k2), "a box not built yet is not marked");
        assertEquals(Priority.STALE, queue.where(k1));
        assertNull(queue.where(k2));
        assertEquals(0, e.blockChanged(0, 20, 70, 20, PASSABLE, SOLID, false), "already stale: no second mark");
    }

    @Test
    void partialShapesCountAnyStateChange() {
        RouteEngine e = engine();
        store.put(RouteFakes.record(k1, SectionRecord.Quality.LIVE));
        assertEquals(0, e.blockChanged(0, 1, 2, 3, PARTIAL, PARTIAL, true));
        assertEquals(1, e.blockChanged(0, 1, 2, 3, PARTIAL, PARTIAL, false), "a door opening");
    }

    @Test
    void aBrokenChangeLookupIsCountedNotThrown() {
        engine = new RouteEngine(store, queue, router, counters, RouteLog.of(logLines::add), 1,
                new RouteSchedulerTest.Work(), (d, x, y, z) -> { throw new UnsupportedOperationException("stage B"); },
                (d, a, b) -> List.of(), () -> 0L);
        assertEquals(0, engine.blockChanged(0, 1, 2, 3, PASSABLE, SOLID, false));
        assertEquals(1, counters.snapshot(0, 0, 0).workerExceptions());
    }

    @Test
    void planAnswersEvenWhenNotAvailable() throws Exception {
        RouteEngine e = engine();
        RoutePlan p = e.plan(req(), "breaking or placing is on").get(1, TimeUnit.SECONDS);
        assertEquals(RoutePlan.Status.BAD_REQUEST, p.status());
        assertTrue(p.reason().contains("breaking or placing is on"));
        assertNotNull(p.table());
    }

    @Test
    void planRunsOnThePlanningThread() throws Exception {
        RouteEngine e = engine();
        String[] thread = new String[1];
        router = req -> {
            thread[0] = Thread.currentThread().getName();
            return new RoutePlan(RoutePlan.Status.OK, 12, List.of(), CostToGo.empty(), 3, 5, "ok");
        };
        RoutePlan p = e.plan(req(), null).get(2, TimeUnit.SECONDS);
        assertEquals(RoutePlan.Status.OK, p.status());
        assertEquals("entropybot-route-plan", thread[0]);
    }

    @Test
    void aPlannerExceptionComesBackAsARefusedPlanAndIsCounted() throws Exception {
        RouteEngine e = engine();
        router = req -> { throw new IllegalStateException("bad graph"); };
        RoutePlan p = e.plan(req(), null).get(2, TimeUnit.SECONDS);
        assertEquals(RoutePlan.Status.BAD_REQUEST, p.status());
        assertTrue(p.reason().contains("bad graph"));
        assertEquals(1, counters.snapshot(0, 0, 0).workerExceptions());
        assertTrue(logLines.stream().anyMatch(l -> l.contains("route error in plan")));
    }

    @Test
    void afterStopPlansAreRefusedNotThrown() throws Exception {
        RouteEngine e = engine();
        assertTrue(e.stop(2000));
        RoutePlan p = e.plan(req(), null).get(1, TimeUnit.SECONDS);
        assertEquals(RoutePlan.Status.BAD_REQUEST, p.status());
        engine = null;
    }

    @Test
    void buildAlongQueuesTheCorridorInsideTheAreas() {
        RouteEngine e = engine();
        AreaBoxes areas = new AreaBoxes(List.of(new int[]{0, 0, 0, 47, 47, -64, 319}), -64, 319);   // sx, sz 0..2
        store.put(RouteFakes.record(new SectionKey(0, 2, 4, 1), SectionRecord.Quality.LIVE));  // built: skipped
        int n = e.requestBuildAlong(0, new Cell(20, 70, 20), new Cell(30, 70, 30), areas);
        // k1 = (1,4,1) and its sideways neighbours (0,4,1) (2,4,1) (1,4,0) (1,4,2); (2,4,1) is built
        assertEquals(4, n);
        assertEquals(Priority.NOW, queue.where(k1));
        assertEquals(Priority.NOW, queue.where(new SectionKey(0, 0, 4, 1)));
        assertNull(queue.where(new SectionKey(0, 2, 4, 1)));
    }

    static RouteRequest req() {
        return new RouteRequest(0, new Cell(0, 70, 0), new Cell(100, 70, 100), 0, null, 3.563);
    }
}
