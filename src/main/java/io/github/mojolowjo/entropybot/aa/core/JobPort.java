package io.github.mojolowjo.entropybot.aa.core;

/**
 * AA v0 (AA_PLAN 3): the one door from the tree to the bot's jobs. The adapter (commands.AaRuntime) implements it over
 * Chains/Jobs; tests use a fake. Only AA's own tasks go through it.
 */
public interface JobPort {
    /** What a task asks for: the chain text, who asked, a priority, its time limits (ms). */
    record TaskSpec(TaskKind kind, String chain, String source, int priority, long timeoutMs, long noProgressMs) {
        public static final long TIMEOUT_MS = 20 * 60_000, NO_PROGRESS_MS = 180_000, MAX_MS = 60 * 60_000;

        /** 0.36.2 (review 7): every limit is finite: 0, negative or over an hour (MAX_VALUE) becomes the default. */
        public TaskSpec {
            if (timeoutMs <= 0 || timeoutMs > MAX_MS) timeoutMs = TIMEOUT_MS;
            if (noProgressMs <= 0 || noProgressMs > MAX_MS) noProgressMs = NO_PROGRESS_MS;
        }

        public static TaskSpec of(TaskKind kind, String chain, String source) {
            return new TaskSpec(kind, chain, source, 1, TIMEOUT_MS, NO_PROGRESS_MS);
        }
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

    /**
     * A "goal ..." text -> the chain that does it (the planner's routine), or null while the planner still searches
     * (AA_PLAN 9: the ladder asks the planner before starting, as the brain's {@code BrainState.planner} does). Other
     * texts come back as they are.
     */
    default String resolve(String chain) { return chain; }

    /** Feasibility (AA_PLAN 9): why the chain cannot start now (brain/Feasibility's verdict), or null when it can. */
    default String infeasible(String chain) { return null; }

    /** A line for the game log ("[entropybot] aa: ..."). */
    default void log(String line) {}

    /** An incident for the recorder (timeout, no progress). */
    default void incident(String what) {}
}
