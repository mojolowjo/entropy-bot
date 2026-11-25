package io.github.mojolowjo.entropybot.aa.core;

import java.util.function.Function;

/**
 * AA v0 (AA_PLAN 3): the leaf that owns one task. States and lines as the plan's table: start ok -> RUNNING; refused ->
 * FAILURE(refusal) (the Cooldown above keeps it from retrying every tick); chain ended "done..." -> SUCCESS; ended
 * otherwise -> FAILURE(end text); timeout -> cancel, FAILURE("timeout"); no progress -> cancel, FAILURE("no progress")
 * (0.39.z70: an order's typed run re-plans instead, and a typed run that has ended is read at the next poll with its own
 * reason);
 * halted (owner preempt, aa off) -> cancelled, no failure counted; paused by danger -> RUNNING, the clocks stop; GONE
 * (someone else removed it) -> FAILURE("removed by ...") and a {@code lost} count (a poster we forgot to gate).
 */
public final class RunJob extends Node {
    public final TaskKind kind;
    private final Function<Blackboard, JobPort.TaskSpec> spec;
    private long taskId = -1, startedAt, lastProgressAt, lastProgress, pausedAt = -1;
    private JobPort.TaskSpec running;
    private String source;

    public RunJob(String id, TaskKind kind, Function<Blackboard, JobPort.TaskSpec> spec) {
        super(id, "RunJob " + kind);
        this.kind = kind;
        this.spec = spec;
    }

    /** rm-execution2: fixed typed steps. */
    public static RunJob of(String id, TaskKind kind, java.util.List<io.github.mojolowjo.entropybot.aa.moves.Step> steps, String source) {
        return new RunJob(id, kind, bb -> JobPort.TaskSpec.steps(kind, steps, source));
    }

    /**
     * FK fix (run FK 19:20:08: "timeout EXPLORE after 1200s" cancelled the boots' healthy caving step - the run had started at
     * 19:00 for the pickaxe and was carried on by every met order since, z77 answers (1)): an order was met and its batch's
     * run carries on for the next order - the run is that order's now, so its clocks start again (the timeout and the
     * no-progress watch are per order's run, as before the carry). Said.
     */
    public void carriedOn(Blackboard bb, long now) {
        if (taskId < 0 || running == null) return;
        bb.port.log("carried on " + running.kind() + " after " + (now - startedAt) / 1000 + "s: the run is the next order's now, its clocks start again");   // structural: milliseconds per second
        startedAt = lastProgressAt = now;
        lastProgress = bb.port.progress(taskId);
    }

    @Override protected Status run(Blackboard bb) {
        long now = bb.clock.now();
        if (taskId < 0) {
            JobPort.TaskSpec t = spec.apply(bb);
            if (t == null) return fail("nothing to run");
            // AREAS (0.39.2): no return leg any more (WayBack is gone); the way back to the base is a soft cost (AreaFacts.awayS)
            long id = bb.port.start(t);
            if (id < 0) {
                String r = bb.port.lastRefusal();
                bb.port.log("refused " + t.kind() + " \"" + t.chain() + "\": " + r);
                bb.tracer.decision(now, t.source(), t.kind(), t.chain(), "refused: " + r);
                // aa-switch2 (r2-iron: 17 of 19 pointless): a refused start never ran; its end line keeps the next start from
                // counting as a switch that replaced it live
                bb.tracer.outcome(now, "refused: " + r);
                // S0 (MINING_PLAN 10.2): a scorer that names a command which does not exist is a build bug, never silent
                if (r != null && r.toLowerCase(java.util.Locale.ROOT).contains("unknown")) bb.port.incident("aa unknown command " + t.kind() + " \"" + t.chain() + "\": " + r);
                return fail(r == null ? "refused" : r);
            }
            taskId = id;
            running = t;
            failedStep = null;
            source = t.source();
            startedAt = lastProgressAt = now;
            lastProgress = bb.port.progress(id);
            pausedAt = -1;
            bb.port.log("start " + t.kind() + " \"" + t.chain() + "\" by " + t.source() + " #" + id);
            bb.tracer.decision(now, t.source(), t.kind(), t.chain(), bb.pendingReason());
            bb.taskStarted(this, t, id);
            return Status.RUNNING;
        }
        JobPort.JobState st = bb.port.poll(taskId);
        switch (st.state()) {
            case DONE -> {
                bb.port.log("done " + running.kind() + " in " + (now - startedAt) / 1000 + "s" + (st.text() == null ? "" : ": " + st.text()));  // structural: milliseconds per second
                bb.tracer.outcome(now, "done");
                return end(bb, Status.SUCCESS, null);
            }
            case FAILED -> {
                bb.port.log("failed " + running.kind() + ": " + st.text());
                bb.tracer.outcome(now, "failed: " + st.text());
                bb.tracer.counts.fails++;
                failedStep = st.step();
                return end(bb, Status.FAILURE, st.text());
            }
            case GONE -> {
                bb.port.log("lost " + running.kind() + ": removed by " + st.text());
                bb.tracer.outcome(now, "lost: " + st.text());
                bb.tracer.counts.lost++;
                return end(bb, Status.FAILURE, "removed by " + st.text());
            }
            default -> { }
        }
        if (pausedAt >= 0) return Status.RUNNING;        // the clocks stand still while danger holds the bot
        long p = bb.port.progress(taskId);
        if (p != lastProgress) {
            lastProgress = p;
            lastProgressAt = now;
        }
        if (now - startedAt > running.timeoutMs()) {
            String why = "timeout after " + (now - startedAt) / 1000 + "s";  // structural: milliseconds per second
            bb.port.cancel(taskId, why);
            bb.port.log("timeout " + running.kind() + " after " + (now - startedAt) / 1000 + "s");  // structural: milliseconds per second
            bb.port.incident("aa timeout " + running.kind() + " \"" + running.chain() + "\"");
            bb.tracer.outcome(now, "timeout");
            bb.tracer.counts.fails++;
            return end(bb, Status.FAILURE, "timeout");
        }
        if (now - lastProgressAt > running.noProgressMs()) {
            long s = (now - lastProgressAt) / 1000;  // structural: milliseconds per second
            // 0.39.z71 (D2 3.2): with monitor.stallS below this watch it fires only when the step monitor is off or broken: said
            bb.port.log("outer watch fired after " + s + " s in " + running.kind() + ": the step monitor said nothing");
            // 0.39.z46: an order's run re-plans on it (the step's target as the source fact) instead of parking
            JobPort.NoProgress np = bb.port.noProgress(taskId, io.github.mojolowjo.entropybot.aa.moves.FactChange.STALL_NO_PROGRESS + " " + s + " s");   // FK fix review: the stall word
            if (np == JobPort.NoProgress.REPLANNED) {
                bb.port.log("no progress " + s + "s in " + running.kind() + ": re-planned on the step's source fact");
                lastProgressAt = now;
                lastProgress = bb.port.progress(taskId);
                return Status.RUNNING;
            }
            // 0.39.z70 (D2 3.1, run O P5): the typed run already ended (or ended in the watch's re-plan): no cancel, no
            // "no progress" end - the next poll reads the run's own end and its reason
            if (np == JobPort.NoProgress.MONITORED) {       // 0.39.z78 run EJ (rule 23 (2)): the step monitor is the one reader
                bb.port.log("watch " + s + "s in " + running.kind() + ": left to the step monitor, which judges the running step (monitor.stallS)");
                lastProgressAt = now;
                lastProgress = bb.port.progress(taskId);
                return Status.RUNNING;
            }
            if (np == JobPort.NoProgress.ENDED) {
                bb.port.log("watch " + s + "s in " + running.kind() + ": the typed run has ended; its own end is read next");
                lastProgressAt = now;
                return Status.RUNNING;
            }
            bb.port.cancel(taskId, io.github.mojolowjo.entropybot.aa.moves.FactChange.STALL_NO_PROGRESS + " " + s + "s");   // FK fix review: the stall word
            bb.port.log("stuck " + running.kind() + ": no progress " + s + "s");
            bb.port.incident("aa stuck " + running.kind() + " \"" + running.chain() + "\": no progress " + s + "s");
            bb.tracer.outcome(now, "stuck");
            bb.tracer.counts.fails++;
            return end(bb, Status.FAILURE, "no progress");
        }
        return Status.RUNNING;
    }

    private Status end(Blackboard bb, Status s, String why) {
        bb.taskEnded(this, taskId);
        taskId = -1;
        running = null;
        pausedAt = -1;
        return s == Status.FAILURE ? fail(why) : s;
    }

    @Override public void halt(String why) {
        if (taskId < 0) return;
        bb().port.cancel(taskId, why);
        bb().port.log("cancelled " + running.kind() + ": " + why);
        bb().tracer.outcome(bb().clock.now(), "cancelled: " + why);
        end(bb(), Status.SUCCESS, null);
    }

    /**
     * Backbone step 2 (step3-watch: a failed burrow, then "wait 120" in the open): the running task is cancelled and the
     * leaf FAILS with {@code why} (counted as a failure, unlike {@link #halt}). Idle: FAILURE(why) too.
     */
    public Status failNow(String why) {
        if (taskId < 0) return fail(why);
        Blackboard bb = bb();
        bb.port.cancel(taskId, why);
        bb.port.log("failed " + running.kind() + ": " + why);
        bb.tracer.outcome(bb.clock.now(), "failed: " + why);
        bb.tracer.counts.fails++;
        return end(bb, Status.FAILURE, why);
    }

    @Override public void pause(String why) {
        if (taskId < 0 || pausedAt >= 0) return;
        pausedAt = bb().clock.now();
        bb().port.pause(taskId, why);
        bb().port.log("paused " + running.kind() + ": " + why);
    }

    @Override public void resume() {
        if (taskId < 0 || pausedAt < 0) return;
        long now = bb().clock.now();
        startedAt += now - pausedAt;                 // the paused time counts for neither clock
        lastProgressAt += now - pausedAt;
        pausedAt = -1;
        bb().port.resume(taskId);
        bb().port.log("resumed " + running.kind());
    }

    private Blackboard bb() {
        if (board == null) throw new IllegalStateException("RunJob " + id + " halted before its first tick");
        return board;
    }

    /** chop D: the typed step the last FAILED task stopped at (null: none known, or it did not fail at a step). */
    private io.github.mojolowjo.entropybot.aa.moves.Step failedStep;

    public io.github.mojolowjo.entropybot.aa.moves.Step failedStep() { return failedStep; }

    public boolean running() { return taskId >= 0; }

    public long taskId() { return taskId; }

    public boolean paused() { return pausedAt >= 0; }

    public JobPort.TaskSpec spec() { return running; }

    public String source() { return source; }

    /** Seconds since the start (0 when idle). */
    public long ageS(long now) { return taskId < 0 ? 0 : (now - startedAt) / 1000; }  // structural: milliseconds per second

    public long progressValue() { return lastProgress; }
}
