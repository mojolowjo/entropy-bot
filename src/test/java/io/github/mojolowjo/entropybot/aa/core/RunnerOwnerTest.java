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

    @Test
    void aTaskThatIsOverHoldsNothing() {
        RunnerOwner r = new RunnerOwner();
        r.taskStarts("EXPLORE owner goal");
        r.taskOver();
        assertFalse(r.taskHolds());
    }

    /** w053-200-2 03:53:09.796 -> .806: the order's run ended and the deposit started before the brain read that end. */
    @Test
    void theDueDepositWaitsWithItsReason() {
        RunnerOwner r = new RunnerOwner();
        r.taskStarts("EXPLORE owner goal");
        assertEquals("the step runner holds EXPLORE owner goal", r.depositWaits(true, true, false, false));
        String open = r.depositWaits(true, false, false, false);
        assertNotNull(open, "the run ended, the task is not read to its end: the deposit waits");
        assertTrue(open.contains("has not been read to its end"), open);
        assertEquals("a job holds the slot", r.depositWaits(false, false, true, false));
        assertEquals("AA's own chain runs", r.depositWaits(false, false, false, true));
        assertNull(r.depositWaits(false, false, false, false), "the slot is free");
    }
}
