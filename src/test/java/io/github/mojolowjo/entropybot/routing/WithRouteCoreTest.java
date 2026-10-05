package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.BuildQueue.Priority;
import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.RouteCore;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RoutePlan;
import io.github.mojolowjo.entropybot.route.RouteRequest;
import io.github.mojolowjo.entropybot.route.SectionKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The adapter on R1's real core (store, queue, box builder, router), on a flat fake world. Skipped while the core is
 * still the contract's stub ("stage B"); runs once route-r1 is merged.
 */
class WithRouteCoreTest {
    RouteEngine engine;

    @AfterEach
    void stop() {
        if (engine != null) assertTrue(engine.stop(2000));
    }

    static boolean coreBuilt() {
        try {
            RouteCore.memoryStore();
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    @Test
    void buildsBoxesOnWorkersAndPlansAcrossThem() throws Exception {
        assumeTrue(coreBuilt(), "R1's route core is not merged yet");
        List<String> lines = new CopyOnWriteArrayList<>();
        engine = RouteEngine.create(RouteLog.of(lines::add));
        RouteSchedulerTest.Env env = new RouteSchedulerTest.Env();
        env.areas = new AreaBoxes(List.of(new int[]{0, 0, 0, 63, 63, 64, 79}), -64, 319);   // 4 x 4 boxes at sy 4
        for (SectionKey k : env.areas.all(0)) engine.queue.offer(k, Priority.NOW);
        long end = System.currentTimeMillis() + 10_000;
        long t = 0;
        while (engine.store.size() < 16 && System.currentTimeMillis() < end) {
            // a fake game tick: the scheduler's clock is real time here (3 contexts a second)
            engine.scheduler.tick(env);
            Thread.sleep(20);
            t++;
        }
        assertEquals(16, engine.store.size(), "every box built: " + engine.line() + " / " + lines);
        assertEquals(0, engine.stats().workerExceptions(), String.valueOf(lines));
        RoutePlan p = engine.plan(new RouteRequest(0, new Cell(2, 64, 2), new Cell(60, 64, 60), 0, new RouteFakes.Flat(64), 4.6), null)
                .get(5, TimeUnit.SECONDS);
        assertEquals(RoutePlan.Status.OK, p.status(), p.reason());
        assertTrue(p.ticks() < Double.POSITIVE_INFINITY);
        assertTrue(p.table().size() > 0);
        // a block placed in a built box marks it stale through R1's boxesForBlockChange
        assertTrue(engine.blockChanged(0, 20, 64, 20, RouteRules.PASSABLE, RouteRules.SOLID, false) >= 1);
        assertTrue(engine.store.isStale(new SectionKey(0, 1, 4, 1)));
    }

    @Test
    void aBreakingContextIsNeverStored() throws Exception {
        assumeTrue(coreBuilt(), "R1's route core is not merged yet");
        engine = RouteEngine.create(RouteLog.of(s -> {}));
        RouteSchedulerTest.Env env = new RouteSchedulerTest.Env();
        env.walkingOnly = false;
        engine.queue.offer(new SectionKey(0, 0, 4, 0), Priority.NOW);
        engine.scheduler.tick(env);
        RoutePoolTest.waitFree(engine.pool, engine.pool.size());
        Thread.sleep(50);
        assertEquals(0, engine.store.size());
        assertEquals(1, engine.stats().refusedBreaking());
    }
}
