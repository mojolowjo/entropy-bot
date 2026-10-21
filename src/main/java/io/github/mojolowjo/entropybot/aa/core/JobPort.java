package io.github.mojolowjo.entropybot.aa.core;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

/**
 * AA v0 (AA_PLAN 3): the one door from the tree to the bot's jobs. The adapter (commands.AaRuntime) implements it over
 * Chains/Jobs; tests use a fake. Only AA's own tasks go through it.
 */
public interface JobPort {
    /**
     * What a task asks for: the chain text, who asked, a priority, its time limits (ms). rm-execution: {@code steps} the
     * option's typed steps (aa/moves/Steps runs them; {@code chain} is then only their label); empty: a chain text.
     */
    record TaskSpec(TaskKind kind, String chain, String source, int priority, long timeoutMs, long noProgressMs,
                    java.util.List<io.github.mojolowjo.entropybot.aa.moves.Step> steps) {
        public static long timeoutMsV() { return Tuning.l("jobPort.timeoutMs"); }
        public static long noProgressMsV() { return Tuning.l("jobPort.noProgressMs"); }
        public static long maxMs() { return Tuning.l("jobPort.maxMs"); }

        /** 0.36.2 (review 7): every limit is finite: 0, negative or over an hour (MAX_VALUE) becomes the default. */
        public TaskSpec {
            if (timeoutMs <= 0 || timeoutMs > maxMs()) timeoutMs = timeoutMsV();
            if (noProgressMs <= 0 || noProgressMs > maxMs()) noProgressMs = noProgressMsV();
            steps = steps == null ? java.util.List.of() : java.util.List.copyOf(steps);
        }

        public TaskSpec(TaskKind kind, String chain, String source, int priority, long timeoutMs, long noProgressMs) {
            this(kind, chain, source, priority, timeoutMs, noProgressMs, java.util.List.of());
        }

        /** rm-execution: a task on typed steps ({@code label} = Step.label of them). */
        public static TaskSpec typed(TaskKind kind, String label, java.util.List<io.github.mojolowjo.entropybot.aa.moves.Step> steps, String source) {
            return new TaskSpec(kind, label, source, 1, timeoutMsV(), noProgressMsV(), steps);
        }

        /** rm-execution2: a task on typed steps, its label made from them. */
        public static TaskSpec steps(TaskKind kind, java.util.List<io.github.mojolowjo.entropybot.aa.moves.Step> steps, String source) {
            return steps(kind, steps, source, timeoutMsV(), noProgressMsV());
        }

        public static TaskSpec steps(TaskKind kind, java.util.List<io.github.mojolowjo.entropybot.aa.moves.Step> steps, String source, long timeoutMs, long noProgressMs) {
            return new TaskSpec(kind, io.github.mojolowjo.entropybot.aa.moves.Step.label(steps), source, 1, timeoutMs, noProgressMs, steps);
        }

        /** AA's own task (anything but the AI operator's text, which is owner vocabulary on the chain interpreter). */
        public boolean typed() { return !steps.isEmpty(); }
    }

    enum State { RUNNING, DONE, FAILED, GONE }

    /** The task's state; {@code text} = the end text (DONE/FAILED) or who removed it (GONE). */
    record JobState(State state, String text) {
        public static final JobState RUNNING = new JobState(State.RUNNING, null);

        public static JobState done(String text) { return new JobState(State.DONE, text); }

        public static JobState failed(String text) { return new JobState(State.FAILED, text); }

        public static JobState gone(String who) { return new JobState(State.GONE, who); }
    }

    /** Starts the task; the new task id (> 0), or a refusal in {@link #lastRefusal()} and -1. */
    long start(TaskSpec t);

    /** Why the last start was refused ("busy: ...", "error: ..."). */
    String lastRefusal();

    JobState poll(long taskId);

    /** Ends AA's own task (never anyone else's). */
    void cancel(long taskId, String why);

    /**
     * The danger guard took the bot: the running step stops (the adapter ends it as "interrupted", so the chain retries it
     * once calm); the task stays AA's and its clocks stand still (RunJob).
     */
    default void pause(long taskId, String why) {}

    /** Danger is over: the chain's own retry restarts the interrupted step. */
    default void resume(long taskId) {}

    /** A monotonic activity counter (steps done + blocks moved + bag changes): the no-progress watch. */
    long progress(long taskId);

    /** MINING_PLAN 10.1 (S0): what the planner said about a goal: still SEARCHING, NONE (no plan, {@code why}), or READY. */
    enum PlanState { SEARCHING, NONE, READY }

    /**
     * The planner's answer with its state and cost (the planner's own step-cost sum). rm-execution2: {@code steps} is the
     * planner's routine typed ONCE at the planner's side (the adapter); AA never reads or builds the routine's text. A
     * routine the facts cannot run now is NONE with {@code why} starting {@value #INFEASIBLE}.
     */
    record Resolved(java.util.List<io.github.mojolowjo.entropybot.aa.moves.Step> steps, double cost, PlanState state, String why, int logsMissing) {
        public Resolved {
            steps = steps == null ? null : java.util.List.copyOf(steps);
        }

        public static Resolved ready(java.util.List<io.github.mojolowjo.entropybot.aa.moves.Step> steps, double cost) { return new Resolved(steps, cost, PlanState.READY, null, 0); }

        public static Resolved searching() { return new Resolved(null, 0, PlanState.SEARCHING, null, 0); }

        public static Resolved none(String why) { return none(why, 0); }

        /** No plan; logsMissing: the logs the planner's dead end lacked (the wood way answers them), else 0. */
        public static Resolved none(String why, int logsMissing) { return new Resolved(null, 0, PlanState.NONE, why == null ? "the planner found no plan" : why, Math.max(0, logsMissing)); }

        /** The routine exists but the facts cannot run it now (brain/Feasibility's verdict). */
        public boolean infeasible() { return state == PlanState.NONE && why != null && why.startsWith(INFEASIBLE); }
    }

    String INFEASIBLE = "infeasible: ";

    /**
     * The planner's answer for {@code goal} (its name: "torch 16", "iron tools"): SEARCHING, NONE with why, or READY with
     * the routine as typed steps. Default (no planner): NONE.
     */
    default Resolved resolveState(String goal) { return Resolved.none("no planner"); }

    /** A line for the game log ("[entropybot] aa: ..."). */
    default void log(String line) {}

    /** An incident for the recorder (timeout, no progress). */
    default void incident(String what) {}
}
