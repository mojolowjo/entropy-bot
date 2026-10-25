package io.github.mojolowjo.entropybot.aa.moves;

import baritone.api.process.Reserves;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.List;

/**
 * rm-execution: the one state machine that runs an option's typed {@link Step}s. Each step starts on {@link Hands}
 * (Baritone underneath; the item actions through their own job) and is followed every tick: DONE starts the next one,
 * the last DONE ends the run DONE; a step that fails, is refused at its start or runs past its time limit ends the whole
 * run FAILED with its stage and reason, {@code "stopped at step i (<step>): <reason>"}, and {@link #failedKey()} names
 * the option's memory key for Failures. One failure path: no retry, no "try N", no step skipped after a failure.
 * Decides nothing (the option chose the steps). Pure (no game classes): JUnit runs it on fake hands. Never throws.
 * <p>0.39.z61 (design D1 J2): at every step start the reserves of the steps from here ({@link Reservations}, counted, from
 * the plan's own takes carried in {@link Routine#uses}) go to the hands' lease, and the dry run ({@link DryRun}) walks those
 * steps on the bag: a shortage re-plans the order before the step runs. Before a re-plan or a re-price the lease keeps only
 * the orders' reserves (release before repair, said in the re-plan line). Loader notes: none (the fork's
 * {@code baritone.api.process.Reserves} is plain Java).
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

        /**
         * 0.39.z61 (D1 J2, replaces z38's keep list): the run's reserves to the fork's lease - what the remaining steps use up,
         * counted, and the orders' targets; Baritone places a reserved item only while the bag holds more than the reserves
         * need. Called at every step start (the whole table), with only the orders' reserves before a re-plan, empty at the
         * run's end.
         */
        default void reserve(List<Reserves.Reserve<String>> reserves) {}

        /**
         * 0.39.z61 (owner's ruling 1 on D1, option c): the orders' ORDER reserves - the running order's target and every
         * finished order's items (safe from pillaring, usable as ingredients); empty: none.
         */
        default List<Reserves.Reserve<String>> orderReserves() { return List.of(); }

        /**
         * 0.39.z56 item 2a (run O 22:15:20-22:17:43: "probe walk:" lines went on after the iron order stopped, 790 -> 3197):
         * the running step is no longer running - done, failed, refused at its start, cancelled, timed out, no progress,
         * re-planned or the run's end. Called on every one of those paths (more than once is fine); the hands drop what
         * follows the step (the probes' step pointers). Never changes what the step did.
         */
        default void ended(String how) {}
    }

    /** 0.39.z56 item 2a: tells the hands the step is over; a failure there is said in the run's words, never thrown. */
    private void ended(String how) {
        try {
            hands.ended(how);
        } catch (RuntimeException e) {
            why = why + " (the step's end could not be told to its hands: " + e + ")";
        }
    }

    /**
     * 0.39.z61: step {@code i}'s needs: its hands' declaration ({@link StepNeeds#of}) with the plan's own counts for it
     * ({@link #uses}) when the chain carried them. A failure to read them is said in the start line (null needs).
     */
    StepNeeds needsAt(int i) {
        try {
            StepNeeds n = hands.needs(steps.get(i));
            if (n == null) n = StepNeeds.NONE;
            return n.withUses(usesAt(i));
        } catch (RuntimeException e) {
            keepErr = "the needs of step " + (i + 1) + " could not be read for the reserves: " + e;   // said in the start line
            return null;
        }
    }

    /** The needs of the steps from here (the running one on), parallel to {@code steps.subList(idx, ...)}. */
    private List<StepNeeds> needsFromHere() {
        List<StepNeeds> rest = new java.util.ArrayList<>();
        for (int i = Math.max(0, idx); i < steps.size(); i++) rest.add(needsAt(i));
        return rest;
    }

    /** 0.39.z61 (D1 J2 3.4 step 1): the reserves of the steps from here plus the orders' ({@link Reservations#build}). */
    Reservations reservationsFromHere(List<StepNeeds> rest) {
        return Reservations.build(steps, Math.max(0, idx), rest, orders());
    }

    /** The orders' reserves from the hands (a failure is said in the start line: none then). */
    private List<Reserves.Reserve<String>> orders() {
        try {
            List<Reserves.Reserve<String>> o = hands.orderReserves();
            return o == null ? List.of() : o;
        } catch (RuntimeException e) {
            keepErr = "the orders' reserves could not be read: " + e;
            return List.of();
        }
    }

    /** What went wrong building or setting the reserves (said in the step's start line), null: nothing. */
    private String keepErr;

    /** 0.39.z61: the table pushed last (aa why; what a release lets go); NONE when nothing is reserved. */
    private Reservations table = Reservations.NONE;

    /** 0.39.z61: the last dry-run shortage (aa why), null: none at the last step start. */
    private String lastDry;

    private void pushReserve(Reservations r) {
        table = r == null ? Reservations.NONE : r;
        try {
            hands.reserve(table.list());
        } catch (RuntimeException e) {
            keepErr = "the reserves could not be set: " + e;
        }
    }

    /**
     * 0.39.z61 (D1 3.4, release before repair; Round 4 #4): before the order is planned or priced again, the lease keeps only
     * the orders' reserves, so the new chain is priced without the old chain's claims. The words for the re-plan line, ""
     * when nothing of the chain was reserved.
     */
    private String release() {
        int n = table.chainCount();
        keepErr = null;
        pushReserve(Reservations.ordersOnly(table.orders()));
        String err = keepErr == null ? "" : " (" + keepErr + ")";
        return n == 0 ? err : "released " + n + " reserve" + (n == 1 ? "" : "s") + " of the old chain" + err;
    }

    /** The run ended: nothing reserved; a failure to say so is added to the end line. */
    private void endKeep() {
        ended("the run ended");                  // 0.39.z56 item 2a: every run end (done, failed, cancelled)
        keepErr = null;
        lastDry = null;
        pushReserve(Reservations.NONE);
        if (keepErr != null) why = why + " (" + keepErr + ")";
    }

    /**
     * 0.39.z61: aa why's reserve words: the table pushed at the step's start ({@link Reservations#text}) and the last dry-run
     * shortage; "nothing (no run)" when idle.
     */
    public String reservesText() {
        if (phase != Phase.RUNNING) return "nothing (no run)";
        return table.text() + (lastDry == null ? "" : " | dry run: " + lastDry);
    }

    // ---- 0.39.z61: the plan's own counts, parallel to the steps ----

    /** Per step, what the plan's simulated bag took for it (null entries: none known); kept parallel to {@link #steps}. */
    private List<java.util.Map<String, Integer>> uses = List.of();

    private java.util.Map<String, Integer> usesAt(int i) { return i >= 0 && i < uses.size() ? uses.get(i) : null; }

    /** {@code n} null entries (steps with no plan counts). */
    private static List<java.util.Map<String, Integer>> nulls(int n) {
        List<java.util.Map<String, Integer>> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) out.add(null);
        return out;
    }

    /** {@code u} sized to {@code n} steps (missing: null, too long: cut). */
    private static List<java.util.Map<String, Integer>> sized(List<java.util.Map<String, Integer>> u, int n) {
        List<java.util.Map<String, Integer>> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) out.add(u != null && i < u.size() ? u.get(i) : null);
        return out;
    }

    /** Steps {@code 0..at} kept, {@code r}'s steps (with its uses) put in their place from {@code at} on, then {@code tail}. */
    private void splice(int at, List<Step> parts, List<java.util.Map<String, Integer>> partUses, int tailFrom) {
        List<Step> all = new java.util.ArrayList<>(steps.subList(0, at));
        List<java.util.Map<String, Integer>> u = new java.util.ArrayList<>(sized(uses, steps.size()).subList(0, at));
        all.addAll(parts);
        u.addAll(sized(partUses, parts.size()));
        if (tailFrom < steps.size()) {
            all.addAll(steps.subList(tailFrom, steps.size()));
            u.addAll(sized(uses, steps.size()).subList(tailFrom, steps.size()));
        }
        steps = List.copyOf(all);
        uses = java.util.Collections.unmodifiableList(u);
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
    /** 0.39.z67 item 1: the key of the fact the last re-plan answered (a place-near failed at another cell is another fact). */
    private String replannedKey;


    /**
     * The chain as typed steps, or null with why. 0.39.z61: {@code uses} - per step, what the plan's simulated bag took for it
     * (NeedChain.Chain#uses; empty: not known, the steps' own data counts).
     */
    public record Routine(List<Step> steps, String why, List<java.util.Map<String, Integer>> uses) {
        public Routine(List<Step> steps, String why) { this(steps, why, List.of()); }

        public Routine {
            uses = uses == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(uses));
        }
    }

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
    public Phase start(String key, List<Step> steps, long tick, Replanner replan) { return start(key, steps, null, tick, replan); }

    /** 0.39.z61: as above, with the plan's own counts per step ({@code uses}, parallel to {@code steps}; null: none known). */
    public Phase start(String key, List<Step> steps, List<java.util.Map<String, Integer>> uses, long tick, Replanner replan) {
        if (phase == Phase.RUNNING) cancel("replaced by " + key);
        runId++;                                     // 0.39.z58 review 2: a new order / chain is active (the fork's first-answer class)
        this.key = key;
        this.steps = List.copyOf(steps);
        this.uses = java.util.Collections.unmodifiableList(sized(uses, this.steps.size()));
        table = Reservations.NONE;
        lastDry = null;
        dryHold = null;
        failedStep = null;
        this.now = tick;
        idx = -1;
        lastStatus = null;
        paused = false;
        expansions = 0;
        replanner = replan;
        replannedStep = null;
        replannedWhy = null;
        replannedKey = null;
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
        FactChange.clear();
        lookNeeds(s);
        keepErr = null;
        // 0.39.z61 (D1 J2 3.4): the reserves of the steps from here, counted, to the lease; then the dry run of those steps on
        // the bag: a shortage is a bag fact that re-plans the order now, before the step runs (no order: said in the line)
        List<StepNeeds> rest = needsFromHere();
        Reservations rs = reservationsFromHere(rest);
        pushReserve(rs);
        dryHold = null;
        DryRun.Shortage dry = dryRun(rest);
        // 0.39.z62 item 3a (z61 review: the client bag can lag a tick behind a pickup): a shortage of a LATER step is looked
        // at again on the next tick before it re-plans the order; the step waits that tick (said in its line)
        if (dry != null && replanner != null) {
            dryHold = dry;
            why = stage() + ": dry run short, looking again next tick before re-planning (the bag may lag a pickup): " + dry.text();
            return;
        }
        begin(s, rs, dry);
    }

    /**
     * 0.39.z62 item 3a: a step held one tick on a dry-run shortage: the dry run again on the bag of now; still short, the bag
     * fact re-plans the order; else the step starts.
     */
    private void dryAgain() {
        dryHold = null;
        List<StepNeeds> rest = needsFromHere();
        Reservations rs = reservationsFromHere(rest);
        pushReserve(rs);
        DryRun.Shortage dry = dryRun(rest);
        if (dry != null && replanner != null) { failOrReplan(dry.text() + " (looked again a tick later)", new FactChange.Fact(dry.key(), dry.text())); return; }
        begin(steps.get(idx), rs, dry);
    }

    /** 0.39.z62 item 3a: the shortage the running step waits one tick on, null: none. */
    private DryRun.Shortage dryHold;

    /** The step's start after the dry run: the tool check, the hands' start, the start line. */
    private void begin(Step s, Reservations rs, DryRun.Shortage dry) {
        String r;
        String dryNote = null;
        if (dry != null) dryNote = "dry run: " + dry.text() + " (no order behind the run: not re-planned)";
        // 0.39.z34 (S39): the step's tool must be in the bag before it starts; missing, the bag fact re-plans the order
        String lack = toolLack(s);
        if (lack != null) { failOrReplan(lack); return; }
        try {
            r = hands.start(s);
        } catch (RuntimeException e) {
            r = "error: " + e;
        }
        if (r != null) {
            ended("refused at its start");       // 0.39.z56 item 2a: the hands may have taken the step before they refused it
            failOrReplan(r);
        } else {
            why = started(idx, steps) + (needs.none() ? "" : " " + needs.text()) + (replanFact == null ? "" : " (re-planned: " + replanFact + ")") + (keepErr == null ? "" : " (" + keepErr + ")")
                    + (rs.unknown() == null ? "" : " (reserves not loaded: " + rs.unknown() + ")") + (dryNote == null ? "" : " (" + dryNote + ")");
            replanFact = null;
        }
    }

    /**
     * 0.39.z61: the dry run of the steps from here on the bag now; null: no shortage, or these hands list no bag. 0.39.z62
     * item 3: the running step itself is left to its own checks (DryRun.look judgeFirst false); {@link #lastDry} holds the
     * shortage or where the walk stopped / what it skipped (aa why).
     */
    private DryRun.Shortage dryRun(List<StepNeeds> rest) {
        java.util.Map<String, Integer> items;
        try {
            StepNeeds.Bag bag = hands.bag();
            items = bag == null ? null : bag.items();
        } catch (RuntimeException e) {
            keepErr = (keepErr == null ? "" : keepErr + "; ") + "the dry run could not read the bag: " + e;
            lastDry = "not checked: the bag could not be read";
            return null;
        }
        DryRun.Outcome o = DryRun.look(steps, idx, rest, items, false);
        lastDry = o.shortage() != null ? o.shortage().text() : o.note();
        return o.shortage();
    }

    /** The step's declared needs and the tool the bag holds for it now (a failure to read them is said in the needs). */
    private void lookNeeds(Step s) {
        hadTool = null;
        hadUses = io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN;
        lastBagLook = now;
        try {
            needs = hands.needs(s);
            if (needs == null) needs = StepNeeds.NONE;
            needs = needs.withUses(usesAt(idx));             // 0.39.z61: the plan's own counts for this step
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
        Reservations held = table;
        String released = release();             // 0.39.z61: the new price sees the bag without the old chain's claims
        Routine r;
        try {
            r = replanner.replan(at, f, f.text());
        } catch (RuntimeException e) {
            pushReserve(held);
            why = stage() + ": the re-price failed: " + e + " (the run goes on as planned)";
            return false;
        }
        List<Step> left = steps.subList(idx, steps.size());
        if (r == null || r.steps() == null || r.steps().isEmpty() || r.steps().equals(left)) {
            pushReserve(held);                   // the run goes on as planned: its table back
            if (r != null && r.steps() == null && r.why() != null && !running) why = stage() + ": " + r.why();
            return false;
        }
        // 0.39.z48 (S66): a running step is never cut for another way; the one step whose end IS the paths' answer - the
        // explore while paths compute - ends only for a priced way (a new pick that waits on paths again runs on as it is)
        if (running && waitsOnPaths(r.steps())) { pushReserve(held); return false; }
        if (running) safeCancel("re-priced: " + f.text() + " - the pick changed");
        splice(idx, r.steps(), r.uses(), steps.size());
        replanFact = "re-priced: " + f.text() + " - switched to " + r.steps().get(0).text() + " first" + (released.isEmpty() ? "" : "; " + released);
        idx--;
        next();
        return true;
    }

    /** 0.39.z48: the steps hold an explore while the chain's paths compute (the pick still waits on a walk's price). */
    public static boolean waitsOnPaths(List<Step> s) {
        for (Step x : s) if (x instanceof Step.Explore e && e.whilePaths()) return true;
        return false;
    }

    /**
     * 0.39.z58 item 1 (D3 pricing queue; run Q 02:29:32: the crafting walks' searches queued behind re-searches): the walk of
     * the step that runs now or runs next - the first {@link Step.Walk} or {@link Step.ToStrip} from {@code idx} on, as x y z
     * (the cell its price and its walk use); null when none is left or nothing runs (idx < 0). The fork's queue gives that
     * goal's search the first slot; which way wins stays the scorer's (rule 1).
     */
    /**
     * 0.39.z58 review 2: one number per {@link #start} - each run started (an order's chain, the next order in a list, an
     * order planned again from scratch, aa on with kept goals) is a new one; the mod tells the fork orderStarted on a change.
     */
    private long runId;

    public long runId() { return runId; }

    public static int[] walkAhead(List<? extends Step> steps, int idx) {
        if (steps == null || idx < 0) return null;
        for (int i = idx; i < steps.size(); i++) {
            if (steps.get(i) instanceof Step.Walk w) return new int[]{w.x(), w.y(), w.z()};
            if (steps.get(i) instanceof Step.ToStation w) return new int[]{w.x(), w.y(), w.z()};     // 0.39.z65 item 1
            if (steps.get(i) instanceof Step.ToStrip t) return new int[]{t.x(), t.y(), t.z()};
        }
        return null;
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
            if (fact == null) fact = walkLeads(steps, idx, reason);
        }
        if (fact == null || replanner == null || idx < 0 || idx >= steps.size()) { fail(reason); return; }
        Step failed = steps.get(idx);
        String clean = reason.replaceFirst("^error: ", "");
        if (sameAgain(failed, replannedStep, fact.key(), replannedKey)) {
            fail("re-planned after \"" + replannedWhy + "\" and the same step failed again: " + clean);
            return;
        }
        String released = release();             // 0.39.z61 (D1 3.4): the re-plan sees the bag without the failed chain's claims
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
        replannedKey = fact.key();
        replanFact = fact.text() + (released.isEmpty() ? "" : "; " + released.replace("the old chain", "the failed chain"));
        splice(idx, r.steps(), r.uses(), steps.size());
        idx--;
        next();
    }

    /**
     * The step that failed is the one the last re-plan answered: the run ends with both reasons. 0.39.z67 item 1 (run Y
     * 09:26:14): a place-near whose fact names another cell ({@link io.github.mojolowjo.entropybot.aa.NeedChain#placeKey}:
     * placed near a cell with room after failing where the bot stood) is a new fact, re-planned again - bounded by the
     * order's replan.maxSourceDrops and the cells with room held one by one. Pure.
     */
    public static boolean sameAgain(Step failed, String lastStep, String key, String lastKey) {
        if (!failed.text().equals(lastStep)) return false;
        if (failed instanceof Step.PlaceNear && key != null && key.startsWith("source " + io.github.mojolowjo.entropybot.aa.NeedChain.PLACE_AT)) return key.equals(lastKey);
        return true;
    }

    /**
     * 0.39.z65 item 1 (run V 07:15:22, rule 7): a failed {@link Step.Walk} with no fact of its own whose next step has a
     * source ({@link io.github.mojolowjo.entropybot.aa.NeedChain#sourceKey}: a station take, a tree, a collect) is that
     * source's fact - the walk led there and could not get there, so the order re-plans without it instead of ending.
     * Null: not a walk, or nothing after it names a source (the run ends with the reason as before). Pure.
     */
    public static FactChange.Fact walkLeads(List<? extends Step> steps, int idx, String reason) {
        if (steps == null || idx < 0 || idx + 1 >= steps.size() || !(steps.get(idx) instanceof Step.Walk)) return null;
        String src = io.github.mojolowjo.entropybot.aa.NeedChain.sourceKey(steps.get(idx + 1));
        return src == null ? null : FactChange.walkFor(src, reason.replaceFirst("^error: ", ""));
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
            List<java.util.Map<String, Integer>> partUses = null;
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
                partUses = r.uses();                 // 0.39.z61: the chain's own counts come with it
            }
            splice(idx, parts, partUses, idx + 1);
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
        if (dryHold != null) return;             // 0.39.z62 item 3a: not started yet - the next tick looks again, then starts it
        String r;
        try {
            r = hands.start(steps.get(idx));
        } catch (RuntimeException e) {
            r = "error: " + e;
        }
        if (r != null) {
            ended("refused at its restart");     // 0.39.z56 item 2a
            fail("after the pause: " + r.replaceFirst("^error: ", ""));
        }
        else why = stage() + " resumed";
    }

    public boolean paused() { return paused; }

    /** One tick: follows the running step. Returns the phase after it. */
    public Phase tick(long tick) {
        now = tick;
        if (phase != Phase.RUNNING || paused) return phase;
        if (dryHold != null) { dryAgain(); return phase; }     // 0.39.z62 item 3a: the held step's second look
        Step s = steps.get(idx);
        // 0.39.z58 (the first-answer wait): a landed answer re-prices the explore while paths compute BEFORE its status is read,
        // so a priced way ends it where it stands instead of its explore starting first and being cancelled a tick later
        if (priceAsk != null && s instanceof Step.Explore ex0 && ex0.whilePaths() && reprice(true)) return phase;
        Car.Status st;
        try {
            st = hands.status(s);
        } catch (RuntimeException e) {
            st = Car.Status.failed("error: " + e);
        }
        if (st == null) st = Car.Status.failed("the hands gave no status");
        switch (st.state()) {
            case FAILED -> {
                ended("failed");                 // 0.39.z56 item 2a (run O: a failed walk step kept its probe pointer)
                failOrReplan(st.why() == null ? "the step ended failed without a reason from its hands" : st.why());
            }
            case DONE -> {
                ended("done");                   // 0.39.z56 item 2a
                lastStatus = st.why();
                next();
            }
            default -> {
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
        ended("cancelled: " + reason);           // 0.39.z56 item 2a: pause, timeout, tool gone, no progress, re-price, stop
    }

    /** Back to IDLE after its end was read. */
    public void clear() {
        if (phase == Phase.RUNNING) cancel("cleared");
        phase = Phase.IDLE;
        paused = false;
        steps = List.of();
        uses = List.of();
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
