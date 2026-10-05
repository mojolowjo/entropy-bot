package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.BuildQueue;
import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.CostToGo;
import io.github.mojolowjo.entropybot.route.RouteCore;
import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RoutePlan;
import io.github.mojolowjo.entropybot.route.RouteRequest;
import io.github.mojolowjo.entropybot.route.RouteStats;
import io.github.mojolowjo.entropybot.route.RouteStore;
import io.github.mojolowjo.entropybot.route.Router;
import io.github.mojolowjo.entropybot.route.SectionKey;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Everything the route map runs, wired together, in plain Java: the store, the build queue, the router, the worker
 * pool with its scheduler, the planning thread and the staleness rules. {@code RouteRuntime} owns one per world and
 * feeds it from the game; JUnit builds one from fakes.
 */
public final class RouteEngine {
    /** The boxes a block change can touch (R1's {@link RouteCore#boxesForBlockChange} by default). */
    public interface ChangeBoxes {
        List<SectionKey> boxes(int dim, int x, int y, int z);
    }

    /** The boxes on a straight stretch (R1's {@link RouteCore#boxesAlong} by default). */
    public interface AlongBoxes {
        List<SectionKey> boxes(int dim, Cell a, Cell b);
    }

    public final RouteStore store;
    public final BuildQueue queue;
    public final RouteCounters counters;
    public final RouteLog log;
    public final RoutePool pool;
    public final RouteScheduler scheduler;
    private final Router router;
    private final ChangeBoxes changeBoxes;
    private final AlongBoxes alongBoxes;
    private final ExecutorService planner;
    private final LongSupplier clock;
    final AtomicLong blockChanges = new AtomicLong(), walkChanges = new AtomicLong(), staleMarks = new AtomicLong(),
            alongRequests = new AtomicLong();

    public RouteEngine(RouteStore store, BuildQueue queue, Router router, RouteCounters counters, RouteLog log,
                       int workers, RouteScheduler.BoxWork work, ChangeBoxes changeBoxes, AlongBoxes alongBoxes,
                       LongSupplier clock) {
        this.store = store;
        this.queue = queue;
        this.router = router;
        this.counters = counters;
        this.log = log;
        this.changeBoxes = changeBoxes;
        this.alongBoxes = alongBoxes;
        this.clock = clock;
        this.pool = new RoutePool(workers, "entropybot-route", (where, t) -> counters.workerException(where, t, log));
        this.scheduler = new RouteScheduler(store, queue, counters, log, pool, work, clock);
        this.planner = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "entropybot-route-plan");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    /** The real parts: R1's store, queue, router and box builder, 3 workers. */
    public static RouteEngine create(RouteLog log) {
        RouteCounters c = new RouteCounters();
        RouteStore s = RouteCore.memoryStore();
        return new RouteEngine(s, RouteCore.queue(), RouteCore.router(s, c, log), c, log, 3,
                RouteScheduler.BoxWork.core(), RouteCore::boxesForBlockChange, RouteCore::boxesAlong,
                System::currentTimeMillis);
    }

    /** A plan that says why there is none (never null, never thrown). */
    public static RoutePlan refused(String reason) {
        return new RoutePlan(RoutePlan.Status.BAD_REQUEST, Double.POSITIVE_INFINITY, List.of(), CostToGo.empty(), 0, 0,
                reason);
    }

    /**
     * Plans on the planning thread. {@code notAvailable} non-null = answer at once with that reason. The future always
     * completes normally; an exception is counted, logged and comes back as a refused plan.
     */
    public CompletableFuture<RoutePlan> plan(RouteRequest req, String notAvailable) {
        if (notAvailable != null) return CompletableFuture.completedFuture(refused("route map not available: " + notAvailable));
        if (req == null || req.start() == null || req.goal() == null)
            return CompletableFuture.completedFuture(refused("bad request: no start or goal"));
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    RoutePlan p = router.plan(req);
                    return p == null ? refused("error: the router gave no answer") : p;
                } catch (Throwable t) {
                    counters.workerException("plan " + req.start() + " -> " + req.goal(), t, log);
                    return refused("error: " + t);
                }
            }, planner);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(refused("route map not available: stopping"));
        }
    }

    /**
     * Puts the boxes along a to b, plus their 4 sideways neighbours (a 3-box corridor), on the now queue when they are
     * inside the areas and not built yet (or stale). Returns how many were queued.
     */
    public int requestBuildAlong(int dim, Cell a, Cell b, AreaBoxes areas) {
        alongRequests.incrementAndGet();
        int n = 0;
        try {
            Set<SectionKey> keys = new LinkedHashSet<>();
            for (SectionKey k : alongBoxes.boxes(dim, a, b)) {
                keys.add(k);
                for (int face : new int[]{0, 1, 4, 5}) keys.add(k.neighbour(face));
            }
            for (SectionKey k : keys) {
                if (!areas.wanted(k)) continue;
                if (store.get(k) != null && !store.isStale(k)) continue;
                if (queue.offer(k, BuildQueue.Priority.NOW)) n++;
            }
        } catch (Throwable t) {
            counters.workerException("build along " + a + " -> " + b, t, log);
        }
        return n;
    }

    /**
     * A block changed (game thread, from the ClientLevel hook). Only a change of walkability ({@link RouteRules#walkChanged})
     * marks the built boxes it can touch stale and queues them. Returns the number marked.
     */
    public int blockChanged(int dim, int x, int y, int z, int oldClass, int newClass, boolean sameState) {
        blockChanges.incrementAndGet();
        if (!RouteRules.walkChanged(oldClass, newClass, sameState)) return 0;
        walkChanges.incrementAndGet();
        return markStaleAround(dim, x, y, z);
    }

    /** Marks the built boxes a change at (x, y, z) can touch stale (no walkability filter). Never throws. */
    public int markStaleAround(int dim, int x, int y, int z) {
        int n = 0;
        try {
            for (SectionKey k : changeBoxes.boxes(dim, x, y, z)) {
                if (store.get(k) == null) continue;
                if (!store.isStale(k)) {
                    store.markStale(k);
                    staleMarks.incrementAndGet();
                    n++;
                }
                queue.offer(k, BuildQueue.Priority.STALE);
            }
        } catch (Throwable t) {
            counters.workerException("block change at " + x + " " + y + " " + z, t, log);
        }
        return n;
    }

    public RouteStats stats() {
        return counters.snapshot(clock.getAsLong(), queue.nowSize(), queue.idleSize());
    }

    /** One line of the adapter's own counters. */
    public String line() {
        return scheduler.line() + "; block changes " + blockChanges.get() + " (walkability " + walkChanges.get()
                + "), stale marks " + staleMarks.get() + ", build-along requests " + alongRequests.get();
    }

    /** Stops the workers and the planning thread (waits up to {@code waitMs}); true when every thread ended. */
    public boolean stop(long waitMs) {
        long end = System.currentTimeMillis() + waitMs;
        boolean ok = pool.stop(waitMs);
        planner.shutdownNow();
        try {
            ok &= planner.awaitTermination(Math.max(0, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        }
        return ok;
    }
}
