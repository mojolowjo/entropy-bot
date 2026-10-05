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
            alongRequests = new AtomicLong(), skippedPlans = new AtomicLong(), alongCapped = new AtomicLong();

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
        CompletableFuture<RoutePlan> f = new CompletableFuture<>();
        try {
            planner.execute(() -> {
                // review note: a walk that gave up waiting cancelled the future; don't plan for nobody
                if (f.isDone()) {
                    skippedPlans.incrementAndGet();
                    return;
                }
                RoutePlan p;
                try {
                    p = router.plan(req);
                    if (p == null) p = refused("error: the router gave no answer");
                } catch (Throwable t) {
                    counters.workerException("plan " + req.start() + " -> " + req.goal(), t, log);
                    p = refused("error: " + t);
                }
                f.complete(p);
            });
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(refused("route map not available: stopping"));
        }
        return f;
    }

    /** Plans dropped unplanned because their walk had already cancelled them. */
    public long skippedPlans() {
        return skippedPlans.get();
    }

    /**
     * The longest straight stretch one build-along request covers (review S5): a farther b is cut to the point this far
     * from a, counted in {@link #line()}. {@code route build} refuses longer stretches with a message before this.
     */
    public static final int MAX_ALONG_BLOCKS = 2000;

    /** b, or the point {@link #MAX_ALONG_BLOCKS} from a toward b when b is farther (straight-line, 3D). */
    Cell capped(Cell a, Cell b) {
        Cell c = capAlong(a, b, MAX_ALONG_BLOCKS);
        if (c != b) alongCapped.incrementAndGet();
        return c;
    }

    /** Pure: b itself when within {@code max} blocks of a, else the cell {@code max} blocks from a toward b. */
    public static Cell capAlong(Cell a, Cell b, int max) {
        double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d <= max) return b;
        double f = max / d;
        return new Cell(a.x() + (int) Math.round(dx * f), a.y() + (int) Math.round(dy * f), a.z() + (int) Math.round(dz * f));
    }

    /**
     * {@code RoutePlanner.requestBuildAlong} (a walk failed where the map said possible): the built boxes on the straight
     * stretch a to b are marked stale, and every box of it inside the areas goes on the now queue; with a == b, the box
     * of a and its 6 face neighbours. Returns how many were queued.
     */
    public int rebuildAlong(int dim, Cell a, Cell b, AreaBoxes areas) {
        alongRequests.incrementAndGet();
        int n = 0;
        try {
            Set<SectionKey> keys = new LinkedHashSet<>();
            if (a.equals(b)) {
                SectionKey k = SectionKey.of(dim, a.x(), a.y(), a.z());
                keys.add(k);
                for (int face = 0; face < 6; face++) keys.add(k.neighbour(face));
            } else {
                keys.addAll(alongBoxes.boxes(dim, a, capped(a, b)));
            }
            for (SectionKey k : keys) {
                if (!areas.wanted(k)) continue;
                if (store.get(k) != null) {
                    boolean was = store.isStale(k);
                    store.markStale(k);         // always: a build in flight notes it (review S3)
                    if (!was) staleMarks.incrementAndGet();
                }
                if (queue.offer(k, BuildQueue.Priority.NOW)) n++;
            }
        } catch (Throwable t) {
            counters.workerException("rebuild along " + a + " -> " + b, t, log);
        }
        return n;
    }

    /**
     * After a plan found its start or goal box missing: the boxes along a to b, plus their 4 sideways neighbours (a
     * 3-box corridor), go on the now queue when they are inside the areas and not built yet (or stale). Nothing is
     * marked stale. Returns how many were queued.
     */
    public int queueMissingAlong(int dim, Cell a, Cell b, AreaBoxes areas) {
        alongRequests.incrementAndGet();
        int n = 0;
        try {
            Set<SectionKey> keys = new LinkedHashSet<>();
            for (SectionKey k : alongBoxes.boxes(dim, a, capped(a, b))) {
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
                if (store.get(k) == null) {
                    // review S3: its first build is running: that build's result is stale, queue it again
                    if (store.noteChangeWhileBuilding(k)) {
                        staleMarks.incrementAndGet();
                        queue.offer(k, BuildQueue.Priority.STALE);
                    }
                    continue;
                }
                boolean was = store.isStale(k);
                store.markStale(k);     // always: a rebuild of a stale box in flight notes the change too (review S3)
                if (!was) {
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
                + "), stale marks " + staleMarks.get() + ", build-along requests " + alongRequests.get()
                + (alongCapped.get() > 0 ? " (" + alongCapped.get() + " cut at " + MAX_ALONG_BLOCKS + " blocks)" : "")
                + ", cancelled plans skipped " + skippedPlans.get();
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
