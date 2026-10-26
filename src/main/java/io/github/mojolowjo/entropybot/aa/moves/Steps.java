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
 * the orders' reserves (release before repair, said in the re-plan line).
 * <p>0.39.z70 (design D2 3.1, Job A): steps are compared by {@link StepId}, never by their text; a failure parks the run only
 * when its {@link Attempt} equals the last failed one (the honest loop), and every switch logs its {@link PlanDiff}.
 * 0.39.z71 (D2 3.2, Job B): while a step runs, {@link StepMonitor} looks at its {@link StepSignals} every monitor.everyTicks:
 * a stall or "Baritone found no path" re-plans on a typed fact, a done fact seen while the hands still run completes it.
 * Loader notes: none (the fork's
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
         * 0.39.z75 (run AI 20:11:05-20:14:16: three explores "no progress 30 s ... Baritone executing", farthest 0): the facts
         * of a frozen walk or explore at the monitor's no-progress verdict - Baritone's process in control and its state, the
         * path's length and the index on it, the goal, the feet, in fluid, ticks since the last move; null: these hands
         * cannot read them. Written as a probe line behind debug.stepProbe.
         */
        default String stallFacts(Step s) { return null; }

        /** 0.39.z75 review 2: the monitor ended this running explore for no progress ({@code why}); its target is held by position. */
        default void exploreFrozen(Step s, String why) {}

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

        /**
         * 0.39.z70 (design D2 3.1): the feet cell now ({x, y, z}), read when a step starts - the start cell of its
         * {@link Attempt}; null: not known (these hands, or no player). Rule 15: an unknown cell is never "the same place".
         */
        default int[] feet() { return null; }

        /** 0.39.z70: a decision line for the log (the re-plan's plan diff); these hands may drop it (tests). */
        default void log(String line) {}

        /**
         * 0.39.z71 (design D2 3.2): what the game shows about the running step now, for {@link StepMonitor}; null: these
         * hands have no signals - the monitor is off for this run (fake hands, as before).
         */
        default StepSignals signals(Step s) { return null; }

        /** 0.39.z71: a {@code probe monitor:} line (debug.monitorProbe 1); these hands may drop it. */
        default void probe(String line) {}
    }

    /**
     * 0.39.z70 (design D2 3.1, Job A): one failed try of a step - its {@link StepId}, the key of the fact it failed on, the
     * feet cell where it started and the bag counts of what it needs (its {@link StepNeeds} consumes, counted slots and tool
     * tier, sorted; read when it failed). Two equal tries mean the planner would be asked the same question again: the run
     * parks (honest loop). Rule 15: a null cell or bag is never "the same" ({@link #sameAs}).
     */
    public record Attempt(StepId id, String factKey, List<Integer> feet, java.util.SortedMap<String, Integer> needs) {
        public Attempt {
            feet = feet == null ? null : List.copyOf(feet);
            needs = needs == null ? null : java.util.Collections.unmodifiableSortedMap(new java.util.TreeMap<>(needs));
        }

        /** "x y z" of the start cell, null: not known. */
        public String startCell() {
            if (feet == null) return null;
            List<String> c = new java.util.ArrayList<>();
            for (Integer i : feet) c.add(String.valueOf(i));
            return String.join(" ", c);
        }

        /** "cobblestone 0, pickaxe tier 1" (sorted), "" when the step needs nothing, null: the bag was not readable. */
        public String needsBag() {
            if (needs == null) return null;
            List<String> p = new java.util.ArrayList<>();
            for (var e : needs.entrySet()) p.add(StepNeeds.sh(e.getKey()) + " " + e.getValue());
            return String.join(", ", p);
        }

        /** The same step, the same fact, from the same cell, with the same needs in the bag; any unknown part: false. */
        public boolean sameAs(Attempt o) {
            return o != null && id != null && id.equals(o.id) && factKey != null && factKey.equals(o.factKey)
                    && feet != null && feet.equals(o.feet) && needs != null && needs.equals(o.needs);
        }
    }

    /** The key of the tool tier in {@link Attempt#needs} ("pickaxe tier"). */
    static String tierKey(String kind) { return kind + " tier"; }

    /**
     * 0.39.z70 (pure): the bag counts of what {@code n} needs: each plain consume by its id, each counted slot (a tag, an
     * "a|b" word) as the sum of its members, the tool kind's best tier. Null: no bag (rule 15).
     */
    public static java.util.SortedMap<String, Integer> needsBag(StepNeeds n, StepNeeds.Bag bag) {
        if (bag == null) return null;
        java.util.SortedMap<String, Integer> out = new java.util.TreeMap<>();
        if (n == null) return out;
        for (String id : n.consumes().keySet()) out.put(id, bag.count(id));
        for (String w : n.tagUses().keySet()) {
            int have = 0;
            for (String m : io.github.mojolowjo.entropybot.aa.NeedChain.membersOf(w)) have += bag.count(StepNeeds.full(m));
            out.put(w, have);
        }
        if (n.hasTool()) out.put(tierKey(n.toolKind()), bag.bestTier(n.toolKind()));
        return out;
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

        /**
         * 0.39.z70 (D2 3.1): the order's re-plan count and dropped sources for the park line ("re-plans this order: 2;
         * dropped: strip ..."); null: not known (the run's own count is said then).
         */
        default String orderState() { return null; }

        /** 0.39.z70: the run switched to new steps ({@code reprice}: a re-price, else a failure re-plan); the order counts it. */
        default void switched(PlanDiff d, boolean reprice) {}

        /** 0.39.z70 (owner's ruling R1 = c): the run parks on an honest loop at {@code a}; the order keeps it for one more try. */
        default void parked(Attempt a) {}

        /**
         * 0.39.z73 (design D2 3.4): the re-plan of a failed step with what was left of the run ({@code left}: the failed step
         * first, its plan counts, the bag's stacks), so the order may price a repair (gap + kept tail) against the full
         * re-plan. Default: the plain re-plan.
         */
        default Routine replan(Step failed, FactChange.Fact fact, String why, Remaining left) { return replan(failed, fact, why); }

        /**
         * 0.39.z70: {@code planner}'s answers, with {@code before} run first on every re-plan (an adapter that charges the fact);
         * 0.39.z73: both re-plan forms go to {@code planner}.
         */
        static Replanner around(Replanner planner, Before before) {
            return new Replanner() {
                @Override public Routine replan(Step failed, FactChange.Fact fact, String why) {
                    before.run(failed, fact, why);
                    return planner.replan(failed, fact, why);
                }
                @Override public Routine replan(Step failed, FactChange.Fact fact, String why, Remaining left) {
                    before.run(failed, fact, why);
                    return planner.replan(failed, fact, why, left);
                }
                @Override public String orderState() { return planner.orderState(); }
                @Override public void switched(PlanDiff d, boolean reprice) { planner.switched(d, reprice); }
                @Override public void parked(Attempt a) { planner.parked(a); }
            };
        }

        /** What an adapter does before each re-plan (charge the fact, refresh the facts). */
        interface Before {
            void run(Step failed, FactChange.Fact fact, String why);
        }
    }

    /**
     * 0.39.z73 (D2 3.4): what was left of the run when a step failed - the steps from it on, their plan counts (parallel; null
     * entries: none), the bag's stacks (item id -> count; null: not readable).
     */
    public record Remaining(List<Step> steps, List<java.util.Map<String, Integer>> uses, java.util.Map<String, Integer> bag) {
        public Remaining {
            steps = List.copyOf(steps);
            uses = uses == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(uses));
        }
    }

    private Replanner replanner;
    /**
     * 0.39.z70 (D2 3.1, replaces z30's "the same step failed again" by the step's text): the last failed try the run
     * re-planned, cleared when a later step ends DONE (progress was made); null: none.
     */
    private Attempt lastFailed;
    /** 0.39.z70: the running step's start cell (Hands.feet at its start), null: not known. */
    private int[] startFeet;
    /** 0.39.z70: re-plans on a failure in this run, switches (re-plans and re-prices) in this run. */
    private int replans, switches;
    /** 0.39.z70: the source facts this run re-planned on (the park line when the order says nothing). */
    private final java.util.LinkedHashSet<String> droppedHere = new java.util.LinkedHashSet<>();


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
        lastFailed = null;
        startFeet = null;
        replans = 0;
        switches = 0;
        droppedHere.clear();
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
        if (skipAhead()) return;                 // 0.39.z73 (D2 3.4): a later suffix already possible from the bag
        Step s = steps.get(idx);
        monitorOn = false;                       // 0.39.z71: on again at the step's start (begin)
        FactChange.clear();
        lookNeeds(s);
        startFeet = feetNow();                   // 0.39.z70: the Attempt's start cell
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

    /** 0.39.z73: the run from the failed step on, its plan counts and the bag's stacks (null: not readable). */
    private Remaining remaining() {
        java.util.Map<String, Integer> bag;
        try {
            StepNeeds.Bag b = hands.bag();
            bag = b == null ? null : b.items();
        } catch (RuntimeException e) {
            bag = null;                          // rule 15: the repair's dry run says "not readable", the full re-plan runs
        }
        return new Remaining(steps.subList(idx, steps.size()), sized(uses, steps.size()).subList(idx, steps.size()), bag);
    }

    // ---- 0.39.z73 (design D2 3.4, Job D): the kernel check ----

    /** The skipped steps' words for the next start line, null: none. */
    private String skipNote;

    /**
     * The kernel check at the step boundary (and before a re-plan): the latest later step whose suffix the bag already makes
     * possible, with what the skipped steps were for kept ({@link Kernels#latest}); each skipped step said. True: the run went
     * on from there (that step started, or the run ended).
     */
    private boolean skipAhead() {
        Kernels.Skip k;
        try {
            k = Kernels.latest(steps, idx, needsFromHere(), hands.bag());
        } catch (RuntimeException e) {
            why = stage() + ": the kernel check could not be read (no skip): " + e;
            return false;
        }
        if (k == null) return false;
        if (!k.skips()) {                        // 0.39.z73 review 2: said in the step's start line
            skipNote = "no skip check: " + k.noCheck();
            return false;
        }
        List<String> said = new java.util.ArrayList<>();
        for (int i = 0; i < k.said().size(); i++) said.add("step " + (idx + i + 1) + "/" + steps.size() + " " + k.said().get(i));
        for (String l : said) hands.log(l);
        skipNote = "skipped steps " + (idx + 1) + (k.to() - idx > 1 ? "-" + k.to() : "") + ": " + String.join("; ", k.said());
        idx = k.to() - 1;
        next();
        return true;
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
    /** 0.39.z75: the bag's count at the step's start of each word the step gets or makes (null: not readable or none). */
    private java.util.Map<String, Integer> madeAtStart;

    /** 0.39.z75: the bag's counts of the step's words now (null: no count, or the bag cannot be listed). */
    private java.util.Map<String, Integer> madeNow(Step s) {
        java.util.Map<String, Integer> w = StepsShort.wants(s);
        if (w == null) return null;
        try {
            StepNeeds.Bag b = hands.bag();
            return StepsShort.counts(w, b == null ? null : b.items());
        } catch (RuntimeException e) {
            return null;                         // rule 15: not readable - the short fact is not said (the failure stands as it was)
        }
    }

    /**
     * 0.39.z75 (rule 7): a step that ended short of its count, with no fact of its own: the typed fact "short by N: reason"
     * in the bag slot of what it was for (StepsShort) - never a plain stop. Null: not short, or not countable.
     */
    private FactChange.Fact shortFact(Step s, String reason) {
        int[] feet;
        try {
            feet = hands.feet();
        } catch (RuntimeException e) {
            feet = null;                         // rule 15: the feet not known - the bag slot, no position held
        }
        return StepsShort.of(s, StepsShort.wants(s), madeAtStart, madeNow(s), feet, reason);
    }

    private void begin(Step s, Reservations rs, DryRun.Shortage dry) {
        madeAtStart = madeNow(s);
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
                    + (rs.unknown() == null ? "" : " (reserves not loaded: " + rs.unknown() + ")") + (dryNote == null ? "" : " (" + dryNote + ")")
                    + (skipNote == null ? "" : " (" + skipNote + ")");
            replanFact = null;
            skipNote = null;
            startMonitor(s);
        }
    }

    // ---- 0.39.z71 (design D2 3.2, Job B): the step monitor ----

    private final StepMonitor monitor = new StepMonitor();
    /** The monitor judges the running step (these hands give signals, the kind is not exempt). */
    private boolean monitorOn;
    private long lastLook;
    private boolean notLoadedSaid;
    private StepMonitor.Verdict lastVerdict;

    /** The step started (or started again): the monitor's bests from its signals now; no signals: off for this step. */
    private void startMonitor(Step s) {
        StepSignals sig = signalsNow(s);
        monitorOn = sig != null && StepMonitor.kind(s) != StepMonitor.Kind.EXEMPT;
        monitor.begin(s, sig, now);
        lastLook = now;
        notLoadedSaid = false;
        lastVerdict = null;
    }

    private StepSignals signalsNow(Step s) {
        try {
            return hands.signals(s);
        } catch (RuntimeException e) {
            return StepSignals.notLoaded("the signals could not be read: " + e);    // rule 15: said as not loaded, never a stall
        }
    }

    /**
     * One look of the monitor (every monitor.everyTicks while the step runs): CONTINUE - nothing (a not-loaded reason said
     * once per step); COMPLETE - the hands are cancelled and the next step starts; REPLAN - the hands are cancelled and the
     * typed fact goes to {@link #failOrReplan} (the honest-loop Attempt rule applies). Each REPLAN / COMPLETE is logged.
     */
    private void monitorLook(Step s) {
        lastLook = now;
        StepSignals sig = signalsNow(s);
        if (sig == null) sig = StepSignals.notLoaded("these hands gave no signals this look");
        StepMonitor.Look l = monitor.look(sig, now);
        if (Tuning.get("debug.monitorProbe") >= 1 && (l.v() != lastVerdict || monitor.stalled() || !sig.loaded())) {
            try {
                hands.probe(monitor.probeLine(now, sig, l));
            } catch (RuntimeException e) {
                why = stage() + ": the monitor probe line could not be written: " + e;
            }
        }
        lastVerdict = l.v();
        switch (l.v()) {
            case CONTINUE -> {
                if (!sig.loaded() && !notLoadedSaid) {
                    notLoadedSaid = true;
                    hands.log(stage() + ": monitor: " + l.reason());
                }
            }
            case COMPLETE -> {
                hands.log(stage() + ": " + l.reason());
                safeCancel(l.reason());
                lastFailed = null;               // a step ended done (0.39.z70: progress was made)
                lastStatus = l.reason();
                next();
            }
            case REPLAN -> {
                hands.log(stage() + ": monitor: " + l.reason());
                if (Tuning.get("debug.stepProbe") >= 1 && (s instanceof Step.Explore || s instanceof Step.Walk || s instanceof Step.ToStation
                        || s instanceof Step.ToStrip || s instanceof Step.ToBand || s instanceof Step.ToSurface)) {
                    try {                                // 0.39.z75: the frozen walk's facts, one line
                        String f = hands.stallFacts(s);
                        hands.probe("probe stall: " + stage() + " | " + (f == null ? "no stall facts in these hands" : f));
                    } catch (RuntimeException e) {
                        why = stage() + ": the stall facts could not be read: " + e;
                    }
                }
                if (s instanceof Step.Explore && l.reason() != null && l.reason().startsWith("no progress")) {
                    try {
                        hands.exploreFrozen(s, l.reason());
                    } catch (RuntimeException e) {
                        why = stage() + ": the frozen explore's target could not be held: " + e;
                    }
                }
                safeCancel("monitor: " + l.reason());
                failOrReplan(l.reason(), l.fact());
            }
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
        // 0.39.z70 (D2 3.1, design 1.8): the same steps by identity (a record's array field compares by reference)
        if (r == null || r.steps() == null || r.steps().isEmpty() || StepId.same(r.steps(), left)) {
            pushReserve(held);                   // the run goes on as planned: its table back
            if (r != null && r.steps() == null && r.why() != null && !running) why = stage() + ": " + r.why();
            return false;
        }
        // 0.39.z48 (S66): a running step is never cut for another way; the one step whose end IS the paths' answer - the
        // explore while paths compute - ends only for a priced way (a new pick that waits on paths again runs on as it is)
        if (running && waitsOnPaths(r.steps())) { pushReserve(held); return false; }
        if (running) safeCancel("re-priced: " + f.text() + " - the pick changed");
        String diff = planDiff(left, r.steps(), true);
        splice(idx, r.steps(), r.uses(), steps.size());
        replanFact = "re-priced: " + f.text() + " - switched to " + r.steps().get(0).text() + " first" + (released.isEmpty() ? "" : "; " + released) + "; " + diff;
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
            if (steps.get(i) instanceof Step.ToSurface w) return new int[]{w.x(), w.y(), w.z()};     // 0.39.z68
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
     * {@link Replanner} for the steps from here on the facts of now and goes on with them; no fact, no replanner or no way
     * now: the run ends FAILED with the reason. 0.39.z70 (D2 3.1): a try equal to the last failed one ({@link Attempt}) parks
     * the run with the honest-loop words instead.
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
            if (fact == null) fact = shortFact(s, reason);       // 0.39.z75: a step that ended short of its count
            if (fact == null) fact = walkLeads(steps, idx, reason);
        }
        if (fact == null || replanner == null || idx < 0 || idx >= steps.size()) { fail(reason); return; }
        Step failed = steps.get(idx);
        String clean = reason.replaceFirst("^error: ", "");
        // 0.39.z73 (D2 3.4): before the planner is asked, the kernel check - what the failed step was for may be in the bag now
        String before = why;
        if (skipAhead()) {
            hands.log("step " + (failed == null ? "?" : failed.text()) + " failed (" + clean + "), but a later step is possible from the bag: skipped ahead");
            return;
        }
        why = before;
        // 0.39.z70 (D2 3.1, run M P1 / run N P3): park only when this try equals the last failed one - the same step, the same
        // fact, from the same cell, with the same needs in the bag: the planner's inputs are the same, a re-plan would loop.
        // Anything else re-plans (the order's caps bound it, Goals.replan). Rule 15: an unknown cell or bag is not "the same".
        Attempt now = attempt(failed, fact.key());
        if (now.sameAs(lastFailed)) {
            String park = "the same way failed twice from the same place with the same bag: " + failed.text() + " - " + fact.text() + " (" + orderState() + ")";
            try {
                replanner.parked(now);
            } catch (RuntimeException e) {
                park = park + " (the order could not keep the park's facts: " + e + ")";
            }
            fail(park);
            return;
        }
        // 0.39.z70: a run never re-plans more often than an order may (both caps), so a planner with no cap of its own cannot loop
        int runCap = Tuning.i("replan.maxPerOrder") + Tuning.i("replan.maxSourceDrops");
        if (replans >= runCap) {
            fail(clean + " (not re-planned: " + replans + " re-plans in this run already, replan.maxPerOrder + replan.maxSourceDrops " + runCap + ")");
            return;
        }
        String released = release();             // 0.39.z61 (D1 3.4): the re-plan sees the bag without the failed chain's claims
        Routine r;
        try {
            r = replanner.replan(failed, fact, clean, remaining());     // 0.39.z73: what was left, for a priced repair
        } catch (RuntimeException e) {
            r = new Routine(null, "the re-plan: " + e);
        }
        if (r == null || r.steps() == null || r.steps().isEmpty()) {
            fail(reason + (r == null || r.why() == null ? "" : " (re-plan: " + r.why() + ")"));
            return;
        }
        lastFailed = now;
        replans++;
        if (fact.key().startsWith("source ")) droppedHere.add(fact.key().substring("source ".length()));
        String diff = planDiff(steps.subList(idx, steps.size()), r.steps(), false);
        replanFact = fact.text() + (released.isEmpty() ? "" : "; " + released.replace("the old chain", "the failed chain")) + "; " + diff;
        splice(idx, r.steps(), r.uses(), steps.size());
        idx--;
        next();
    }

    /**
     * 0.39.z70 (D2 3.1): the try of {@code failed} on the fact keyed {@code key}: its identity, the cell it started from and
     * the bag counts of its needs now (z67's place-near "failed at another cell" is a fact key of its own, so it is covered).
     */
    private Attempt attempt(Step failed, String key) {
        java.util.SortedMap<String, Integer> bag;
        try {
            bag = needsBag(needs, hands.bag());
        } catch (RuntimeException e) {
            bag = null;                          // rule 15: not readable is never "the same bag" (this try re-plans)
            why = stage() + ": the bag could not be read for the try's facts: " + e;
        }
        List<Integer> feet = startFeet == null || startFeet.length != 3 ? null : List.of(startFeet[0], startFeet[1], startFeet[2]);   // structural: x y z
        return new Attempt(StepId.of(failed), key, feet, bag);
    }

    /** The feet from the hands now (null: not known; a failure to read them is not known either, said in the line). */
    private int[] feetNow() {
        try {
            int[] f = hands.feet();
            return f == null ? null : f.clone();
        } catch (RuntimeException e) {
            keepErr = "the feet could not be read: " + e;
            return null;
        }
    }

    /** The order's count and dropped sources for the park line; the run's own when the order says nothing. */
    private String orderState() {
        String s, err = "";
        try {
            s = replanner.orderState();
        } catch (RuntimeException e) {
            s = null;
            err = " (the order's count could not be read: " + e + ")";
        }
        if (s != null) return s;
        return "re-plans this run: " + replans + "; dropped: " + (droppedHere.isEmpty() ? "none" : String.join(", ", droppedHere)) + err;
    }

    /**
     * 0.39.z70 (D2 3.1 stability): the switch's plan diff, logged through the hands and counted by the order; the words
     * for the step's start line.
     */
    private String planDiff(List<Step> old, List<Step> neu, boolean reprice) {
        PlanDiff d = PlanDiff.of(old, neu);
        switches++;
        String line = d.line(switches) + (reprice ? " (re-priced)" : "");
        try {
            hands.log(line);
            replanner.switched(d, reprice);
        } catch (RuntimeException e) {
            line = line + " (not counted: " + e + ")";
        }
        return line;
    }

    /** 0.39.z70: the last failed try this run re-planned (aa why, tests), null: none since the last DONE. */
    public Attempt lastFailed() { return lastFailed; }

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
        startFeet = feetNow();                   // 0.39.z70: the step starts again from here
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
        else {
            why = stage() + " resumed";
            startMonitor(steps.get(idx));        // 0.39.z71: started again from here: its bests start again
        }
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
        // 0.39.z75 metric fix B (run AH 19:46:05-33: "explore until #stone_tool_materials" ended on each stone seen and the
        // re-plan chose the same explore, 14 times in 30 s): an explore until a source is priced again where it runs on a
        // found source (FactChange.found) or a landed path, and ends only when the planner picks other steps - its done-test
        // is the planner's own test of what it found, not a second one
        if (priceAsk != null && s instanceof Step.Explore ex0 && (ex0.whilePaths() || ex0.until() != null) && reprice(true)) return phase;
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
                lastFailed = null;               // 0.39.z70 (D2 3.1): progress was made, the next failure is a new question
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
                } else {
                    if (st.why() != null) why = stage() + ": " + st.why();
                    // 0.39.z71 (D2 3.2): the monitor's look once per monitor.everyTicks (it may end, complete or re-plan the step)
                    if (monitorOn && now - lastLook >= StepMonitor.everyTicks()) monitorLook(s);
                }
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
