package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.BuildQueue.Priority;
import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.CostToGo;
import io.github.mojolowjo.entropybot.route.RouteCore;
import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RoutePlan;
import io.github.mojolowjo.entropybot.route.RouteRequest;
import io.github.mojolowjo.entropybot.route.RouteStore;
import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The routing stage 1 review fixes in this package: S3 (end to end), S5 (caps), S7, and the cancelled-plan note. */
class RouteReviewFixesTest {
    final RouteFakes.Queue queue = new RouteFakes.Queue();
    final RouteCounters counters = new RouteCounters();
    final List<String> logLines = new CopyOnWriteArrayList<>();
    final RouteLog log = RouteLog.of(logLines::add);
    final RouteSchedulerTest.Env env = new RouteSchedulerTest.Env();
    final RouteSchedulerTest.Work work = new RouteSchedulerTest.Work();
    static final SectionKey K = new SectionKey(0, 3, 4, 3);
    RouteEngine engine;
    RoutePool pool;

    @AfterEach
    void stop() {
        if (engine != null) assertTrue(engine.stop(2000));
        if (pool != null) assertTrue(pool.stop(2000));
    }

    RouteEngine engine(RouteStore store, io.github.mojolowjo.entropybot.route.Router router) {
        engine = new RouteEngine(store, queue, router, counters, log, 1, work, (d, x, y, z) -> List.of(K),
                (d, a, b) -> List.of(K), () -> 0L);
        return engine;
    }

    // ---- S3: a change during a build, through the scheduler and the engine ----

    @Test
    void aBlockChangeDuringTheFirstBuildLeavesTheBoxStaleAndQueued() throws Exception {
        RouteStore store = RouteCore.memoryStore();
        RouteEngine e = engine(store, req -> null);
        work.entered = new CountDownLatch(1);
        work.release = new CountDownLatch(1);
        queue.offer(K, Priority.NOW);
        e.scheduler.tick(env);
        assertTrue(work.entered.await(2, TimeUnit.SECONDS));
        assertNull(store.get(K), "still building");
        e.markStaleAround(0, 50, 70, 50);       // the change lands while the worker builds
        work.release.countDown();
        RoutePoolTest.waitFree(e.pool, e.pool.size());
        assertNotNull(store.get(K));
        assertTrue(store.isStale(K), "built from terrain older than the change");
        assertEquals(Priority.STALE, queue.where(K), "queued again");
    }

    @Test
    void aBuildWithNoChangeIsFresh() throws Exception {
        RouteStore store = RouteCore.memoryStore();
        RouteEngine e = engine(store, req -> null);
        queue.offer(K, Priority.NOW);
        e.scheduler.tick(env);
        RoutePoolTest.waitFree(e.pool, e.pool.size());
        assertNotNull(store.get(K));
        assertFalse(store.isStale(K));
        assertFalse(store.noteChangeWhileBuilding(K), "the build's stamp ended");
    }

    // ---- the cancelled-plan note ----

    @Test
    void aCancelledPlanIsSkippedByThePlanningThread() throws Exception {
        CountDownLatch inFirst = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        RouteEngine e = engine(new RouteFakes.Store(), req -> {
            calls.incrementAndGet();
            inFirst.countDown();
            RoutePoolTest.await(release);
            return new RoutePlan(RoutePlan.Status.OK, 1, List.of(), CostToGo.empty(), 0, 0, "ok");
        });
        RouteRequest r = new RouteRequest(0, new Cell(0, 64, 0), new Cell(100, 64, 0), 0, null, 3.563);
        CompletableFuture<RoutePlan> first = e.plan(r, null);
        assertTrue(inFirst.await(2, TimeUnit.SECONDS));
        CompletableFuture<RoutePlan> second = e.plan(r, null);
        assertTrue(second.cancel(false), "the walk gave up waiting");
        release.countDown();
        assertEquals(RoutePlan.Status.OK, first.get(2, TimeUnit.SECONDS).status());
        CompletableFuture<RoutePlan> third = e.plan(r, null);
        assertEquals(RoutePlan.Status.OK, third.get(2, TimeUnit.SECONDS).status());
        assertEquals(2, calls.get(), "the cancelled one never reached the router");
        assertEquals(1, e.skippedPlans());
        assertTrue(e.line().contains("cancelled plans skipped 1"), e.line());
    }

    // ---- S5: caps ----

    @Test
    void aLongStretchIsCutAt2000Blocks() {
        Cell a = new Cell(0, 64, 0);
        Cell near = new Cell(1500, 64, 0);
        assertSame(near, RouteEngine.capAlong(a, near, RouteEngine.MAX_ALONG_BLOCKS));
        Cell c = RouteEngine.capAlong(a, new Cell(30000, 64, 40000), 2000);
        assertEquals(new Cell(1200, 64, 1600), c);
        RouteEngine e = engine(new RouteFakes.Store(), req -> null);
        e.rebuildAlong(0, a, new Cell(100000, 64, 0), RouteFakes.everywhere());
        assertTrue(e.line().contains("1 cut at 2000 blocks"), e.line());
    }

    @Test
    void theBoxListIsCappedAndCounted() {
        AreaBoxes small = new AreaBoxes(List.of(new int[]{0, 0, 0, 31, 31, 0, 31}), -64, 319);
        assertEquals(2 * 2 * 2, small.count(0));
        assertEquals(8, small.all(0).size());
        assertEquals(3, small.all(0, 3).size());
        // 20000 x 20000 blocks, the full height: 1250 * 1250 * 24 boxes
        AreaBoxes huge = new AreaBoxes(List.of(new int[]{0, -10000, -10000, 9999, 9999, -64, 319}), -64, 319);
        assertEquals(1250L * 1250 * 24, huge.count(0));
        assertEquals(AreaBoxes.MAX_BOXES, huge.all(0).size());
    }

    @Test
    void refillSaysWhenTheCapLeftBoxesOut() {
        pool = new RoutePool(1, "test-route", (w, t) -> counters.workerException(w, t, log));
        RouteScheduler s = new RouteScheduler(new RouteFakes.Store(), queue, counters, log, pool, work, () -> 0L);
        AreaBoxes huge = new AreaBoxes(List.of(new int[]{0, -10000, -10000, 9999, 9999, -64, 319}), -64, 319);
        s.refillIdle(huge, 0, List.of());
        assertNotNull(s.boxCap());
        assertTrue(s.line().contains("CAPPED"), s.line());
        assertTrue(logLines.stream().anyMatch(l -> l.contains("only the first 200000")), logLines.toString());
        s.refillIdle(new AreaBoxes(List.of(new int[]{0, 0, 0, 31, 31, 0, 31}), -64, 319), 0, List.of());
        assertNull(s.boxCap());
    }

    // ---- S7: a chunk load re-queues its neighbours' coarse boxes ----

    @Test
    void aChunkLoadQueuesTheNeighboursCoarseBoxes() throws Exception {
        RouteFakes.Store store = new RouteFakes.Store();
        pool = new RoutePool(1, "test-route", (w, t) -> counters.workerException(w, t, log));
        RouteScheduler s = new RouteScheduler(store, queue, counters, log, pool, work, () -> 0L);
        AreaBoxes one = new AreaBoxes(List.of(new int[]{0, 0, 0, 47, 47, 64, 79}), -64, 319);
        env.areas = one;
        SectionKey neighbour = new SectionKey(0, 2, 4, 1), live = new SectionKey(0, 0, 4, 1);
        store.put(RouteFakes.record(neighbour, SectionRecord.Quality.COARSE));
        store.put(RouteFakes.record(live, SectionRecord.Quality.LIVE));
        store.put(RouteFakes.record(new SectionKey(0, 1, 4, 1), SectionRecord.Quality.LIVE));
        s.chunkLoaded(0, 1, 1);
        s.tick(env);
        RoutePoolTest.waitFree(pool, pool.size());
        assertEquals(1L, s.numbers().get("neighbourCoarse"), "only the coarse neighbour");
        assertTrue(work.built.contains(neighbour), "rebuilt live now: " + work.built);
        assertFalse(work.built.contains(live));
    }
}
