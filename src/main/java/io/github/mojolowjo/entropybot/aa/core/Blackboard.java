package io.github.mojolowjo.entropybot.aa.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AA v0 (AA_PLAN 2, 4): what every node reads: the typed inputs (filled once per tick), the request board, the job
 * port, the clock and the tracer, plus a few remembered facts (per task key). Pure.
 */
public final class Blackboard {
    public final Inputs in = new Inputs();
    public final Clock clock;
    public final JobPort port;
    public final Tracer tracer;
    private final Deque<Request> requests = new ArrayDeque<>();
    private final Map<String, Object> facts = new HashMap<>();
    private String pendingReason;
    /** The task that runs now (the RunJob leaf and its spec), null when the slot is empty. */
    private RunJob task;
    private long taskId = -1;

    public Blackboard(Clock clock, JobPort port, Tracer tracer) {
        this.clock = clock;
        this.port = port;
        this.tracer = tracer;
    }

    // ---- the request board ----

    public void post(Request r) { requests.addLast(r); }

    /** The oldest unexpired request of this source, left on the board (null: none). */
    public Request peek(String source) {
        long now = clock.now();
        requests.removeIf(r -> r.expired(now));
        for (Request r : requests) if (r.source().equals(source)) return r;
        return null;
    }

    /** Takes the oldest request of this source off the board (null: none). */
    public Request take(String source) {
        Request r = peek(source);
        if (r != null) requests.remove(r);
        return r;
    }

    /** Drops every request of this source (the AI's own stop); how many. */
    public int drop(String source) {
        int n = 0;
        for (var it = requests.iterator(); it.hasNext(); ) if (it.next().source().equals(source)) { it.remove(); n++; }
        return n;
    }

    public List<Request> requests() { return new ArrayList<>(requests); }

    // ---- remembered facts (shelter built, cooldown notes ...) ----

    public void fact(String key, Object v) {
        if (v == null) facts.remove(key);
        else facts.put(key, v);
    }

    public Object fact(String key) { return facts.get(key); }

    public boolean flag(String key) { return Boolean.TRUE.equals(facts.get(key)); }

    // ---- the running task ----

    /** The reason the next start will log (a chooser sets it just before ticking its RunJob). */
    public void pendingReason(String r) { pendingReason = r; }

    String pendingReason() {
        String r = pendingReason;
        pendingReason = null;
        return r == null ? "chosen" : r;
    }

    void taskStarted(RunJob leaf, JobPort.TaskSpec spec, long id) {
        task = leaf;
        taskId = id;
    }

    void taskEnded(RunJob leaf, long id) {
        if (task == leaf) {
            task = null;
            taskId = -1;
        }
    }

    public RunJob task() { return task; }

    public long taskId() { return taskId; }

    /** "slot>needs>NeedTorches>RunJob(goal torch 16) RUNNING 34s by needs" or "idle". */
    public String taskText(long now) {
        if (task == null) return facts.get("planning") != null ? "planning " + facts.get("planning") : "no task";
        return task.id + "(" + task.spec().chain() + ") " + (task.paused() ? "PAUSED" : "RUNNING") + " " + task.ageS(now) + "s by " + task.source();
    }

    /** The task block of the snapshot (AA_PLAN 6), or null. */
    public String taskJson(long now) {
        if (task == null) return null;
        return "{\"id\":" + taskId + ",\"kind\":" + Tracer.q(task.kind.name()) + ",\"chain\":" + Tracer.q(task.spec().chain())
                + ",\"age_s\":" + task.ageS(now) + ",\"progress\":" + task.progressValue() + ",\"paused\":" + task.paused() + "}";
    }
}
