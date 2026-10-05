package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.BoxBuilder;
import io.github.mojolowjo.entropybot.route.BuildInput;
import io.github.mojolowjo.entropybot.route.BuildQueue;
import io.github.mojolowjo.entropybot.route.CellMoves;
import io.github.mojolowjo.entropybot.route.RouteCore;
import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RouteStore;
import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The game thread's side of building (plan section 3, review R4/R5): once a tick it hands at most
 * {@link #MAX_BATCHES_PER_TICK} batches of boxes to free workers, each batch with one fresh {@code CellMoves} (one
 * Baritone {@code CalculationContext}, built on the game thread, at most {@link #CONTEXTS_PER_SECOND} a second). The
 * now queue goes first and always runs; idle work (the idle queue and the chunk-load re-hashes) only while the bot is
 * idle, and a worker drops the rest of an idle batch back into the queue the moment a job starts. Nothing is handed out
 * while {@link BuildEnv#pauseReason} says why not (breaking on, not in a world, files loading...).
 *
 * <p>Plain Java: the Minecraft side is behind {@link BuildEnv}, the box building behind {@link BoxWork} (R1's
 * {@link BoxBuilder} and {@link RouteCore} by default), so JUnit runs it with fakes.
 */
public final class RouteScheduler {
    public static final int MAX_BATCHES_PER_TICK = 2;
    public static final int CONTEXTS_PER_SECOND = 3;
    public static final int NOW_BATCH = 8;
    public static final int IDLE_BATCH = 4;
    /** Keys looked at per batch (skipped ones included), so one tick never walks a long queue of unknown terrain. */
    public static final int MAX_POLLS = 64;
    /** Chunk columns turned into re-hash work per tick. */
    public static final int COLUMNS_PER_TICK = 8;

    /** What the scheduler needs from the game (game thread only). */
    public interface BuildEnv {
        /** Null when building may run now, else why not ("breaking is on", "not in a world"...). */
        String pauseReason();

        /** No job, chain, request or reflex: the idle queue may run. */
        boolean idle();

        /** The game is slow this tick (skip handing out, nothing else). */
        boolean lagging();

        /** The owner's areas now. */
        AreaBoxes areas();

        /** Where the box's terrain would come from now, or null when nothing knows it (not built). */
        SectionRecord.Quality terrain(SectionKey k);

        /** A fresh CellMoves for one worker, or null when it can't be made now. */
        WorkerMoves newMoves();
    }

    /**
     * One worker's moves. {@code walkingOnly} false = the context was made with breaking or placing on (review R5): the
     * boxes are built but never stored ({@link BuildInput#allowBreak}).
     */
    public record WorkerMoves(CellMoves moves, boolean walkingOnly) {
    }

    /** The box work a worker does; R1's code by default. */
    public interface BoxWork {
        boolean build(BuildInput in, RouteStore store, RouteCounters counters, RouteLog log);

        long walkHash(SectionKey key, CellMoves moves);

        boolean rehash(RouteStore store, SectionKey key, long walkHash);

        static BoxWork core() {
            return new BoxWork() {
                @Override
                public boolean build(BuildInput in, RouteStore store, RouteCounters counters, RouteLog log) {
                    return BoxBuilder.buildInto(in, store, counters, log);
                }

                @Override
                public long walkHash(SectionKey key, CellMoves moves) {
                    return RouteCore.walkHash(key, moves);
                }

                @Override
                public boolean rehash(RouteStore store, SectionKey key, long walkHash) {
                    return RouteCore.rehash(store, key, walkHash);
                }
            };
        }
    }

    enum Kind { BUILD, REHASH }

    record Item(SectionKey key, SectionRecord.Quality quality, boolean now, Kind kind) {
    }

    record Column(int dim, int cx, int cz) {
    }

    private final RouteStore store;
    private final BuildQueue queue;
    private final RouteCounters counters;
    private final RouteLog log;
    private final RoutePool pool;
    private final BoxWork work;
    private final LongSupplier clock;
    private final RateGate gate = new RateGate(CONTEXTS_PER_SECOND, 1000);
    private final LinkedHashSet<Column> pendingColumns = new LinkedHashSet<>();
    private final ArrayDeque<Item> rehashDue = new ArrayDeque<>();

    private volatile boolean idleFlag;
    private volatile String pause = "not started";

    final AtomicLong contexts = new AtomicLong(), contextFailures = new AtomicLong(), batches = new AtomicLong(),
            notWanted = new AtomicLong(), unknownTerrain = new AtomicLong(), alreadyGood = new AtomicLong(),
            idleBreaks = new AtomicLong(), requeued = new AtomicLong(), rehashes = new AtomicLong(),
            lagSkips = new AtomicLong(), columns = new AtomicLong();

    public RouteScheduler(RouteStore store, BuildQueue queue, RouteCounters counters, RouteLog log, RoutePool pool,
                          BoxWork work, LongSupplier clock) {
        this.store = store;
        this.queue = queue;
        this.counters = counters;
        this.log = log;
        this.pool = pool;
        this.work = work;
        this.clock = clock;
    }

    /** Null while building runs, else why it is paused. */
    public String pauseReason() {
        return pause;
    }

    public boolean idleFlag() {
        return idleFlag;
    }

    /**
     * Takes one context from the shared budget ({@link #CONTEXTS_PER_SECOND}) for a use outside the builder (a plan's
     * start and goal boxes). Game thread only.
     */
    public boolean takeContext(long nowMs) {
        if (!gate.take(nowMs)) return false;
        contexts.incrementAndGet();
        return true;
    }

    /** Once a game tick. Never throws (an exception is counted and logged). */
    public void tick(BuildEnv env) {
        try {
            tick0(env);
        } catch (Throwable t) {
            counters.workerException("route tick", t, log);
        }
    }

    private void tick0(BuildEnv env) {
        String why = env.pauseReason();
        pause = why;
        if (why != null) {
            idleFlag = false;
            return;
        }
        boolean idle = env.idle();
        idleFlag = idle;
        if (env.lagging()) {
            lagSkips.incrementAndGet();
            return;
        }
        AreaBoxes areas = env.areas();
        if (idle) drainColumns(env, areas);
        long now = clock.getAsLong();
        for (int h = 0; h < MAX_BATCHES_PER_TICK; h++) {
            if (pool.free() <= 0 || !gate.available(now)) break;
            List<Item> items = collect(env, areas, idle);
            if (items.isEmpty()) break;
            WorkerMoves wm;
            try {
                wm = env.newMoves();
            } catch (Throwable t) {
                counters.workerException("route context", t, log);
                wm = null;
            }
            if (wm == null) {
                contextFailures.incrementAndGet();
                requeue(items, 0);
                break;
            }
            gate.take(now);
            contexts.incrementAndGet();
            final WorkerMoves moves = wm;
            if (!pool.submit(() -> runBatch(items, moves))) {
                requeue(items, 0);
                break;
            }
            batches.incrementAndGet();
        }
    }

    private List<Item> collect(BuildEnv env, AreaBoxes areas, boolean idle) {
        List<Item> out = new ArrayList<>();
        if (queue.nowSize() > 0) {
            for (int polls = 0; polls < MAX_POLLS && out.size() < NOW_BATCH; polls++) {
                SectionKey k = queue.poll(false);
                if (k == null) break;
                consider(env, areas, k, true, out);
            }
            if (!out.isEmpty() || !idle) return out;
        }
        if (!idle) return out;
        while (!rehashDue.isEmpty() && out.size() < IDLE_BATCH) out.add(rehashDue.poll());
        for (int polls = 0; polls < MAX_POLLS && out.size() < IDLE_BATCH; polls++) {
            SectionKey k = queue.poll(true);
            if (k == null) break;
            consider(env, areas, k, false, out);
        }
        return out;
    }

    private void consider(BuildEnv env, AreaBoxes areas, SectionKey k, boolean now, List<Item> out) {
        if (!areas.wanted(k)) {
            notWanted.incrementAndGet();
            return;
        }
        SectionRecord.Quality q = env.terrain(k);
        if (q == null) {
            unknownTerrain.incrementAndGet();
            return;
        }
        SectionRecord old = store.get(k);
        if (old != null && !store.isStale(k) && !(old.quality() == SectionRecord.Quality.COARSE && q == SectionRecord.Quality.LIVE)) {
            alreadyGood.incrementAndGet();
            return;
        }
        out.add(new Item(k, q, now, Kind.BUILD));
    }

    /** On a worker. */
    void runBatch(List<Item> items, WorkerMoves wm) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (pool.stopping()) {
                requeue(items, i);
                return;
            }
            if (!it.now() && !idleFlag) {
                idleBreaks.incrementAndGet();
                requeue(items, i);
                return;
            }
            try {
                if (it.kind() == Kind.REHASH) {
                    long h = work.walkHash(it.key(), wm.moves());
                    rehashes.incrementAndGet();
                    if (work.rehash(store, it.key(), h)) queue.offer(it.key(), BuildQueue.Priority.STALE);
                } else {
                    work.build(new BuildInput(it.key(), wm.moves(), it.quality(), !wm.walkingOnly(), System.currentTimeMillis()),
                            store, counters, log);
                }
            } catch (Throwable t) {
                counters.workerException((it.kind() == Kind.REHASH ? "rehash " : "build ") + it.key(), t, log);
            }
        }
    }

    /** Puts items[from..] back: now keys on the now queue, idle keys as stale or rest, re-hashes as columns. */
    private void requeue(List<Item> items, int from) {
        for (int i = from; i < items.size(); i++) {
            Item it = items.get(i);
            requeued.incrementAndGet();
            if (it.kind() == Kind.REHASH) {
                synchronized (pendingColumns) {
                    pendingColumns.add(new Column(it.key().dim(), it.key().sx(), it.key().sz()));
                }
            } else if (it.now()) {
                queue.offer(it.key(), BuildQueue.Priority.NOW);
            } else {
                queue.offer(it.key(), store.isStale(it.key()) ? BuildQueue.Priority.STALE : BuildQueue.Priority.REST);
            }
        }
    }

    /** A chunk column loaded (game thread): its boxes are looked at in idle time. */
    public void chunkLoaded(int dim, int cx, int cz) {
        synchronized (pendingColumns) {
            pendingColumns.add(new Column(dim, cx, cz));
        }
    }

    public int pendingColumns() {
        synchronized (pendingColumns) {
            return pendingColumns.size();
        }
    }

    /**
     * Plan section 3, chunk (re)load: a missing box goes on the idle queue, a coarse or stale one is queued for a live
     * rebuild, a live one gets its walk-grid hash checked by a worker (a different hash = stale, rebuilt).
     */
    private void drainColumns(BuildEnv env, AreaBoxes areas) {
        if (rehashDue.size() >= IDLE_BATCH * 4) return;
        List<Column> take = new ArrayList<>();
        synchronized (pendingColumns) {
            Iterator<Column> it = pendingColumns.iterator();
            while (it.hasNext() && take.size() < COLUMNS_PER_TICK) {
                take.add(it.next());
                it.remove();
            }
        }
        for (Column c : take) {
            columns.incrementAndGet();
            for (SectionKey k : areas.column(c.dim(), c.cx(), c.cz())) {
                SectionRecord rec = store.get(k);
                if (rec == null) {
                    queue.offer(k, BuildQueue.Priority.REST);
                } else if (rec.quality() == SectionRecord.Quality.COARSE || store.isStale(k)) {
                    queue.offer(k, BuildQueue.Priority.STALE);
                } else if (env.terrain(k) == SectionRecord.Quality.LIVE) {
                    rehashDue.add(new Item(k, SectionRecord.Quality.LIVE, false, Kind.REHASH));
                }
            }
        }
    }

    /**
     * Fills the idle queue afresh (start, areas or settings changed): boxes near the marked places first, then the
     * stale ones, then every box of the areas the store doesn't hold.
     *
     * @param places marked places of {@code dim} as {x, y, z}.
     */
    public int refillIdle(AreaBoxes areas, int dim, List<int[]> places) {
        queue.clearIdle();
        int n = 0;
        for (int[] p : places)
            for (SectionKey k : areas.near(dim, p[0], p[1], p[2], 2, 1))
                if (store.get(k) == null || store.isStale(k)) n += queue.offer(k, BuildQueue.Priority.PLACES) ? 1 : 0;
        for (SectionKey k : store.staleKeys())
            if (k.dim() == dim && areas.wanted(k)) n += queue.offer(k, BuildQueue.Priority.STALE) ? 1 : 0;
        for (SectionKey k : areas.all(dim))
            if (store.get(k) == null) n += queue.offer(k, BuildQueue.Priority.REST) ? 1 : 0;
        return n;
    }

    /** The adapter's own counters, one line (for {@code route status}). */
    public String line() {
        String p = pause;
        return String.format(Locale.ROOT,
                "builder %s%s, workers %d/%d busy, contexts %d (failed %d), batches %d, skipped: outside areas %d,"
                        + " no terrain %d, already built %d; idle breaks %d, requeued %d, chunk columns %d (pending %d),"
                        + " rehashes %d, lag skips %d",
                p == null ? "running" : "paused (" + p + ")", idleFlag ? ", idle work on" : "",
                pool.busy(), pool.size(), contexts.get(), contextFailures.get(), batches.get(), notWanted.get(),
                unknownTerrain.get(), alreadyGood.get(), idleBreaks.get(), requeued.get(), columns.get(),
                pendingColumns(), rehashes.get(), lagSkips.get());
    }

    /** The counters as a map (tests, JSON status). */
    public Map<String, Long> numbers() {
        return Map.ofEntries(Map.entry("contexts", contexts.get()), Map.entry("contextFailures", contextFailures.get()),
                Map.entry("batches", batches.get()), Map.entry("notWanted", notWanted.get()),
                Map.entry("unknownTerrain", unknownTerrain.get()), Map.entry("alreadyGood", alreadyGood.get()),
                Map.entry("idleBreaks", idleBreaks.get()), Map.entry("requeued", requeued.get()),
                Map.entry("rehashes", rehashes.get()), Map.entry("lagSkips", lagSkips.get()),
                Map.entry("columns", columns.get()));
    }
}
