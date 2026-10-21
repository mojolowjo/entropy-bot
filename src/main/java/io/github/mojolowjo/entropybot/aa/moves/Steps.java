package io.github.mojolowjo.entropybot.aa.moves;

import java.util.List;

/**
 * rm-execution: the one state machine that runs an option's typed {@link Step}s. Each step starts on {@link Hands}
 * (Baritone underneath; the item actions through their own job) and is followed every tick: DONE starts the next one,
 * the last DONE ends the run DONE; a step that fails, is refused at its start or runs past its time limit ends the whole
 * run FAILED with its stage and reason, {@code "stopped at step i (<step>): <reason>"}, and {@link #failedKey()} names
 * the option's memory key for Failures. One failure path: no retry, no "try N", no step skipped after a failure.
 * Decides nothing (the option chose the steps). Pure (no game classes): JUnit runs it on fake hands. Never throws.
 */
public final class Steps {
    public enum Phase { IDLE, RUNNING, DONE, FAILED }

    /** What a step asks the game for. The real one is {@code commands/StepHands}; tests use a fake. */
    public interface Hands {
        /** Starts the step; null when it runs, else why it could not start. */
        String start(Step s);

        /** The running step now: RUNNING / DONE / FAILED with its line. */
        Car.Status status(Step s);

        /** Ends the running step (keys, goals, its job). */
        void cancel(String why);

        /**
         * rm-execution2: the planner's routine for a {@link Step.Plan}, typed at the planner's side; {@link Routine#why}
         * says why there is none (no plan, still searching, a routine line with no typed step).
         */
        default Routine routine(Step.Plan p) { return new Routine(null, "no planner in these hands"); }
    }

    /** The planner's routine as typed steps, or null with why. */
    public record Routine(List<Step> steps, String why) {}

    /** At most this many expansions (a plan in a plan) per run: a routine that names itself ends FAILED, never loops. */
    static final int MAX_EXPANSIONS = 16;
    private int expansions;

    /** Seconds one step may run before the run fails "timed out" (Tuning steps.stepMaxS). */
    static long stepTicks() { return (long) (20 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("steps.stepMaxS")); }

    private final Hands hands;
    private List<Step> steps = List.of();
    private int idx = -1;
    private Phase phase = Phase.IDLE;
    private String key, why = "idle", lastStatus;
    private long now, stepStart;

    public Steps(Hands hands) {
        this.hands = hands;
    }

    /** Starts {@code steps} for the option whose memory key is {@code key}; a running run is cancelled first. */
    public Phase start(String key, List<Step> steps, long tick) {
        if (phase == Phase.RUNNING) cancel("replaced by " + key);
        this.key = key;
        this.steps = List.copyOf(steps);
        this.now = tick;
        idx = -1;
        lastStatus = null;
        paused = false;
        expansions = 0;
        if (this.steps.isEmpty()) {
            phase = Phase.FAILED;
            why = "stopped at step 0 (nothing): no steps to run";
            return phase;
        }
        phase = Phase.RUNNING;
        next();
        return phase;
    }

    private void next() {
        idx++;
        if (idx >= steps.size()) {
            phase = Phase.DONE;
            why = "done" + (lastStatus == null || lastStatus.isEmpty() ? "" : " - " + lastStatus);
            return;
        }
        stepStart = now;
        String bad = expand();
        if (bad != null) {
            fail(bad);
            return;
        }
        if (idx >= steps.size()) {               // an empty routine or compound: nothing left to run
            phase = Phase.DONE;
            why = "done" + (lastStatus == null || lastStatus.isEmpty() ? "" : " - " + lastStatus);
            return;
        }
        Step s = steps.get(idx);
        String r;
        try {
            r = hands.start(s);
        } catch (RuntimeException e) {
            r = "error: " + e;
        }
        if (r != null) fail(r);
        else why = stage() + " started";
    }

    /**
     * rm-execution2: a {@link Step.Compound} or a {@link Step.Plan} at the current index is replaced by its parts / the
     * planner's routine (same machine, same failure path). Null when the index holds a plain step (or ran past the end),
     * else why the expansion failed.
     */
    private String expand() {
        while (idx < steps.size() && (steps.get(idx) instanceof Step.Compound || steps.get(idx) instanceof Step.Plan)) {
            if (++expansions > MAX_EXPANSIONS) return "more than " + MAX_EXPANSIONS + " plans and compound steps in one run";
            Step s = steps.get(idx);
            List<Step> parts;
            if (s instanceof Step.Compound c) parts = c.parts();
            else {
                Routine r;
                try {
                    r = hands.routine((Step.Plan) s);
                } catch (RuntimeException e) {
                    r = new Routine(null, "the planner: " + e);
                }
                if (r == null || r.steps() == null) return "no routine for " + ((Step.Plan) s).goal() + ": " + (r == null || r.why() == null ? "the planner gave no answer" : r.why());
                parts = r.steps();
            }
            java.util.List<Step> all = new java.util.ArrayList<>(steps.subList(0, idx));
            all.addAll(parts);
            all.addAll(steps.subList(idx + 1, steps.size()));
            steps = List.copyOf(all);
        }
        return null;
    }

    private boolean paused;

    /**
     * Danger holds the bot: the running step's Baritone work stops and its clock stands still. Not a failure and not a
     * retry: {@link #resume()} starts the same step again where the bot is now.
     */
    public void pause(String why) {
        if (phase != Phase.RUNNING || paused) return;
        paused = true;
        safeCancel("paused: " + why);
        this.why = stage() + ": paused (" + why + ")";
    }

    /** Danger is over: the paused step starts again (its clock from now). */
    public void resume() {
        if (!paused) return;
        paused = false;
        if (phase != Phase.RUNNING) return;
        stepStart = now;
        String r;
        try {
            r = hands.start(steps.get(idx));
        } catch (RuntimeException e) {
            r = "error: " + e;
        }
        if (r != null) fail("after the pause: " + r.replaceFirst("^error: ", ""));
        else why = stage() + " resumed";
    }

    public boolean paused() { return paused; }

    /** One tick: follows the running step. Returns the phase after it. */
    public Phase tick(long tick) {
        now = tick;
        if (phase != Phase.RUNNING || paused) return phase;
        Step s = steps.get(idx);
        Car.Status st;
        try {
            st = hands.status(s);
        } catch (RuntimeException e) {
            st = Car.Status.failed("error: " + e);
        }
        if (st == null) st = Car.Status.failed("the hands gave no status");
        switch (st.state()) {
            case FAILED -> fail(st.why() == null ? "failed" : st.why());
            case DONE -> {
                lastStatus = st.why();
                next();
            }
            default -> {
                if (now - stepStart > stepTicks()) {
                    safeCancel("timed out");
                    fail("timed out after " + stepTicks() / 20 + " s" + (st.why() == null ? "" : " (" + st.why() + ")"));
                } else if (st.why() != null) why = stage() + ": " + st.why();
            }
        }
        return phase;
    }

    private void fail(String reason) {
        phase = Phase.FAILED;
        why = "stopped at step " + (idx + 1) + " (" + steps.get(Math.max(0, Math.min(idx, steps.size() - 1))).text() + "): " + reason.replaceFirst("^error: ", "");
    }

    /** Ends the run FAILED "cancelled" (the owner's stop, AA's switch); nothing when not running. */
    public void cancel(String reason) {
        if (phase != Phase.RUNNING) return;
        paused = false;
        safeCancel(reason);
        phase = Phase.FAILED;
        why = "stopped at step " + (idx + 1) + " (" + steps.get(idx).text() + "): cancelled: " + reason;
    }

    private void safeCancel(String reason) {
        try {
            hands.cancel(reason);
        } catch (RuntimeException e) {
            why = stage() + ": cancel failed: " + e;
        }
    }

    /** Back to IDLE after its end was read. */
    public void clear() {
        if (phase == Phase.RUNNING) cancel("cleared");
        phase = Phase.IDLE;
        paused = false;
        steps = List.of();
        idx = -1;
        key = null;
        why = "idle";
    }

    private String stage() { return "step " + (idx + 1) + "/" + steps.size() + " " + steps.get(idx).text(); }

    public Phase phase() { return phase; }

    public boolean running() { return phase == Phase.RUNNING; }

    /** "done - ...", "stopped at step i (...): reason", or the running step's line. */
    public String why() { return why; }

    /** The option's memory key when the run FAILED (Failures charges it), else null. */
    public String failedKey() { return phase == Phase.FAILED ? key : null; }

    public String key() { return key; }

    /** The running step's index (0-based), -1 none. */
    public int index() { return phase == Phase.RUNNING ? idx : -1; }

    /** "2/5 mine iron 1 at 1 2 3 dig" for the running step, null when none. */
    public String stepText() { return phase == Phase.RUNNING ? (idx + 1) + "/" + steps.size() + " " + steps.get(idx).text() : null; }

    /** The run's steps. */
    public List<Step> steps() { return steps; }
}
