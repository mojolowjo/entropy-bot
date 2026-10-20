package io.github.mojolowjo.entropybot.aa.core;

import java.util.function.Function;

/**
 * AA v0 (AA_PLAN 3): the leaf that owns one task. States and lines as the plan's table: start ok -> RUNNING; refused ->
 * FAILURE(refusal) (the Cooldown above keeps it from retrying every tick); chain ended "done..." -> SUCCESS; ended
 * otherwise -> FAILURE(end text); timeout -> cancel, FAILURE("timeout"); no progress -> cancel, FAILURE("no progress");
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

    /** A fixed chain. */
    public static RunJob of(String id, TaskKind kind, String chain, String source) {
        return new RunJob(id, kind, bb -> JobPort.TaskSpec.of(kind, chain, source));
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
                bb.port.log("done " + running.kind() + " in " + (now - startedAt) / 1000 + "s" + (st.text() == null ? "" : ": " + st.text()));
                bb.tracer.outcome(now, "done");
                return end(bb, Status.SUCCESS, null);
            }
            case FAILED -> {
                bb.port.log("failed " + running.kind() + ": " + st.text());
                bb.tracer.outcome(now, "failed: " + st.text());
                bb.tracer.counts.fails++;
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
            String why = "timeout after " + (now - startedAt) / 1000 + "s";
            bb.port.cancel(taskId, why);
            bb.port.log("timeout " + running.kind() + " after " + (now - startedAt) / 1000 + "s");
            bb.port.incident("aa timeout " + running.kind() + " \"" + running.chain() + "\"");
            bb.tracer.outcome(now, "timeout");
            bb.tracer.counts.fails++;
            return end(bb, Status.FAILURE, "timeout");
        }
        if (now - lastProgressAt > running.noProgressMs()) {
            long s = (now - lastProgressAt) / 1000;
            bb.port.cancel(taskId, "no progress " + s + "s");
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

    public boolean running() { return taskId >= 0; }

    public long taskId() { return taskId; }

    public boolean paused() { return pausedAt >= 0; }

    public JobPort.TaskSpec spec() { return running; }

    public String source() { return source; }

    /** Seconds since the start (0 when idle). */
    public long ageS(long now) { return taskId < 0 ? 0 : (now - startedAt) / 1000; }

    public long progressValue() { return lastProgress; }
}
