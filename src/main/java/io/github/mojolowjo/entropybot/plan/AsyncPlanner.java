package io.github.mojolowjo.entropybot.plan;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 0.23.1: the planner's search off the client thread. The caller gathers the facts on the client thread (they read the
 * world), {@link #submit} runs the pure search on one daemon worker (the search keeps its own 200 ms / 2000-node bound),
 * and {@link #drain} - called from the client tick - hands each finished result to its callback on the client thread, so
 * the callbacks may touch the game and the stores. One search per key at a time ("already planning").
 *
 * <p>Loader notes: plain java.util.concurrent, nothing from the loader. Errors: a search that throws is delivered as
 * the throwable's text (never lost); a callback that throws is caught and counted.
 */
public final class AsyncPlanner {
    /** A finished search: the result, or the error text when the search threw. */
    public record Done<T>(T result, String error) {}

    private record Item<T>(String key, Done<T> done, Consumer<Done<T>> then) {}

    private final ExecutorService worker;
    private final ArrayDeque<Item<?>> finished = new ArrayDeque<>();
    private final Set<String> running = new HashSet<>();
    private int callbackErrors;

    public AsyncPlanner() {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "entropybot-planner");
            t.setDaemon(true);
            return t;
        }));
    }

    /** For tests: a given executor (a direct one runs the search inside submit). */
    public AsyncPlanner(ExecutorService worker) { this.worker = worker; }

    /** Starts a search; false when one with the same key is still running (nothing started). */
    public synchronized <T> boolean submit(String key, Supplier<T> search, Consumer<Done<T>> then) {
        if (running.contains(key)) return false;
        running.add(key);
        try {
            worker.execute(() -> {
                Done<T> d;
                try { d = new Done<>(search.get(), null); } catch (RuntimeException | Error e) { d = new Done<>(null, e.toString()); }
                synchronized (AsyncPlanner.this) { finished.add(new Item<>(key, d, then)); }
            });
        } catch (RuntimeException e) {
            running.remove(key);
            throw e;
        }
        return true;
    }

    public synchronized boolean busy(String key) { return running.contains(key); }

    /** On the client thread: runs the callbacks of the searches that finished; returns how many. Never throws. */
    public int drain() {
        int n = 0;
        while (true) {
            Item<?> it;
            synchronized (this) {
                it = finished.poll();
                if (it == null) return n;
                running.remove(it.key());
            }
            n++;
            run(it);
        }
    }

    private static <T> void runItem(Item<T> it) { it.then().accept(it.done()); }

    private void run(Item<?> it) {
        try { runItem(it); } catch (RuntimeException e) { callbackErrors++; }
    }

    public int callbackErrors() { return callbackErrors; }
}
