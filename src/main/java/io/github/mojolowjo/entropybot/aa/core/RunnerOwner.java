package io.github.mojolowjo.entropybot.aa.core;

/**
 * Runner ownership (tester-2 probe-3c w053-200-2, 2026-11-22 03:53:09.806 -> .927: the line's deposit took the one step
 * runner in the tick an order's run ended, the order's task then read that run as its own, and "goal reached" stopped it:
 * "aa deposit ended: stopped at step 1 (up to the surface ...): cancelled: aa goal reached"; w003-60-2 03:42:04.354 the
 * other way round: the deposit's failed walk up was read as the order's end, "aa: failed EXPLORE: stopped at step 1 (up to
 * the surface at 16 66 -8 ...)", and the order parked on the deposit's reason).
 *
 * <p>The one step runner takes one run at a time and nothing marked whose run it holds. This is that mark: every start on
 * the runner gets a key (a serial), and the brain's task remembers the key of the run started for it. The task's cancel,
 * its end read and the "is this AA's own run" answer act on the runner only while {@link #taskHolds()}; for any other run
 * {@link #cancelLeft} / {@link #endNotMine} give the line to log, once per run and reason (rule 7). One store (rule 23): the runtime holds one
 * of these. No Minecraft or Baritone types. Loader notes: none.
 */
public final class RunnerOwner {
    /** The key of the run started last on the runner (0: none yet) and of the run started for the brain's task (-1: none). */
    private long key, task = -1;      // structural: 0 and -1 are "no run yet" and "no task run", not amounts
    /** The name of the run the runner holds, for the log. */
    private String name = "no run";
    /** The line said last by {@link #once} (its run's key and words). */
    private String said;

    /** A run for the brain's task took the runner (its typed steps, a re-plan, its way up): the new key. */
    public long taskStarts(String name) {
        task = ++key;
        this.name = name;
        return key;
    }

    /** Another run took the runner (an owner order, the line's deposit): the new key; the task's run is not in it any more. */
    public long otherStarts(String name) {
        this.name = name;
        return ++key;
    }

    /** The brain's task is over (ended, cancelled, aa off): no run on the runner is its own from here. */
    public void taskOver() { task = -1; }      // structural: -1 is "no task run"

    /** The run the runner holds (or held last) was started for the brain's task. */
    public boolean taskHolds() { return task >= 0 && task == key; }

    /** The name of the run the runner holds (or held last). */
    public String name() { return name; }

    /**
     * Whether a start on the runner took it, from the start's answer (commands.StepRun.start has two refusals): null: the
     * steps run; "busy: ...": the runner kept the run it had and took nothing; "error: ...": the new steps REPLACED the run
     * in the runner and failed at their first step (aa/moves/Steps.start sets the key, the steps and the phase before the
     * first step runs), so the runner's end and reason are the new run's from there. The mark follows the runner's
     * content: a start that failed at its first step is marked like one that runs, or the task would read the failed
     * run's reason as its own end.
     */
    public static boolean took(String refusal) { return refusal == null || !refusal.startsWith("busy"); }

    /** The name a start is marked with: a start that failed at its first step says so in the lines that name it (rule 7). */
    public static String named(String name, String refusal) { return refusal == null ? name : name + " (failed at its start)"; }

    /**
     * The one answer to "AA's own task runs" (rule 23 (2): every reader asks here): on typed steps, the runner runs AND the
     * run in it is the task's own (a deposit or an owner order in the runner is not, though the task's typed flag stays
     * set after its run ended); else AA's own chain.
     */
    public boolean ownRunning(boolean typedRun, boolean runnerRunning, boolean chainRunning) {
        return typedRun ? runnerRunning && taskHolds() : chainRunning;
    }

    /** The task is cancelled ({@code why}) while the runner holds another run: that run is not stopped. The line, once. */
    public String cancelLeft(String why) { return once("cancel " + why + ": the runner holds " + name + ", not this task - left running"); }

    /** The task's end is asked for while the runner holds another run: that run's end is never the task's. The line, once. */
    public String endNotMine() { return once("end: the runner holds " + name + ", not this task - its end is not read as this task's"); }

    /** {@code line} the first time for the run the runner holds, null when it was said last already. */
    private String once(String line) {
        String mark = key + " " + line;
        if (mark.equals(said)) return null;
        said = mark;
        return line;
    }

    /**
     * Why the line's due deposit does not start now, null: the slot is free. {@code taskOpen}: the brain's task has not been
     * read to its end (a run that ended in this tick is still the task's until its end is read - starting the deposit there
     * is what mixed the two runs up). Each reason is a fact of the slot, said by the caller when it changes.
     */
    public String depositWaits(boolean taskOpen, boolean runnerBusy, boolean jobBusy, boolean chainBusy) {
        if (runnerBusy) return "the step runner holds " + name;
        if (jobBusy) return "a job holds the slot";
        if (chainBusy) return "AA's own chain runs";
        if (taskOpen) return "the brain's task has not been read to its end (its run's end comes first, then the deposit)";
        return null;
    }
}
