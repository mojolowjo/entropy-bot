package io.github.mojolowjo.entropybot.aa.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runner ownership (to-do/deposit-faults-2026-11-23.md, "walk-up" part 2): whose run the one step runner holds. The runs
 * are tester-2's of 2026-11-22: w053-200-2 (the torch order's "goal reached" stopped the line's deposit) and w003-60-2
 * (the deposit's failed walk up read as the birch_log order's end).
 */
class RunnerOwnerTest {

    @Test
    void theTasksOwnRunIsItsOwn() {
        RunnerOwner r = new RunnerOwner();
        assertFalse(r.taskHolds(), "nothing started yet");
        r.taskStarts("EXPLORE owner goal");
        assertTrue(r.taskHolds());
        r.taskStarts("replan torch");                      // a re-plan, the way up: still the task's
        assertTrue(r.taskHolds());
        assertEquals("replan torch", r.name());
    }

    /**
     * Merge review 2026-11-23: "the runner holds the line's deposit run" is asked by that run's key, not by the task's typed
     * flag - the deposit's own amounts are its to put away only while its run is the one in the runner.
     */
    @Test
    void aRunIsHeldByItsKeyUntilTheNextStart() {
        RunnerOwner r = new RunnerOwner();
        assertFalse(r.holds(r.key()), "no run yet");
        r.taskStarts("EXPLORE owner goal");
        r.otherStarts("aa deposit");
        long deposit = r.key();
        assertTrue(r.holds(deposit));
        r.otherStarts("aa eat");                           // an owner one-shot after it
        assertFalse(r.holds(deposit), "the deposit run is no longer the one in the runner");
        r.taskStarts("EXPLORE owner goal");
        assertFalse(r.holds(deposit));
    }

    /** w053-200-2 03:53:09.806: "owner order deposit" took the runner while the torch order's task was still open. */
    @Test
    void aDepositInTheRunnerIsNotTheTasks() {
        RunnerOwner r = new RunnerOwner();
        r.taskStarts("EXPLORE owner goal");
        r.otherStarts("aa deposit");
        assertFalse(r.taskHolds(), "the runner holds the deposit");
        assertEquals("cancel goal reached: the runner holds aa deposit, not this task - left running", r.cancelLeft("goal reached"));
        assertNull(r.cancelLeft("goal reached"), "said once");
        assertEquals("end: the runner holds aa deposit, not this task - its end is not read as this task's", r.endNotMine());
        assertNull(r.endNotMine(), "said once");
        r.otherStarts("aa deposit");                       // the next deposit run: its own line
        assertNotNull(r.cancelLeft("goal reached"));
        r.taskStarts("EXPLORE owner goal");
        assertTrue(r.taskHolds(), "the task's next run is its own again");
    }

    /**
     * Review 2 (rule 23 (2)): "AA's own task runs" has one answer. The task's typed flag stays set after its run ended, so
     * with the line's deposit in the runner the old answer (the runner runs) took the deposit for the task's run - the swim
     * watch could cancel it.
     */
    @Test
    void aDepositRunningIsNotTheTasksOwnRun() {
        RunnerOwner r = new RunnerOwner();
        r.taskStarts("EXPLORE owner goal");
        assertTrue(r.ownRunning(true, true, false), "the task's typed steps run");
        assertFalse(r.ownRunning(true, false, false), "its run ended");
        r.otherStarts("aa deposit");
        assertFalse(r.ownRunning(true, true, false), "the runner runs the deposit: not AA's own run");
        assertTrue(r.ownRunning(false, true, true), "not on typed steps: AA's own chain");
        assertFalse(r.ownRunning(false, true, false));
    }

    /**
     * Review 4: the mark follows what the runner holds. StepRun.start answers null (runs), "busy: ..." (took nothing) or
     * "error: ..." (the new steps replaced the run and failed at their first step).
     */
    @Test
    void theMarkFollowsWhatTheRunnerHolds() {
        assertTrue(RunnerOwner.took(null));
        assertFalse(RunnerOwner.took("busy: steps aa deposit"), "a busy runner kept its run");
        assertTrue(RunnerOwner.took("error: no main chest set"), "the failed steps are the run in the runner now");
        assertEquals("aa deposit", RunnerOwner.named("aa deposit", null));
        assertEquals("aa deposit (failed at its start)", RunnerOwner.named("aa deposit", "error: no main chest set"));
        RunnerOwner r = new RunnerOwner();
        r.taskStarts("EXPLORE owner goal");
        String busy = "busy: steps EXPLORE owner goal";
        if (RunnerOwner.took(busy)) r.otherStarts("aa scan");
        assertTrue(r.taskHolds(), "a busy start leaves the task's run its own");
        String err = "error: nothing to scan";
        if (RunnerOwner.took(err)) r.otherStarts(RunnerOwner.named("aa scan", err));
        assertFalse(r.taskHolds(), "the failed order's steps are in the runner: their end is not the task's");
        assertEquals("end: the runner holds aa scan (failed at its start), not this task - its end is not read as this task's", r.endNotMine());
    }

    @Test
    void aTaskThatIsOverHoldsNothing() {
        RunnerOwner r = new RunnerOwner();
        r.taskStarts("EXPLORE owner goal");
        r.taskOver();
        assertFalse(r.taskHolds());
    }

    /** deposit-goal stage 4: the line's deposit is the brain's task now - one runner owner, no separate deposit wait. */
    @Test
    void noDepositWaitBesideTheTask() {
        for (var m : RunnerOwner.class.getMethods()) assertFalse(m.getName().startsWith("deposit"), "no deposit reader on the runner owner: " + m.getName());
    }
}
