package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.BuildInput;
import io.github.mojolowjo.entropybot.route.BuildQueue.Priority;
import io.github.mojolowjo.entropybot.route.CellMoves;
import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RouteStore;
import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RouteSchedulerTest {
    final RouteFakes.Store store = new RouteFakes.Store();
    final RouteFakes.Queue queue = new RouteFakes.Queue();
    final RouteCounters counters = new RouteCounters();
    final List<String> logLines = new CopyOnWriteArrayList<>();
    final RouteLog log = RouteLog.of(logLines::add);
    final AtomicLong clock = new AtomicLong(0);
    RoutePool pool;

    final Env env = new Env();
    final Work work = new Work();

    static final class Env implements RouteScheduler.BuildEnv {
        volatile String pause;
        volatile boolean idle = true, lagging, walkingOnly = true;
        AreaBoxes areas = RouteFakes.everywhere();
        Set<SectionKey> noTerrain = new HashSet<>();
        Set<SectionKey> coarse = new HashSet<>();
        int movesMade;

        public String pauseReason() { return pause; }
        public boolean idle() { return idle; }
        public boolean lagging() { return lagging; }
        public AreaBoxes areas() { return areas; }

        public SectionRecord.Quality terrain(SectionKey k) {
            if (noTerrain.contains(k)) return null;
            return coarse.contains(k) ? SectionRecord.Quality.COARSE : SectionRecord.Quality.LIVE;
        }

        public RouteScheduler.WorkerMoves newMoves() {
            movesMade++;
            return new RouteScheduler.WorkerMoves(new RouteFakes.Flat(64), walkingOnly);
        }
    }

    static final class Work implements RouteScheduler.BoxWork {
        final List<SectionKey> built = new CopyOnWriteArrayList<>();
        final List<BuildInput> inputs = new CopyOnWriteArrayList<>();
        final List<SectionKey> hashed = new CopyOnWriteArrayList<>();
        volatile CountDownLatch entered, release;
        volatile RuntimeException fail;
        volatile boolean rehashSays = true;

        public boolean build(BuildInput in, RouteStore store, RouteCounters counters, RouteLog log) {
            if (entered != null) entered.countDown();
            if (release != null) RoutePoolTest.await(release);
            if (fail != null) throw fail;
            built.add(in.key());
            inputs.add(in);
            if (!in.allowBreak()) store.put(RouteFakes.record(in.key(), in.quality()));
            return !in.allowBreak();
        }

        public long walkHash(SectionKey key, CellMoves moves) {
            hashed.add(key);
            return 42;
        }

        public boolean rehash(RouteStore store, SectionKey key, long walkHash) {
            return rehashSays;
        }
    }

    RouteScheduler scheduler(int workers) {
        pool = new RoutePool(workers, "test-route", (w, t) -> counters.workerException(w, t, log));
        return new RouteScheduler(store, queue, counters, log, pool, work, clock::get);
    }

    @AfterEach
    void stop() {
        if (pool != null) assertTrue(pool.stop(2000));
    }

    static SectionKey k(int x) {
        return new SectionKey(0, x, 4, 0);
    }

    void settle() throws InterruptedException {
        RoutePoolTest.waitFree(pool, pool.size());
    }

    @Test
    void nowQueueBeforeIdleQueue() throws Exception {
        RouteScheduler s = scheduler(1);
        queue.offer(k(1), Priority.REST);
        queue.offer(k(2), Priority.PLACES);
        queue.offer(k(3), Priority.NOW);
        s.tick(env);
        settle();
        s.tick(env);
        settle();
        assertEquals(List.of(k(3), k(2), k(1)), work.built, "now first, then places, then the rest");
    }

    @Test
    void idleQueueWaitsForIdleButNowAlwaysRuns() throws Exception {
        RouteScheduler s = scheduler(2);
        env.idle = false;
        queue.offer(k(1), Priority.REST);
        s.tick(env);
        settle();
        assertTrue(work.built.isEmpty());
        assertEquals(1, queue.idleSize());
        queue.offer(k(2), Priority.NOW);
        s.tick(env);
        settle();
        assertEquals(List.of(k(2)), work.built);
        assertEquals(1, queue.idleSize());
    }

    @Test
    void anIdleBatchStopsWhenAJobStarts() throws Exception {
        RouteScheduler s = scheduler(1);
        for (int i = 1; i <= 4; i++) queue.offer(k(i), Priority.REST);
        work.entered = new CountDownLatch(1);
        work.release = new CountDownLatch(1);
        s.tick(env);
        assertTrue(work.entered.await(2, TimeUnit.SECONDS));
        env.idle = false;               // a job starts
        s.tick(env);
        assertFalse(s.idleFlag());
        work.release.countDown();
        settle();
        assertEquals(List.of(k(1)), work.built, "only the box in progress finishes");
        assertEquals(3, queue.idleSize(), "the rest goes back to the idle queue");
        assertEquals(1L, s.numbers().get("idleBreaks"));
        assertEquals(3L, s.numbers().get("requeued"));
    }

    @Test
    void nothingIsHandedOutWhilePaused() throws Exception {
        RouteScheduler s = scheduler(3);
        env.pause = "breaking or placing is on";
        queue.offer(k(1), Priority.NOW);
        s.tick(env);
        settle();
        assertTrue(work.built.isEmpty());
        assertEquals(0, env.movesMade, "no context while breaking is on");
        assertEquals("breaking or placing is on", s.pauseReason());
        assertTrue(s.line().contains("paused (breaking or placing is on)"));
        env.pause = null;
        s.tick(env);
        settle();
        assertEquals(List.of(k(1)), work.built);
        assertNull(s.pauseReason());
    }

    @Test
    void laggingTicksHandOutNothing() throws Exception {
        RouteScheduler s = scheduler(1);
        env.lagging = true;
        queue.offer(k(1), Priority.NOW);
        s.tick(env);
        settle();
        assertTrue(work.built.isEmpty());
        assertEquals(1L, s.numbers().get("lagSkips"));
    }

    @Test
    void aContextWithBreakingOnBuildsWithAllowBreak() throws Exception {
        RouteScheduler s = scheduler(1);
        env.walkingOnly = false;
        queue.offer(k(1), Priority.NOW);
        s.tick(env);
        settle();
        assertTrue(work.inputs.get(0).allowBreak(), "review R5: the builder must refuse to store it");
        assertNull(store.get(k(1)));
    }

    @Test
    void atMostThreeContextsASecondAndTwoBatchesATick() throws Exception {
        RouteScheduler s = scheduler(3);
        for (int i = 0; i < 80; i++) queue.offer(k(i), Priority.NOW);
        s.tick(env);
        assertTrue(s.numbers().get("contexts") <= RouteScheduler.MAX_BATCHES_PER_TICK);
        settle();
        for (int t = 0; t < 5; t++) {
            s.tick(env);
            settle();
        }
        assertEquals(3L, s.numbers().get("contexts"));
        assertEquals(3 * RouteScheduler.NOW_BATCH, work.built.size());
        clock.addAndGet(1000);
        s.tick(env);
        settle();
        assertEquals(5L, s.numbers().get("contexts"));
    }

    @Test
    void workerExceptionsAreCountedAndLogged() throws Exception {
        RouteScheduler s = scheduler(1);
        work.fail = new IllegalStateException("broken moves");
        queue.offer(k(1), Priority.NOW);
        queue.offer(k(2), Priority.NOW);
        s.tick(env);
        settle();
        assertEquals(2, counters.snapshot(0, 0, 0).workerExceptions());
        assertTrue(counters.snapshot(0, 0, 0).lastError().contains("broken moves"));
        assertTrue(logLines.stream().anyMatch(l -> l.contains("route error in build") && l.contains("broken moves")));
    }

    @Test
    void skipsBoxesOutsideAreasWithoutTerrainOrAlreadyBuilt() throws Exception {
        RouteScheduler s = scheduler(1);
        env.areas = new AreaBoxes(List.of(new int[]{0, 0, 0, 47, 15, -64, 319}), -64, 319);   // sx 0..2, sz 0
        env.noTerrain.add(k(1));
        store.put(RouteFakes.record(k(2), SectionRecord.Quality.LIVE));
        store.put(RouteFakes.record(new SectionKey(0, 0, 4, 0), SectionRecord.Quality.COARSE));   // coarse, live now
        queue.offer(k(5), Priority.NOW);        // outside
        queue.offer(k(1), Priority.NOW);        // no terrain
        queue.offer(k(2), Priority.NOW);        // built, not stale
        queue.offer(k(0), Priority.NOW);        // coarse -> rebuilt live
        s.tick(env);
        settle();
        assertEquals(List.of(k(0)), work.built);
        assertEquals(1L, s.numbers().get("notWanted"));
        assertEquals(1L, s.numbers().get("unknownTerrain"));
        assertEquals(1L, s.numbers().get("alreadyGood"));
        store.markStale(k(2));
        queue.offer(k(2), Priority.NOW);
        s.tick(env);
        settle();
        assertTrue(work.built.contains(k(2)), "a stale box is rebuilt");
    }

    @Test
    void chunkLoadQueuesMissingAndCoarseAndRehashesLiveBoxes() throws Exception {
        RouteScheduler s = scheduler(1);
        env.areas = new AreaBoxes(List.of(new int[]{0, 0, 0, 15, 15, 0, 47}), -64, 319);       // sy 0..2 of column 0,0
        SectionKey live = new SectionKey(0, 0, 0, 0), coarse = new SectionKey(0, 0, 1, 0), missing = new SectionKey(0, 0, 2, 0);
        store.put(RouteFakes.record(live, SectionRecord.Quality.LIVE));
        store.put(RouteFakes.record(coarse, SectionRecord.Quality.COARSE));
        s.chunkLoaded(0, 0, 0);
        assertEquals(1, s.pendingColumns());
        env.idle = false;
        s.tick(env);
        assertEquals(1, s.pendingColumns(), "chunk loads wait for idle time");
        env.idle = true;
        s.tick(env);
        settle();
        assertEquals(List.of(live), work.hashed, "the live box gets its walk hash checked");
        assertTrue(work.built.containsAll(List.of(coarse, missing)));
        assertEquals(Priority.STALE, queue.where(live), "a changed hash queues the box");
    }

    @Test
    void refillPutsPlacesFirstThenStaleThenTheRest() {
        RouteScheduler s = scheduler(1);
        AreaBoxes a = new AreaBoxes(List.of(new int[]{0, 0, 0, 159, 159, 0, 15}), -64, 319);  // 10x10 boxes, sy 0
        SectionKey stale = new SectionKey(0, 9, 0, 9);
        store.put(RouteFakes.record(stale, SectionRecord.Quality.LIVE));
        store.markStale(stale);
        int n = s.refillIdle(a, 0, List.of(new int[]{8, 8, 8}));
        assertEquals(100, n);
        assertEquals(Priority.PLACES, queue.where(new SectionKey(0, 0, 0, 0)));
        assertEquals(Priority.PLACES, queue.where(new SectionKey(0, 2, 0, 2)));
        assertEquals(Priority.STALE, queue.where(stale));
        assertEquals(Priority.REST, queue.where(new SectionKey(0, 5, 0, 5)));
        assertEquals(new SectionKey(0, 0, 0, 0), queue.poll(true), "the place's own box comes first");
    }
}
