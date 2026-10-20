package io.github.mojolowjo.entropybot.aa.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA v0 P1 (AA_PLAN 3, 12): RunJob over a fake JobPort: refuse, done, fail, timeout, no progress, GONE, pause, halt. */
class RunJobTest {
    final Clock.Fake clock = new Clock.Fake(5_000_000);
    final FakePort port = new FakePort();
    final Tracer tracer = new Tracer();
    final Blackboard bb = new Blackboard(clock, port, tracer);
    /** Limits for the tests: timeout 100 s, no progress 40 s. */
    final RunJob job = new RunJob("needs.torches", TaskKind.TORCHES,
            b -> new JobPort.TaskSpec(TaskKind.TORCHES, "goal torch 16", "needs", 1, 100_000, 40_000));

    /** 0.36.2 (review 7): every TaskSpec limit is finite. */
    @Test
    void taskSpecLimitsAreAlwaysFinite() {
        JobPort.TaskSpec t = new JobPort.TaskSpec(TaskKind.WAIT, "wait 60", "idle", 1, Long.MAX_VALUE, 0);
        assertEquals(JobPort.TaskSpec.TIMEOUT_MS, t.timeoutMs());
        assertEquals(JobPort.TaskSpec.NO_PROGRESS_MS, t.noProgressMs());
        assertEquals(100_000, new JobPort.TaskSpec(TaskKind.WAIT, "x", "idle", 1, 100_000, -5).timeoutMs());
    }

    @Test
    void startRefusedIsAFailureWithTheRefusal() {
        port.refuse = "busy: the owner's chain runs";
        assertEquals(Status.FAILURE, job.tick(bb));
        assertEquals("busy: the owner's chain runs", job.reason());
        assertFalse(job.running());
        assertTrue(port.log.get(0).startsWith("refused TORCHES \"goal torch 16\": busy"), port.log.get(0));
        assertEquals(2, tracer.decisions().size(), "a decision line even for a refusal, and its end line (aa-switch2)");
    }

    @Test
    void doneAndFailed() {
        assertEquals(Status.RUNNING, job.tick(bb));
        assertTrue(job.running());
        assertEquals(1, job.taskId());
        assertSame(job, bb.task());
        assertEquals("start TORCHES \"goal torch 16\" by needs #1", port.log.get(0));
        clock.advance(34_000);
        assertEquals(Status.RUNNING, job.tick(bb));
        assertEquals(34, job.ageS(clock.now()));
        port.done(1, "done - 16 torches");
        assertEquals(Status.SUCCESS, job.tick(bb));
        assertFalse(job.running());
        assertNull(bb.task());
        assertTrue(port.log.get(1).startsWith("done TORCHES in 34s"), port.log.get(1));
        assertTrue(tracer.decisions().get(1).contains("\"outcome\":\"done\""));
        // failed
        assertEquals(Status.RUNNING, job.tick(bb));
        port.fail(2, "stopped at step 2 (smelt): no fuel");
        assertEquals(Status.FAILURE, job.tick(bb));
        assertEquals("stopped at step 2 (smelt): no fuel", job.reason());
        assertEquals(1, tracer.counts.fails);
        assertEquals(0, tracer.counts.switches, "ends are no switches");
    }

    @Test
    void timeoutCancelsWithAnIncident() {
        job.tick(bb);
        clock.advance(39_000);
        port.step(1);
        assertEquals(Status.RUNNING, job.tick(bb));
        clock.advance(39_000);
        port.step(1);
        assertEquals(Status.RUNNING, job.tick(bb));
        clock.advance(23_000);
        port.step(1);
        assertEquals(Status.FAILURE, job.tick(bb));
        assertEquals("timeout", job.reason());
        assertEquals(1, port.cancelled.size());
        assertTrue(port.cancelled.get(0).startsWith("1: timeout"));
        assertEquals(1, port.incidents.size());
        assertTrue(port.incidents.get(0).startsWith("aa timeout TORCHES"));
    }

    @Test
    void noProgressCancels() {
        job.tick(bb);
        clock.advance(20_000);
        assertEquals(Status.RUNNING, job.tick(bb));
        port.step(1);
        clock.advance(20_000);
        assertEquals(Status.RUNNING, job.tick(bb), "progress 20 s ago resets the watch (40 s since the start)");
        clock.advance(41_000);
        assertEquals(Status.FAILURE, job.tick(bb));
        assertEquals("no progress", job.reason());
        assertTrue(port.log.get(port.log.size() - 1).startsWith("stuck TORCHES: no progress 41s"), port.log.toString());
        assertEquals(1, port.incidents.size());
    }

    @Test
    void goneIsLost() {
        job.tick(bb);
        port.gone(1, "rule #2");
        assertEquals(Status.FAILURE, job.tick(bb));
        assertEquals("removed by rule #2", job.reason());
        assertEquals(1, tracer.counts.lost);
        assertEquals("lost TORCHES: removed by rule #2", port.log.get(1));
    }

    @Test
    void pauseStopsBothClocksAndHaltCancelsWithoutAFailure() {
        job.tick(bb);
        clock.advance(5_000);
        job.pause("danger: drowning");
        assertTrue(job.paused());
        assertEquals(1, port.paused);
        clock.advance(200_000);                       // longer than both limits
        assertEquals(Status.RUNNING, job.tick(bb), "paused: no timeout, no stuck");
        job.resume();
        assertEquals(1, port.resumed);
        assertEquals(5, job.ageS(clock.now()), "the paused time is not counted");
        clock.advance(4_000);
        assertEquals(Status.RUNNING, job.tick(bb));
        job.halt("preempted by owner: goto");
        assertFalse(job.running());
        assertEquals(0, tracer.counts.fails);
        assertEquals(1, tracer.counts.switches);
        assertEquals(0, tracer.counts.pointless, "an owner preempt is justified");
        assertTrue(port.cancelled.get(0).endsWith("preempted by owner: goto"));
        // a pointless one
        job.tick(bb);
        job.halt("re-chose");
        assertEquals(2, tracer.counts.switches);
        assertEquals(1, tracer.counts.pointless);
        assertTrue(tracer.lastSwitchText(clock.now()).contains("(pointless)"), tracer.lastSwitchText(clock.now()));
    }

    @Test
    void snapshotHasStableNodeIdsAndCounts() {
        job.tick(bb);
        String s = tracer.snapshot(clock.now(), true, "needs", bb.taskJson(clock.now()), bb.in);
        assertTrue(s.contains("\"driver\":\"needs\""), s);
        assertTrue(s.contains("\"nodes\":[{\"id\":\"needs.torches\",\"s\":\"RUNNING\"}]"), s);
        assertTrue(s.contains("\"task\":{\"id\":1,\"kind\":\"TORCHES\",\"chain\":\"goal torch 16\""), s);
        assertTrue(s.contains("\"counts\":{\"switches\":0,\"pointless\":0,\"preempts\":0,\"fails\":0,\"lost\":0,\"ticks\":0,\"tickUs\":0}"), s);
        assertTrue(s.contains("\"inputs\":{\"health\":1.0,"), s);
        assertEquals("\"a \\\"q\\\" \\n\"", Tracer.q("a \"q\" \n"));
    }
}
