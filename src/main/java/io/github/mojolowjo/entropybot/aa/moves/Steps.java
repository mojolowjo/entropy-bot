package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

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
         * one-planner: the chain for a {@link Step.Obtain} (NeedChain on the facts of the moment); {@link Routine#why}
         * says why there is none (no facts yet, no way at all).
         */
        default Routine routine(Step.Obtain p) { return new Routine(null, "no chain in these hands"); }

        /** 0.39.z30: the fact the step that just failed found changed ({@link FactChange}), taken once; null: none. */
        default FactChange.Fact lastFact() { return FactChange.take(); }

        /** 0.39.z34: what the step needs from the bag (its own data, {@link StepNeeds#of}). */
        default StepNeeds needs(Step s) { return StepNeeds.of(s); }

        /** 0.39.z34: the bag now; null: these hands have none (nothing is checked). */
        default StepNeeds.Bag bag() { return null; }

        /** 0.39.z38: the items the run's remaining steps use up (Baritone keeps them out of the throwaway list); empty: none. */
        default void keep(List<String> items) {}
    }

    /** The items the remaining steps (the running one on) use up, from their declared needs ({@link StepNeeds#keepOf}). */
    List<String> keepFromHere() {
        List<StepNeeds> rest = new java.util.ArrayList<>();
        for (int i = Math.max(0, idx); i < steps.size(); i++) {
            try {
                rest.add(hands.needs(steps.get(i)));
            } catch (RuntimeException e) {
                keepErr = "the needs of step " + (i + 1) + " could not be read for the keep list: " + e;   // said in the start line
            }
        }
        return StepNeeds.keepOf(rest);
    }

    /** What went wrong building or setting the keep list (said in the step's start line), null: nothing. */
    private String keepErr;

    private void pushKeep(List<String> items) {
        try {
            hands.keep(items);
        } catch (RuntimeException e) {
            keepErr = "the keep list could not be set: " + e;
        }
    }

    /** The run ended: nothing kept; a failure to say so is added to the end line. */
    private void endKeep() {
        keepErr = null;
        pushKeep(List.of());
        if (keepErr != null) why = why + " (" + keepErr + ")";
    }

    /** Ticks between the running tool step's bag looks (Tuning job.bagCheckTicks). */
    static long bagCheckTicks() { return Math.max(1, Tuning.i("job.bagCheckTicks")); }

    /** The running step's declared needs, the tool seen at its start and that tool's uses at the last look. */
    private StepNeeds needs = StepNeeds.NONE;
    private String hadTool;
    private int hadUses = io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN;
    private long lastBagLook;
    /** The fact the last re-plan answered (aa why says it), null: none. */
    private String replanFact;

    /**
     * 0.39.z30: the order's own planner, asked again when a step fails on a changed fact: the step list from here on the
     * facts of now (the same planner call the order used), or null steps with why (no way now, or the cap).
     */
    public interface Replanner {
        Routine replan(Step failed, FactChange.Fact fact, String why);
    }

    private Replanner replanner;
    /** The step text and reason of the failure the last re-plan answered (the same step failing again stops the run). */
    private String replannedStep, replannedWhy;


    /** The chain as typed steps, or null with why. */
    public record Routine(List<Step> steps, String why) {}

    /** At most this many expansions (a plan in a plan) per run: a routine that names itself ends FAILED, never loops. */
    static int maxExpansions() { return Tuning.i("steps.maxExpansions"); }
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
    public Phase start(String key, List<Step> steps, long tick) { return start(key, steps, tick, null); }

    /** 0.39.z30: as {@link #start(String, List, long)}, re-planned through {@code replan} when a step fails on a changed fact. */
    public Phase start(String key, List<Step> steps, long tick, Replanner replan) {
        if (phase == Phase.RUNNING) cancel("replaced by " + key);
        this.key = key;
        this.steps = List.copyOf(steps);
        failedStep = null;
        this.now = tick;
        idx = -1;
        lastStatus = null;
        paused = false;
        expansions = 0;
        replanner = replan;
        replannedStep = null;
        replannedWhy = null;
        replanFact = null;
        needs = StepNeeds.NONE;
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
            endKeep();
            return;
        }
        stepStart = now;
        if (reprice()) return;                   // 0.39.z41: a walk's path landed: priced again here, before the step starts
        String bad = expand();
        if (bad != null) {
            fail(bad);
            return;
        }
        if (idx >= steps.size()) {               // an empty routine or compound: nothing left to run
            phase = Phase.DONE;
            why = "done" + (lastStatus == null || lastStatus.isEmpty() ? "" : " - " + lastStatus);
            endKeep();
            return;
        }
        Step s = steps.get(idx);
        String r;
        FactChange.clear();
        lookNeeds(s);
        keepErr = null;
        pushKeep(keepFromHere());
        // 0.39.z34 (S39): the step's tool must be in the bag before it starts; missing, the bag fact re-plans the order
        String lack = toolLack(s);
        if (lack != null) { failOrReplan(lack); return; }
        try {
            r = hands.start(s);
        } catch (RuntimeException e) {
            r = "error: " + e;
        }
        if (r != null) failOrReplan(r);
        else {
            why = started(idx, steps) + (needs.none() ? "" : " " + needs.text()) + (replanFact == null ? "" : " (re-planned: " + replanFact + ")") + (keepErr == null ? "" : " (" + keepErr + ")");
            replanFact = null;
        }
    }

    /** The step's declared needs and the tool the bag holds for it now (a failure to read them is said in the needs). */
    private void lookNeeds(Step s) {
        hadTool = null;
        hadUses = io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN;
        lastBagLook = now;
        try {
            needs = hands.needs(s);
            if (needs == null) needs = StepNeeds.NONE;
            StepNeeds.Bag bag = hands.bag();
            if (bag != null && needs.hasTool() && bag.bestTier(needs.toolKind()) >= needs.minTier()) {
                hadTool = bag.bestTool(needs.toolKind());
                if (hadTool != null) hadUses = bag.usesLeft(hadTool);
            }
        } catch (RuntimeException e) {
            needs = new StepNeeds(null, 0, java.util.Map.of(), "the needs could not be read: " + e);
        }
    }

    /** The running step's tool missing from the bag now (the fact's words), null: there or nothing to check. */
    private String toolLack(Step s) {
        StepNeeds.Bag bag;
        try {
            bag = hands.bag();
            if (bag == null || !needs.hasTool()) return null;
            String m = needs.toolMissing(bag, Step.verb(s), hadTool, hadUses);
            if (m == null) {
                String best = bag.bestTool(needs.toolKind());
                if (best != null) { hadTool = best; hadUses = bag.usesLeft(best); }
            }
            return m;
        } catch (RuntimeException e) {
            why = stage() + ": the bag could not be read: " + e;   // said, the step goes on unchecked this look
            return null;
        }
    }

    private String toolLackAt(Step s) {
        lastBagLook = now;
        return toolLack(s);
    }

    /** 0.39.z41: a new price waiting for the next step boundary (a walk's path landed), null: none. */
    private FactChange.Fact priceAsk;

    /**
     * 0.39.z41 (owner 2026-10-23): a walk's complete path landed while this run runs: the order is priced again at the next
     * step's start (a running step is never cut). No replanner: nothing to price, said nowhere (no order behind the run).
     */
    public void repriceAtNextStep(FactChange.Fact f) { if (phase == Phase.RUNNING && replanner != null) priceAsk = f; }

    /**
     * The re-price at a step boundary: the order's planner on the facts of now; other steps than the ones left switch the
     * run (said in the step's start line, "re-priced: ... - switched"); the same steps, or no way now, keep the run as it is.
     * True: the steps were switched and the new step started.
     */
    private boolean reprice() { return reprice(false); }

    /**
     * {@code running}: 0.39.z46 (runs E-G: "explore 5 while paths compute" ended and restarted within 0-2 s on every path
     * answer) - the running explore-while-paths step is priced again on the answer where it runs; it is cancelled only when
     * the order's pick changed (other steps from here), else it runs on to its minutes.
     */
    private boolean reprice(boolean running) {
        FactChange.Fact f = priceAsk;
        priceAsk = null;
        if (f == null || replanner == null) return false;
        Step at = steps.get(idx);
        Routine r;
        try {
            r = replanner.replan(at, f, f.text());
        } catch (RuntimeException e) {
            why = stage() + ": the re-price failed: " + e + " (the run goes on as planned)";
            return false;
        }
        List<Step> left = steps.subList(idx, steps.size());
        if (r == null || r.steps() == null || r.steps().isEmpty() || r.steps().equals(left)) {
            if (r != null && r.steps() == null && r.why() != null && !running) why = stage() + ": " + r.why();
            return false;
        }
        // 0.39.z48 (S66): a running step is never cut for another way; the one step whose end IS the paths' answer - the
        // explore while paths compute - ends only for a priced way (a new pick that waits on paths again runs on as it is)
        if (running && waitsOnPaths(r.steps())) return false;
        if (running) safeCancel("re-priced: " + f.text() + " - the pick changed");
        List<Step> all = new java.util.ArrayList<>(steps.subList(0, idx));
        all.addAll(r.steps());
        steps = List.copyOf(all);
        replanFact = "re-priced: " + f.text() + " - switched to " + r.steps().get(0).text() + " first";
        idx--;
        next();
        return true;
    }

    /** 0.39.z48: the steps hold an explore while the chain's paths compute (the pick still waits on a walk's price). */
    static boolean waitsOnPaths(List<Step> s) {
        for (Step x : s) if (x instanceof Step.Explore e && e.whilePaths()) return true;
        return false;
    }

    /** The fact the last re-plan answered, until the next step's start line said it; null: none. */
    public String replanFact() { return replanFact; }

    /** The running step's declared needs ({@link StepNeeds#NONE} when idle). */
    public StepNeeds needs() { return phase == Phase.RUNNING ? needs : StepNeeds.NONE; }

    /**
     * 0.39.z30 (owner 2026-10-23): a step that failed on a changed fact (its hands say which, typed) asks the run's
     * {@link Replanner} for the steps from here on the facts of now and goes on with them; no fact, no replanner, no way
     * now or the same step failing again after a re-plan: the run ends FAILED with the reason (both reasons for a repeat).
     */
    private void failOrReplan(String reason) { failOrReplan(reason, null); }

    /** {@code known}: the fact the run itself found (a source step's timeout), null: the hands say it. */
    private void failOrReplan(String reason, FactChange.Fact known) {
        FactChange.Fact fact = known;
        if (fact == null) try {
            fact = hands.lastFact();
        } catch (RuntimeException e) {
            fact = null;
            reason = reason + " (its changed fact could not be read: " + e + ")";
        }
        // 0.39.z34 (S39): a step that ends short is checked against its declared needs: a tool gone is the cause (it wins
        // over the hands' own fact, e.g. a mine that stopped with 0 got); an item it uses up short is one when the hands had none
        if (idx >= 0 && idx < steps.size()) {
            Step s = steps.get(idx);
            String tool = toolLack(s);
            if (tool != null) {
                fact = new FactChange.Fact("bag " + needs.toolKey(), tool);
                if (!reason.contains(tool)) reason = tool + " (" + reason.replaceFirst("^error: ", "") + ")";
            } else if (fact == null) {
                String item;
                try {
                    item = needs.itemShort(hands.bag(), Step.verb(s));
                } catch (RuntimeException e) {
                    item = null;
                    reason = reason + " (the bag could not be read: " + e + ")";
                }
                if (item != null) {
                    fact = new FactChange.Fact("bag " + item.substring(0, item.indexOf(':')), item);
                    reason = reason + " (" + item + ")";
                }
            }
        }
        if (fact == null || replanner == null || idx < 0 || idx >= steps.size()) { fail(reason); return; }
        Step failed = steps.get(idx);
        String clean = reason.replaceFirst("^error: ", "");
        if (failed.text().equals(replannedStep)) {
            fail("re-planned after \"" + replannedWhy + "\" and the same step failed again: " + clean);
            return;
        }
        Routine r;
        try {
            r = replanner.replan(failed, fact, clean);
        } catch (RuntimeException e) {
            r = new Routine(null, "the re-plan: " + e);
        }
        if (r == null || r.steps() == null || r.steps().isEmpty()) {
            fail(reason + (r == null || r.why() == null ? "" : " (re-plan: " + r.why() + ")"));
            return;
        }
        replannedStep = failed.text();
        replannedWhy = clean;
        replanFact = fact.text();
        List<Step> all = new java.util.ArrayList<>(steps.subList(0, idx));
        all.addAll(r.steps());
        steps = List.copyOf(all);
        idx--;
        next();
    }

    /**
     * rm-execution2: a {@link Step.Compound} or a {@link Step.Obtain} at the current index is replaced by its parts / the
     * chain (same machine, same failure path). Null when the index holds a plain step (or ran past the end),
     * else why the expansion failed.
     */
    private String expand() {
        while (idx < steps.size() && (steps.get(idx) instanceof Step.Compound || steps.get(idx) instanceof Step.Obtain)) {
            if (++expansions > maxExpansions()) return "more than " + maxExpansions() + " chains and compound steps in one run";
            Step s = steps.get(idx);
            List<Step> parts;
            if (s instanceof Step.Compound c) parts = c.parts();
            else {
                Routine r;
                try {
                    r = hands.routine((Step.Obtain) s);
                } catch (RuntimeException e) {
                    r = new Routine(null, "the chain: " + e);
                }
                if (r == null || r.steps() == null) return "no chain for " + s.text() + ": " + (r == null || r.why() == null ? "no answer" : r.why());
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
            case FAILED -> failOrReplan(st.why() == null ? "the step ended failed without a reason from its hands" : st.why());
            case DONE -> {
                lastStatus = st.why();
                next();
            }
            default -> {
                if (priceAsk != null && s instanceof Step.Explore ex && ex.whilePaths() && reprice(true)) return phase;
                // 0.39.z34 (S39): a tool step's bag look every job.bagCheckTicks: the tool gone ends the step at once with the fact
                String lack = needs.hasTool() && now - lastBagLook >= bagCheckTicks() ? toolLackAt(s) : null;
                if (lack != null) {
                    safeCancel(lack);
                    failOrReplan(lack);
                } else if (now - stepStart > stepTicks()) {
                    safeCancel("timed out");
                    String out = "timed out after " + stepTicks() / 20 + " s";  // game fact: 20 game ticks per second
                    String reason = out + (st.why() == null ? "" : " (" + st.why() + ")");
                    // 0.39.z42 (run C 20:38:58): a source step that timed out is the z33 source fact: the order re-plans
                    // without that source; any other step's timeout ends the run as before
                    String src = io.github.mojolowjo.entropybot.aa.NeedChain.sourceKey(s);
                    if (src != null) failOrReplan(reason, new FactChange.Fact("source " + src, out));
                    else fail(reason);
                } else if (st.why() != null) why = stage() + ": " + st.why();
            }
        }
        return phase;
    }

    /**
     * 0.39.z46 (run G 23:40:12): the no-progress watch fired (no move, no step change for jobPort.noProgressMs): the
     * running step ends and its target is the source fact ("no progress 180 s at mine ..."), keyed as the step's source
     * ({@link io.github.mojolowjo.entropybot.aa.NeedChain#sourceKey}; a step with no source: its own words), so the order
     * re-plans without it. True: the run goes on re-planned; false: not running, or no re-plan (the run ended FAILED).
     */
    public boolean noProgress(String text) {
        if (phase != Phase.RUNNING || paused || idx < 0 || idx >= steps.size()) return false;
        Step s = steps.get(idx);
        String src = io.github.mojolowjo.entropybot.aa.NeedChain.sourceKey(s);
        String words = text + " at " + s.text();
        safeCancel(words);
        failOrReplan(words, FactChange.noProgress(src != null ? src : "step " + s.text(), words));
        return phase == Phase.RUNNING;
    }

    private void fail(String reason) {
        phase = Phase.FAILED;
        failedStep = idx >= 0 && idx < steps.size() ? steps.get(idx) : null;
        why = "stopped at step " + (idx + 1) + " (" + steps.get(Math.max(0, Math.min(idx, steps.size() - 1))).text() + "): " + reason.replaceFirst("^error: ", "");
        endKeep();
    }

    /** Ends the run FAILED "cancelled" (the owner's stop, AA's switch); nothing when not running. */
    public void cancel(String reason) {
        if (phase != Phase.RUNNING) return;
        paused = false;
        safeCancel(reason);
        phase = Phase.FAILED;
        why = "stopped at step " + (idx + 1) + " (" + steps.get(idx).text() + "): cancelled: " + reason;
        endKeep();
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

    private String stage() { return stage(idx, steps); }

    /** "step 1/6 craft oak_planks 3": how a run names its step (aa debug says the first step in these words). */
    public static String stage(int idx, List<? extends Step> steps) { return "step " + (idx + 1) + "/" + steps.size() + " " + steps.get(idx).text(); }

    /** The words a run logs when its step starts ("step 1/6 craft oak_planks 3 started"). */
    public static String started(int idx, List<? extends Step> steps) { return stage(idx, steps) + " started"; }

    public Phase phase() { return phase; }

    public boolean running() { return phase == Phase.RUNNING; }

    /** "done - ...", "stopped at step i (...): reason", or the running step's line. */
    public String why() { return why; }

    /** The option's memory key when the run FAILED (Failures charges it), else null. */
    public String failedKey() { return phase == Phase.FAILED ? key : null; }

    private Step failedStep;

    /** chop D: the step the run FAILED at by its hands (a cancel is none), else null. */
    public Step failedStep() { return phase == Phase.FAILED ? failedStep : null; }

    public String key() { return key; }

    /** The running step's index (0-based), -1 none. */
    public int index() { return phase == Phase.RUNNING ? idx : -1; }

    /** "2/5 mine iron 1 at 1 2 3 dig" for the running step, null when none. */
    public String stepText() { return phase == Phase.RUNNING ? (idx + 1) + "/" + steps.size() + " " + steps.get(idx).text() + (needs.none() ? "" : " " + needs.text()) : null; }

    /** The run's steps. */
    public List<Step> steps() { return steps; }
}
