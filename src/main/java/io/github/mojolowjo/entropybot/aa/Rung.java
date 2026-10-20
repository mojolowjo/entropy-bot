package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Cooldown;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * AA v0 (AA_PLAN 9): one rung of the basic-needs ladder: unmet? -> may it start (a skip reason)? -> the goal text ->
 * the planner's answer -> feasible? -> the RunJob under a Cooldown. Every "no" is a FAILURE with a readable reason, so
 * the ladder's reason line says why each rung was passed. After a SUCCESS the rung counts as met for {@link #SETTLE_MS}
 * (the sense facts refresh at most once a minute).
 *
 * <p>S0 (MINING_PLAN 10.1): the planner answers in three states ({@link JobPort.Resolved}): NONE is taken at once (no
 * more "found none in 45s" when it had answered), SEARCHING is waited for (at most {@link #PLAN_WAIT_MS}) only when no
 * other option of the rung's {@link Picker} can run. The owner's manual driving ({@link AaManual}: ask/pick, do, skip,
 * use) is checked here before a task starts.
 */
public final class Rung extends Node {
    public static final long PLAN_WAIT_MS = 45_000, SETTLE_MS = 75_000;
    /** S0: a picker with a ready planner-free option still gives the planner this long (it answers in ms as a rule). */
    public static final long PLAN_PEEK_MS = 3_000;
    /** The blackboard fact "what is being planned" (the status line shows it while the slot waits). */
    public static final String PLANNING_FACT = "planning";
    /** S0: the id of the rung that picked last ({@code aa use} applies to it). */
    public static final String CURRENT_RUNG = "aa.currentRung";

    /**
     * 0.36.a8 (MINING_PLAN 3.6): a rung's option scorer. The planner's chain (expanded to its steps; null when it has
     * none) goes in, the chain to run comes out (null: no option can run, {@link #why()} says why; {@link Options#NOTHING}:
     * the rung passes); a failed task is offered back: {@code true} means another option may start at once.
     */
    public interface Picker {
        String pick(Blackboard bb, String planned);

        /** S0: with the planner's cost and, when it has no chain, why not. */
        default String pick(Blackboard bb, String planned, double plannedCost, String plannerWhy) { return pick(bb, planned); }

        boolean failed(Blackboard bb, String end);

        /** aa395: the brain's stuck watchdog halted the running option: charged like a failure (held), no switch at once. */
        default void stalled(Blackboard bb, String why) {}

        /** The options line for {@code aa why}. */
        String why();

        /** The owner chose another ranked option (aa pick): failures are charged to it. */
        default void chose(String command) {}

        /** The ranked options, the chosen one first (for aa ask). */
        default List<AaManual.Row> rows() { return List.of(); }

        /** The chosen option's id (the decision log), or null. */
        default String chosenId() { return null; }

        /** Counters for aa status (null: none). */
        default String counters(long now) { return null; }

        /** A facts supplier failed at or after this time (aa status goes red). */
        default boolean factsFailedSince(long since) { return false; }

        /** AA_ROUND2 1: the chosen option's cost in seconds from the last pick or rescore (NaN: unknown). */
        default double lastCostS() { return Double.NaN; }

        /** AA_ROUND3 2: the chosen option's site (null: unknown). */
        default io.github.mojolowjo.entropybot.aa.brain.Site lastSite() { return null; }

        /** AA_ROUND2 1: re-score from fresh facts without picking (updates {@link #lastCostS}); no-op by default. */
        default void refresh(Blackboard bb) {}

        /** AA_ROUND2 2: why no option can run after the last refresh (null: one can, or not known). */
        default String noOption() { return null; }
    }

    public final TaskKind kind;
    private final Predicate<Blackboard> unmet;
    private final Function<Blackboard, String> skip, goal;
    private final RunJob job;
    private final Cooldown cooldown;
    private final Picker picker;
    private String resolved, planned;
    private double plannedCost;
    /** The task runs for the owner's aa do (a met need is no reason to count its failure as done). */
    private boolean lastForced;
    private long planningSince = -1, settledAt = -1, lastSearchPick = -1;
    private String planFailed;
    private long planFailedAt = -1;
    /** aa-prereq: when the last prerequisite step ended done (the planner's facts lag up to a minute behind the bag). */
    private long prereqDoneAt = -1;
    private String prereqGoal;
    /** aa-trees: the wood option the running prerequisite is (a failure is held against it), its options line, and the memory. */
    private Options.Option woodRan;
    private String woodWhy;
    private final Options.OptionMemory woodMem = new Options.OptionMemory();

    /**
     * @param unmet the need is on (bands live in here)
     * @param skip  why it cannot start now (null = it can), e.g. "no base chests scanned"
     * @param goal  the text to run: "goal torch 16", "deposit", "house main"...
     */
    public Rung(String id, TaskKind kind, Predicate<Blackboard> unmet, Function<Blackboard, String> skip, Function<Blackboard, String> goal) {
        this(id, kind, unmet, skip, goal, null);
    }

    /** With a {@link Picker} choosing how the planner's chain runs (the IRON rung). */
    public Rung(String id, TaskKind kind, Predicate<Blackboard> unmet, Function<Blackboard, String> skip, Function<Blackboard, String> goal, Picker picker) {
        super(id, "rung " + kind);
        this.kind = kind;
        this.unmet = unmet;
        this.skip = skip;
        this.goal = goal;
        this.picker = picker;
        this.job = new RunJob(id + ".job", kind, bb -> resolved == null ? null : JobPort.TaskSpec.of(kind, resolved, "needs"));
        this.cooldown = new Cooldown(id + ".cd", job);
    }

    @Override protected Status run(Blackboard bb) {
        Status s = run0(bb);
        AaManual man = AaManual.of(bb);
        if (s != Status.RUNNING && id.equals(man.doRung)) {
            man.doRung = null;
            bb.port.log("aa do " + kind + " ended: " + (s == Status.FAILURE ? reason : s.name()));
        }
        return s;
    }

    private Status run0(Blackboard bb) {
        long now = bb.clock.now();
        if (job.running()) {
            Status s = cooldown.tick(bb);
            if (s == Status.SUCCESS) { if (isPrereq(resolved)) { prereqDoneAt = now; prereqGoal = settledGoal; } else settledAt = now; }
            if (s == Status.FAILURE) return jobFailed(bb, cooldown.reason());
            return s;
        }
        AaManual man = AaManual.of(bb);
        if (man.skipped(id)) return fail(kind + " skipped: the owner's aa skip");
        if (man.doRung != null && man.doSince >= 0 && now - man.doSince > AaManual.DO_MAX_MS) {
            bb.port.log("aa do " + man.doRung + " dropped: it did not run within " + AaManual.DO_MAX_MS / 60000 + " min");
            man.doRung = null;
        }
        boolean forced = id.equals(man.doRung);
        lastForced = forced;
        if (man.doRung != null && !forced) return fail(kind + " waits: the owner's aa do " + man.doRung.substring(man.doRung.indexOf('.') + 1));
        if (!forced && !unmet.test(bb)) return fail(kind + " met");
        String why = skip.apply(bb);
        if (why != null) return fail(kind + " skipped: " + why);
        String g = goal.apply(bb);
        if (g == null) return fail(kind + " has nothing to run");
        if (!forced && settledAt >= 0 && now - settledAt < SETTLE_MS && g.equals(settledGoal)) return fail(kind + " just done (" + g + "), waiting for the facts");
        settledAt = -1;
        if (forced && (cooldown.cooling(now) || planFailed != null)) {
            cooldown.forget();
            planFailed = null;
        }
        if (cooldown.cooling(now)) {
            cooldown.tick(bb);                  // answers the cooldown reason without touching the job
            return fail(cooldown.reason());
        }
        // aa385: outside the areas every plan names a spot outside (infeasible); the return leg comes first (RunJob puts
        // it in, costed and logged), a plan failed out there is forgotten, and this rung plans again from inside
        if (io.github.mojolowjo.entropybot.aa.core.WayBack.leg(bb, g, "needs") != null) {
            clearPlanning(bb);
            planFailed = null;
            resolved = g;
            settledGoal = g;
            bb.pendingReason("needs: " + kind + " unmet -> " + g + " [outside my areas: the return leg first]");
            Status s = cooldown.tick(bb);
            if (s == Status.SUCCESS) settledAt = now;
            return s == Status.FAILURE ? fail(cooldown.reason()) : s;
        }
        if (planFailed != null && now - planFailedAt < Cooldown.BASE_MS) return fail(kind + " skipped: " + planFailed);
        planFailed = null;
        // review: an ask already waits for this rung: no new pick per tick, the frozen rows decide
        if (man.ask && man.pending != null && man.pending.rung().equals(id)) {
            String go = man.gate(id, man.pending.rows(), now, bb.port::log);
            if (go == null) return Status.RUNNING;
            if (picker != null) picker.chose(go);
            resolved = go;
            settledGoal = g;
            bb.pendingReason("needs: " + kind + " unmet -> " + g + " as " + go + " [the owner's ask]");
            Status s = cooldown.tick(bb);
            if (s == Status.SUCCESS) settledAt = now;
            return s == Status.FAILURE ? jobFailed(bb, cooldown.reason()) : s;
        }
        JobPort.Resolved r = bb.port.resolveState(g);
        boolean searching = r.state() == JobPort.PlanState.SEARCHING;
        String chain = r.chain(), plannerWhy = null;
        if (r.state() == JobPort.PlanState.NONE) plannerWhy = "no plan for " + g + ": " + r.why();
        if (chain != null) {
            String inf = bb.port.infeasible(chain);
            if (inf != null) {
                // live 2026-10-18: the verdict can disagree with the planner's saved routine (a fetch of an item that is
                // in the bag); checked every tick it spun forever, so it is remembered like a failed plan and logged once
                plannerWhy = inf;
                chain = null;
                if (picker == null) bb.port.log(kind + " " + r.chain() + " skipped " + Cooldown.BASE_MS / 60000 + " min: " + inf);
            }
        }
        // aa-prereq (aa391: torches 60 / table 54 outranked idle, yet "no plan: missing 1 logs - no trees in my areas"
        // skipped them for 2 min and idle.stock_coal ran): the missing wood is a priced prerequisite step, not a skip.
        // The cut walks to trees in my areas; none there -> the woodArea rung makes an area at the nearest tree.
        String pre = r.state() == JobPort.PlanState.NONE && chain == null ? woodPrereq(r.why()) : null;
        // aa-trees: the wood prerequisite is priced over the trees seen (extend the area / chop at); nothing runnable is
        // an option drop with every reason (held per option 10 min in woodMem), never a failed task looping
        boolean woodRunning = isPrereq(resolved) && g.equals(settledGoal);      // a running wood prerequisite keeps its option: no re-choice per tick
        if (!woodRunning) woodRan = null;
        if (pre != null && woodRunning && woodRan != null) pre = resolved;
        else if (pre != null && bb.fact(WoodOptions.FACTS) instanceof java.util.function.Supplier<?> sup) {      // picker rungs too (TOOLS, IRON...): missing logs are this prerequisite, not a switchable end
            WoodOptions.WoodFacts wf = null;
            try {
                if (sup.get() instanceof WoodOptions.WoodFacts x) wf = x;
            } catch (RuntimeException e) {
                bb.port.log(kind + " wood facts: " + e);
            }
            if (wf != null) {
                woodMem.sweep(bb.in);
                Options.Choice wc = WoodOptions.choose(new WoodOptions.WoodFacts(Integer.parseInt(pre.substring(PREREQ_CUT.length()).trim()), wf.trees(), wf.areasMade(), wf.outsideLogs()), woodMem, now);
                if (wc.best() == null) return planFailed(bb, now, "no wood option (" + wc.why() + ")", false);
                woodRan = wc.best();
                woodWhy = wc.why();
                pre = woodRan.command();
            }
        }
        if (picker != null && woodRan == null) pre = null;          // no wood facts: the picker as before
        if (pre != null && prereqDoneAt >= 0 && now - prereqDoneAt < SETTLE_MS && g.equals(prereqGoal))
            return fail(kind + " prerequisite done (" + resolved + "), waiting for the facts before " + g);
        if (pre != null) {
            clearPlanning(bb);
            if (!pre.equals(resolved) || !g.equals(settledGoal))
                bb.port.log(kind + " prerequisite for " + g + ": " + r.why() + " -> " + pre + " first (~" + prereqSeconds(pre) + " s), then " + g + (woodRan == null ? "" : " [wood options: " + woodWhy + "]"));
            resolved = pre;
            settledGoal = g;
            bb.pendingReason("needs: " + kind + " unmet -> " + g + " [prerequisite: " + pre + " ~" + prereqSeconds(pre) + " s]");
            Status s = cooldown.tick(bb);
            if (s == Status.SUCCESS) { prereqDoneAt = now; prereqGoal = g; }
            return s == Status.FAILURE ? jobFailed(bb, cooldown.reason()) : s;
        }
        if (picker == null) {
            if (searching) return waitPlan(bb, g, now);
            if (chain == null) return planFailed(bb, now, plannerWhy, r.state() == JobPort.PlanState.NONE);
            resolved = chain;
        } else {
            // the planner still searches: give it PLAN_PEEK_MS, then the best planner-free option may run; re-picked at
            // most every PLAN_PEEK_MS while it searches (the facts are read once per pick, never per tick)
            if (searching) {
                if (planningSince < 0 || !g.equals(planningGoal) || now - planningSince < PLAN_PEEK_MS || (lastSearchPick >= 0 && now - lastSearchPick < PLAN_PEEK_MS))
                    return waitPlan(bb, g, now);
                lastSearchPick = now;
            }
            planned = chain == null ? null : bb.port.expand(chain);
            bb.fact(CURRENT_RUNG, id);
            plannedCost = r.cost();
            String p = picker.pick(bb, planned, plannedCost, searching ? "still searching" : plannerWhy);
            if (p == null && searching) return waitPlan(bb, g, now);
            if (p == null) {
                // nothing the scorer can run: remembered like a failed plan (else it would ask every tick)
                return planFailed(bb, now, picker.why() + (plannerWhy == null || picker.why().contains(plannerWhy) ? "" : "; planner: " + plannerWhy), false);
            }
            if (Options.NOTHING.equals(p)) {
                clearPlanning(bb);
                planFailed = "passed (" + picker.why() + ")";
                planFailedAt = now;
                bb.port.log(kind + " passes: " + picker.why());
                return fail(kind + " " + planFailed);
            }
            resolved = p;
            bb.port.log(kind + " options: " + picker.why());
        }
        clearPlanning(bb);
        lastSearchPick = -1;
        // the owner's ask: wait for aa pick (RUNNING), or the timeout's first choice
        List<AaManual.Row> rows = picker == null || picker.rows().isEmpty() ? List.of(new AaManual.Row(resolved, resolved)) : picker.rows();
        String go = man.gate(id, rows, now, bb.port::log);
        if (go == null) {
            bb.fact(PLANNING_FACT, kind + ": asking the owner (aa pick)");
            return Status.RUNNING;
        }
        if (!go.equals(resolved) && picker != null) picker.chose(go);
        resolved = go;
        settledGoal = g;
        String opt = picker == null ? null : picker.chosenId();
        bb.pendingReason("needs: " + kind + " unmet -> " + g + (picker == null ? "" : " as " + resolved + " [option " + opt + "; options: " + picker.why() + "]"));
        Status s = cooldown.tick(bb);
        if (s == Status.SUCCESS) settledAt = now;
        return s == Status.FAILURE ? jobFailed(bb, cooldown.reason()) : s;
    }

    /** The prerequisite verb a wood-less plan gets first. */
    static final String PREREQ_CUT = "cut ";

    static boolean isPrereq(String chain) { return chain != null && (chain.startsWith(PREREQ_CUT) || WoodOptions.isWoodCommand(chain)); }

    /**
     * aa-prereq: the planner's "missing N logs - ... no trees in my areas" -> "cut max(N, 4)" (a table's worth at the
     * least); any other no-plan reason -> null (the skip stands, with its reason).
     */
    static String woodPrereq(String why) {
        if (why == null || !why.contains("no trees in my areas")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("missing (\\d+) logs").matcher(why);
        if (!m.find()) return null;
        return PREREQ_CUT + Math.max(4, Math.min(64, Integer.parseInt(m.group(1))));
    }

    /** The cut's price: 20 s to reach trees and 6 s a log (the chopper's measured pace with an axe, rounded up). */
    static int prereqSeconds(String pre) {
        try { return 20 + 6 * Integer.parseInt(pre.substring(pre.lastIndexOf(PREREQ_CUT) + PREREQ_CUT.length()).trim()); } catch (RuntimeException e) { return 60; }
    }

    /** The planner searches on its worker: the slot waits on this rung (RUNNING) up to PLAN_WAIT_MS, then a skip. */
    private Status waitPlan(Blackboard bb, String g, long now) {
        // 0.36.a6 (aa371): the wait counts only while this rung keeps asking for this goal; a clock left from an
        // earlier wait (another rung or a job ran in between, or the goal changed) starts afresh
        if (planningSince < 0 || !g.equals(planningGoal) || now - lastPlanAsk > PLAN_GAP_MS) {
            planningSince = now;
            planningGoal = g;
            lastSearchPick = -1;
            bb.port.log("planning " + g + " for " + kind);
        }
        lastPlanAsk = now;
        if (now - planningSince > PLAN_WAIT_MS) {
            planningSince = -1;
            bb.fact(PLANNING_FACT, null);
            planFailed = "no plan for " + g + " (the planner still searched after " + PLAN_WAIT_MS / 1000 + "s)";
            planFailedAt = now;
            bb.port.log("no plan for " + g + ": skipped " + Cooldown.BASE_MS / 60000 + " min");
            return fail(kind + " skipped: " + planFailed);
        }
        bb.fact(PLANNING_FACT, kind + ": " + g + " (" + (now - planningSince) / 1000 + "s)");
        return Status.RUNNING;
    }

    private Status planFailed(Blackboard bb, long now, String why, boolean none) {
        clearPlanning(bb);
        planFailed = why;
        planFailedAt = now;
        bb.port.log(kind + (none ? " skipped at once (the planner said no plan) " : " has no option ") + Cooldown.BASE_MS / 60000 + " min: " + why);
        return fail(kind + " skipped: " + why);
    }

    private void clearPlanning(Blackboard bb) {
        planningSince = -1;
        bb.fact(PLANNING_FACT, null);
    }

    /**
     * 0.36.a8: the task failed. With a picker that takes the failure as a reason to switch, the next option starts at once
     * (the cooldown the failure set is forgotten; the rung stays RUNNING); without one, or with nothing left, the rung
     * fails as before and the options line goes with the reason.
     */
    private Status jobFailed(Blackboard bb, String why) {
        // S2 (aa373: bootstrap ended "scan base: no chests" after the tools were made): the need is met, the task's
        // failed tail is not this rung's failure
        if (!lastForced && !unmet.test(bb) && !Options.ownerOrDanger(why == null ? "" : why)) {
            cooldown.forget();
            settledAt = bb.clock.now();
            bb.port.log(kind + ": the task ended \"" + why + "\" but the need is met - counted done");
            return Status.SUCCESS;
        }
        if (woodRan != null && isPrereq(resolved)) {
            if (WoodOptions.switchable(why)) {
                woodMem.failed(woodRan.memoryKey(), why == null ? "failed" : why, bb.clock.now(), bb.in);
                bb.port.log(kind + ": wood option " + woodRan.id() + " failed (\"" + why + "\"), held " + Options.RETRY_MS / 60000 + " min; the next option runs");
                cooldown.forget();
            }
            woodRan = null;
            return fail(why);
        }
        if (picker == null) return fail(why);
        // aa-trees: a picker rung whose own chain ended on missing wood: the next pick goes through the wood prerequisite
        if (why != null && why.contains("no trees in my areas") && board != null && board.fact(WoodOptions.FACTS) != null) {
            cooldown.forget();
            return planFailed(bb, bb.clock.now(), why, false);
        }
        if (picker.failed(bb, why)) {
            bb.fact(CURRENT_RUNG, id);
            String next = picker.pick(bb, planned, plannedCost, planned == null ? "no planner chain" : null);
            if (next != null && !Options.NOTHING.equals(next) && !job.running()) {
                cooldown.forget();
                resolved = next;
                bb.port.log(kind + ": " + why + " -> switching: " + picker.why());
                bb.pendingReason("needs: " + kind + " switched after \"" + why + "\" -> " + next + " [option " + picker.chosenId() + "]");
                Status s = cooldown.tick(bb);
                return s == Status.FAILURE ? fail(cooldown.reason() + " | options: " + picker.why()) : s;
            }
        }
        return fail(why + " | options: " + picker.why());
    }

    /** BRAIN_PLAN B1: the need is on now (its band updated; the brain scores a met rung below 0). */
    public boolean unmet(Blackboard bb) { return unmet.test(bb); }

    /**
     * AA_ROUND2 2: could this rung start a task now? Null when it can (or a task already runs), else the reason: met,
     * skipped, cooling, a remembered failed plan, the planner searching or answering NONE, or (a picker rung, after a
     * rescore) no option. Touches no state but the picker's last cost; the brain asks before an interrupt.
     */
    public String probe(Blackboard bb) {
        if (job.running()) return null;
        long now = bb.clock.now();
        AaManual man = AaManual.of(bb);
        boolean forced = id.equals(man.doRung);
        if (!forced && !unmet.test(bb)) return kind + " met";
        String why = skip.apply(bb);
        if (why != null) return kind + " skipped: " + why;
        String g = goal.apply(bb);
        if (g == null) return kind + " has nothing to run";
        if (forced) return null;
        if (cooldown.cooling(now)) return kind + " cooling down";
        if (io.github.mojolowjo.entropybot.aa.core.WayBack.leg(bb, g, "needs") != null) return null;      // aa385: the return leg can run
        if (planFailed != null && now - planFailedAt < Cooldown.BASE_MS) return kind + " skipped: " + planFailed;
        JobPort.Resolved r = bb.port.resolveState(g);
        if (r.state() == JobPort.PlanState.SEARCHING && picker == null) return "the planner still searches " + g;
        boolean none = r.state() == JobPort.PlanState.NONE;
        if (picker == null) return none ? "no plan for " + g + ": " + r.why() : null;
        picker.refresh(bb);
        String no = picker.noOption();
        if (no != null) return no;
        // a picker with no fresh ranking and no planner chain has nothing known to run
        if (none && Double.isNaN(picker.lastCostS())) return "no plan for " + g + ": " + r.why();
        return null;
    }

    /** The skip reason now (null: it may start). */
    public String skipWhy(Blackboard bb) { return skip.apply(bb); }

    /** The picker's options line (null without a picker). */
    public String options() { return picker == null ? null : picker.why(); }

    public Picker picker() { return picker; }

    private String settledGoal, planningGoal;
    private long lastPlanAsk = -1;
    /** A gap this long between two asks for a plan means the earlier wait is over (AA ticks at 4 Hz). */
    public static final long PLAN_GAP_MS = 5_000;

    @Override public void halt(String why) {
        if (planningSince >= 0 && board != null) board.fact(PLANNING_FACT, null);
        planningSince = -1;
        if (board != null) AaManual.of(board).dropAsk(id);
        if (picker != null && board != null && why != null && why.startsWith("stuck:") && job.running()) picker.stalled(board, why);
        cooldown.halt(why);
    }

    @Override public List<Node> children() { return List.of(cooldown); }

    public RunJob job() { return job; }

    public Cooldown cooldown() { return cooldown; }
}
