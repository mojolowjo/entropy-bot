package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.Playbook;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 0.39.z78 D6 (design "the worker protocol"): the planner off the render thread. {@link #submit} queues one request per
 * trip key and returns at once - a newer request for the same key replaces a queued one (queue depth 1 per key by design,
 * structural); one daemon thread resolves one request at a time (a snapshot of the facts, PlannerSnapshot: pure classes
 * only, no game type, NeedChain on its own node cache); {@link #poll} hands its result to the client tick, which applies
 * it only when the facts it read still hold (PlannerPort). The worker never touches Baritone, the level or the player:
 * the path asks are posted by the client tick (the snapshot's copy and the result's check ask the live provider).
 * {@link #landed} counts the results posted (the client tick re-prices on it as on a landed path). A request that throws
 * comes back with its error (rule 7: said and resolved on the client tick by the port). No Minecraft types (JUnit:
 * D6WorkerTest). Loader notes: java.lang.Thread only.
 */
public final class PlannerWorker {
    /** One request: the trip {@code key}, its version (the port's counter), the playbook, the kept fuels, the facts. */
    public record Job(String key, long version, Playbook pb, Set<String> keptFuel, PlannerSnapshot snap) {}

    /** One result: its request, the legs, the record of what it read, the resolve time on the worker; {@code error}: it threw. */
    public record Done(Job job, NeedChain.Legs legs, NeedChain.Read read, long resolveNs, RuntimeException error) {}

    private final Object lock = new Object();
    private final LinkedHashMap<String, Job> queued = new LinkedHashMap<>();
    private final Map<String, Done> done = new HashMap<>();
    private final Function<Job, Done> resolver;
    private String running;
    private Thread thread;
    private volatile long landed;

    /** The live worker: NeedChain on the snapshot (its frozen World, its extras, the live bag order), effects kept for the apply. */
    public PlannerWorker() { this(PlannerWorker::resolve); }

    /** Tests: {@code resolver} in place of NeedChain. */
    public PlannerWorker(Function<Job, Done> resolver) { this.resolver = resolver; }

    static Done resolve(Job j) {
        long t0 = System.nanoTime();
        try {
            NeedChain.Legs l = NeedChain.offThread(j.snap().extras(), j.snap().bag(),
                    () -> NeedChain.keepingFuel(j.keptFuel(), () -> NeedChain.resolvePlaybookLegs(j.pb(), j.snap().world())));
            return new Done(j, l, NeedChain.lastRead(), System.nanoTime() - t0, null);
        } catch (RuntimeException e) {
            return new Done(j, null, NeedChain.Read.NONE, System.nanoTime() - t0, e);
        }
    }

    /** Queues {@code j} (replacing a queued request of its key) and returns at once. */
    public void submit(Job j) {
        synchronized (lock) {
            queued.put(j.key(), j);
            if (thread == null || !thread.isAlive()) {
                thread = new Thread(this::loop, "entropybot-planner");
                thread.setDaemon(true);
                thread.start();
            }
            lock.notifyAll();
        }
    }

    /** The result of {@code key} posted since the last poll (the newest), null: none. */
    public Done poll(String key) {
        synchronized (lock) { return done.remove(key); }
    }

    /** A request of {@code key} is queued or running. */
    public boolean busy(String key) {
        synchronized (lock) { return queued.containsKey(key) || key.equals(running); }
    }

    /** Results posted since the start. */
    public long landed() { return landed; }

    /** Requests queued or running now (the probe line). */
    public int inFlight() {
        synchronized (lock) { return queued.size() + (running == null ? 0 : 1); }
    }

    private void loop() {
        while (true) {
            Job j;
            synchronized (lock) {
                while (queued.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;                                        // the worker stops with its thread (JVM exit)
                    }
                }
                var it = queued.entrySet().iterator();
                j = it.next().getValue();
                it.remove();
                running = j.key();
            }
            Done d;
            try {
                d = resolver.apply(j);
            } catch (RuntimeException e) {
                d = new Done(j, null, NeedChain.Read.NONE, 0, e);
            }
            synchronized (lock) {
                done.put(j.key(), d);
                running = null;
                landed++;
            }
        }
    }
}
