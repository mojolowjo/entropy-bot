package io.github.mojolowjo.entropybot.aa.core;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * job-tick2 (runs JAU / JAV: the first aa tick of a world spent 280 ms in the one-time tech tree and item worth build on the
 * client thread): a one-time build of the world's item data run off the client tick, handed back in one swap. The client
 * tick {@link #start}s a build for a key (the world and its recipe count) with a pure snapshot; the executor's thread runs
 * it; the client tick {@link #poll}s and gets the finished, immutable result exactly once, for the key it still has (a
 * result built for an older key is dropped). While it builds, {@link #buildingLine} says "item data: the tech tree is
 * being built (N ms so far)" once per build (rule 22: the order stands meanwhile, rule 15: said). A build that throws comes
 * back with its error (rule 7: said by the caller, never a dead thread). Pure (JUnit: OffThreadBuildTest, a fake executor).
 * Loader notes: java.util.concurrent only.
 */
public final class OffThreadBuild<T> {
    /** One finished build: its key, the value (null when it threw), the error, the build time. */
    public record Done<T>(Object key, T value, Throwable error, long nanos) {}

    private final Executor exec;
    private volatile Done<T> done;
    private Object building;
    private long startedMs;
    private boolean said;

    public OffThreadBuild(Executor exec) { this.exec = exec; }

    /** A daemon single-thread executor named {@code name} (the live builds). */
    public static Executor daemon(String name) {
        return java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts {@code work} for {@code key} unless a build for the same key runs; returns whether it started. */
    public synchronized boolean start(Object key, Supplier<T> work, long nowMs) {
        if (building != null && building.equals(key)) return false;
        building = key;
        startedMs = nowMs;
        said = false;
        exec.execute(() -> {
            long t0 = System.nanoTime();
            Done<T> d;
            try {
                d = new Done<>(key, work.get(), null, System.nanoTime() - t0);
            } catch (RuntimeException | Error e) {       // rule 7: any failure comes back with its error
                d = new Done<>(key, null, e, System.nanoTime() - t0);
            }
            done = d;
        });
        return true;
    }

    /** The finished build for {@code liveKey}, once (then null); a finished build of another key is dropped. */
    public synchronized Done<T> poll(Object liveKey) {
        Done<T> d = done;
        if (d == null) return null;
        done = null;
        if (d.key().equals(building)) building = null;
        return d.key().equals(liveKey) ? d : null;
    }

    /** job-tick3: true while a build for {@code key} runs (the caller then skips the snapshot copy a start would need). */
    public synchronized boolean building(Object key) { return building != null && building.equals(key); }

    /** True while a build started and has not been polled. */
    public synchronized boolean building() { return building != null; }

    /** The "being built" line the first time it is asked per build, else null. */
    public synchronized String buildingLine(long nowMs) {
        if (building == null || said) return null;
        said = true;
        return "item data: the tech tree is being built (" + (nowMs - startedMs) + " ms so far)";
    }
}
