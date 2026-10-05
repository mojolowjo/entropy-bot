package io.github.mojolowjo.entropybot.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * The route builders' threads (plan section 3): {@code n} daemon threads at {@link Thread#MIN_PRIORITY}, named
 * {@code <prefix>-1..n}. The game thread hands out work only while {@link #free()} is above 0, so nothing piles up in
 * here. A task's exception goes to {@code onError} (counted and logged by the caller), never swallowed.
 * {@link #stop} interrupts and joins.
 */
public final class RoutePool {
    private final List<Thread> threads = new ArrayList<>();
    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final AtomicInteger busy = new AtomicInteger();
    private final BiConsumer<String, Throwable> onError;
    private final int size;
    private volatile boolean stopping;

    public RoutePool(int n, String prefix, BiConsumer<String, Throwable> onError) {
        this.size = n;
        this.onError = onError;
        for (int i = 1; i <= n; i++) {
            Thread t = new Thread(this::loop, prefix + "-" + i);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            threads.add(t);
        }
        for (Thread t : threads) t.start();
    }

    public int size() {
        return size;
    }

    /** Threads with nothing handed to them (the game thread's budget check). */
    public int free() {
        return stopping ? 0 : size - busy.get();
    }

    public int busy() {
        return busy.get();
    }

    public boolean stopping() {
        return stopping;
    }

    /** Hands one task over; false when stopping or every thread already has one. */
    public boolean submit(Runnable task) {
        if (stopping) return false;
        while (true) {
            int b = busy.get();
            if (b >= size) return false;
            if (busy.compareAndSet(b, b + 1)) break;
        }
        tasks.add(task);
        return true;
    }

    private void loop() {
        while (!stopping) {
            Runnable r;
            try {
                r = tasks.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (stopping) return;
                continue;
            }
            if (r == null) continue;
            try {
                r.run();
            } catch (Throwable t) {
                try {
                    onError.accept(Thread.currentThread().getName(), t);
                } catch (Throwable ignored) {
                    // the error sink itself broke; the counters in the caller are the only other place to say it
                }
            } finally {
                busy.decrementAndGet();
                Thread.interrupted(); // a stop's interrupt must not leak into the next task
            }
        }
    }

    /**
     * Stops: no new tasks, waiting tasks dropped, threads interrupted and joined for up to {@code waitMs} in all.
     * Returns true when every thread ended.
     */
    public boolean stop(long waitMs) {
        stopping = true;
        tasks.clear();
        for (Thread t : threads) t.interrupt();
        long end = System.currentTimeMillis() + Math.max(0, waitMs);
        boolean all = true;
        for (Thread t : threads) {
            long left = end - System.currentTimeMillis();
            try {
                if (left > 0) t.join(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            all &= !t.isAlive();
        }
        return all;
    }

    /** For tests and {@code route status}: the threads (daemon, MIN_PRIORITY). */
    List<Thread> threads() {
        return threads;
    }
}
