package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Round 4A N (AA_ROUND4 A1): one basic need (tools, food, station, torches, base, bed, bag, iron). It ticks one way:
 * facts -> its option set's choice (with the {@link Ways} rows: the planner, cut N + planner, the climb as a priced first
 * step) -> the best row's chain -> {@link RunJob}. No planner-first, no fixed waits, no own cooldown: the brain's
 * {@link Failures} is the one memory (a failed row is held by its key; a need with no runnable row fails with every
 * reason and the brain rests it Cooldown.NO_RUN_MS, Brain.ended). A prerequisite row that ends done keeps the need
 * RUNNING on a {@value #WAIT_FACTS} step until the bag or station facts change (at most Tuning settleMaxS), then it
 * picks again - never a FAILURE. The owner's manual driving ({@link AaManual}: do, skip, ask/pick, use) is checked
 * here. Replaces the ladder's Rung (deleted; NoRungTest). Loader notes: none (pure; the facts are blackboard suppliers).
 */
public final class Need extends Node {
    /** The blackboard fact: the need that picked last ({@code aa use} applies to it). */
    public static final String CURRENT = "aa.currentRung";
    /** The blackboard fact "what is being planned or waited for" (the status line shows it). */
    public static final String PLANNING_FACT = "planning";
    /** The step after a prerequisite while the facts catch up (the brain's watchdog lets it stand still). */
    public static final String WAIT_FACTS = "wait facts";

    /**
     * A need's option scorer ({@link Options.Picker} over an {@link Options.OptionSet}): the planner's chain in (null when
     * it has none), the chain to run out (null: no row can run, {@link #why()} says why; {@link Options#NOTHING}: the need
     * passes); a failed end is offered back ({@code true}: another row runs at once, the failed one held).
     */
    public interface Picker {
        String pick(Blackboard bb, String planned);

        default String pick(Blackboard bb, String planned, double plannedCost, String plannerWhy) { return pick(bb, planned); }

        boolean failed(Blackboard bb, String end);

        /** Its memory joins the brain's one failure record under the need's id. */
        default void bind(Failures f, String need) {}

        /** A failed end that does not switch: the row that ran is held all the same. */
        default void charge(Blackboard bb, String end) {}

        /** The brain's stuck watchdog halted the running row: held like a failure, no switch at once. */
        default void stalled(Blackboard bb, String why) {}

        String why();

        default void chose(String command) {}

        default List<AaManual.Row> rows() { return List.of(); }

        default String chosenId() { return null; }

        default String counters(long now) { return null; }

        default boolean factsFailedSince(long since) { return false; }

        /** The chosen row's cost in seconds from the last pick or rescore (NaN: unknown). */
        default double lastCostS() { return Double.NaN; }

        default io.github.mojolowjo.entropybot.aa.brain.Site lastSite() { return null; }

        /** Re-score from fresh facts without picking (updates {@link #lastCostS}). */
        default void refresh(Blackboard bb) {}

        /** Why no row can run after the last refresh (null: one can, or not known). */
        default String noOption() { return null; }
    }

    public final TaskKind kind;
    private final Predicate<Blackboard> unmet;
    private final Function<Blackboard, String> noWay;
    private final Function<Blackboard, List<Step>> goal;
    private final Picker picker;
    private boolean surface;
    private double handS = 60;
    private final RunJob job;
    private final Ways.State plan = new Ways.State();
    private Failures failures = new Failures();
    /** The memory of the Ways rows (wood, climb, plain commands): a view over the brain's record once bound. */
    private final Options.OptionMemory mem = new Options.OptionMemory();
    private String resolved, ranKey, preSig, lastWhy = "no choice yet", lastLogged;
    private boolean ranPre, lastForced, switched;
    private long waitSince = -1, refreshAt = -1;
    private double lastCostS = Double.NaN, climbS;

    /**
     * @param unmet the need is on (its band lives in here)
     * @param noWay why no row of it can run now (null: rows may), e.g. "no base chests scanned"
     * @param goal  rm-execution2: the typed steps its rows are built on: one {@link Step.Plan} (a planner goal) or the
     *              need's plain way ({@link Step.Deposit}, a craft)
     */
    public Need(String id, TaskKind kind, Predicate<Blackboard> unmet, Function<Blackboard, String> noWay, Function<Blackboard, List<Step>> goal, Picker picker) {
        super(id, "need " + kind);
        this.kind = kind;
        this.unmet = unmet;
        this.noWay = noWay;
        this.goal = goal;
        this.picker = picker;
        this.job = new RunJob(id + ".job", kind, bb -> resolved == null ? null : spec(kind, resolved));
    }

    public Need(String id, TaskKind kind, Predicate<Blackboard> unmet, Function<Blackboard, String> noWay, Function<Blackboard, List<Step>> goal) {
        this(id, kind, unmet, noWay, goal, null);
    }

    /** Its work wants open ground: under ground the climb is the first step of its row, priced in. */
    public Need surface() {
        surface = true;
        return this;
    }

    /** The hand price (seconds) of a plain-command row. */
    public Need hand(double seconds) {
        handS = seconds;
        return this;
    }

    public boolean wantsSurface() { return surface; }

    /** The brain's one failure record (the pickers' and the Ways rows' memories become views over it). */
    public void bindFailures(Failures f) {
        failures = f;
        mem.bind(f, id);
        if (picker != null) picker.bind(f, id);
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
        if (job.running()) return ended(bb, job.tick(bb), now);
        if (waitSince >= 0) {
            boolean changed = !sig(bb).equals(preSig);
            long waited = now - waitSince;
            if (!changed && waited < Tuning.get("settleMaxS") * 1000) {
                bb.fact(PLANNING_FACT, kind + ": " + WAIT_FACTS + " (" + waited / 1000 + " s of " + Math.round(Tuning.get("settleMaxS")) + ")");
                bb.pendingReason("needs: " + kind + " [prerequisite done, " + WAIT_FACTS + "]");
                return Status.RUNNING;
            }
            bb.port.log(kind + ": " + WAIT_FACTS + " over after " + waited / 1000 + " s (" + (changed ? "the facts changed" : "settleMaxS") + "), picking again");
            // review 4A-N (2): a prerequisite that ended done and moved no fact is held, never run again in a loop
            if (!changed && ranKey != null) {
                mem.failed(ranKey, "no change after the prerequisite (" + waited / 1000 + " s)", now, bb.in);
                bb.port.log(kind + ": " + ranKey + " held: no change after the prerequisite");
            }
            waitSince = -1;
            clearPlanning(bb);
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
        String no = noWay.apply(bb);
        if (no != null) return fail(kind + ": no way can run (" + no + ")");
        List<Step> g = goal.apply(bb);
        if (g == null || g.isEmpty()) return fail(kind + " has nothing to run");
        // an ask already waits for this need: the frozen rows decide, no new pick per tick
        if (man.ask && man.pending != null && man.pending.rung().equals(id)) {
            String go = man.gate(id, man.pending.rows(), now, bb.port::log);
            if (go == null) return Status.RUNNING;
            if (picker != null) picker.chose(go);
            runOpt = null;
            return start(bb, go, false, chosenKey(), "[the owner's ask]", now);
        }
        Pick p = pick(bb, g, now);
        if (p.command == null) {
            clearPlanning(bb);
            String why = kind + ": no way can run (" + p.why + ")";
            if (!why.equals(lastLogged)) bb.port.log(why);
            lastLogged = why;
            return fail(why);
        }
        if (Ways.WAIT_PLAN.equals(p.command)) {
            bb.fact(PLANNING_FACT, kind + ": " + Step.label(g) + " (" + p.why + ")");
            return Status.RUNNING;
        }
        clearPlanning(bb);
        if (Options.NOTHING.equals(p.command)) {
            bb.port.log(kind + " passes: " + p.why);
            return fail(kind + " passes (" + p.why + ")");
        }
        List<AaManual.Row> rows = picker == null || picker.rows().isEmpty() || p.pre ? List.of(new AaManual.Row(p.command, p.command)) : picker.rows();
        String go = man.gate(id, rows, now, bb.port::log);
        if (go == null) {
            bb.fact(PLANNING_FACT, kind + ": asking the owner (aa pick)");
            return Status.RUNNING;
        }
        if (!go.equals(p.command) && picker != null) {
            picker.chose(go);
            runOpt = null;
        }
        return start(bb, go, p.pre && go.equals(p.command), go.equals(p.command) ? p.key : chosenKey(), "[" + p.why + "]", now);
    }

    /** The memory key of the row the owner chose (review 4A-N (3)), null when the picker has none. */
    private String chosenKey() {
        return picker instanceof Options.Picker<?> op && op.ran() != null ? op.ran().memoryKey() : null;
    }

    /** The pick: the command (the row's label), whether it is a prerequisite, its memory key and the {@code aa why} text. */
    record Pick(String command, boolean pre, String key, String why) {}

    /** rm-execution2: the row a climb or the need's own rows chose (its typed steps run); null: the picker's row. */
    private Options.Option runOpt;

    private Pick pick(Blackboard bb, List<Step> g, long now) {
        runOpt = null;
        bb.fact(CURRENT, id);
        Ways.Plan pl = Ways.plan(bb, plan, failures, id, mem, g, now);
        Ways.Pre climb = Ways.climb(bb, mem, now);       // used by a surface need, and by a wood row (trees grow up there)
        String cmd, key = null, why;
        boolean pre = false;
        double rowS;
        if (picker == null) {
            Options.Choice c = Ways.goalRows(g, pl, plan, mem, now, handS);
            Options.Option b = c.best();
            if (b != null && Ways.WAIT_PLAN.equals(b.command())) return note(new Pick(b.command(), false, null, pl.why()), lastCostS);
            why = c.why();
            runOpt = b;
            cmd = b == null ? null : b.command();
            key = b == null ? null : b.memoryKey();
            pre = b != null && Ways.PREREQ.equals(b.way());
            rowS = b == null ? Double.NaN : b.seconds();
        } else {
            if (picker instanceof Options.Picker<?> op) op.extra = ch -> Ways.withPlan(ch, pl, plan, mem, now);
            cmd = picker.pick(bb, pl != null && pl.ready() ? pl.steps() : null, pl == null ? 0 : pl.cost(), pl == null ? null : pl.why());
            // no facts readable: the picker has no row for a searching planner; the need waits for it (bounded by plannerWaitS)
            if (cmd == null && pl != null && pl.state() == JobPort.PlanState.SEARCHING) cmd = Ways.WAIT_PLAN;
            if (Ways.WAIT_PLAN.equals(cmd)) return note(new Pick(cmd, false, null, pl.why()), lastCostS);
            why = picker.why();
            rowS = picker.lastCostS();
            if (picker instanceof Options.Picker<?> op && op.ran() != null) {
                pre = Ways.PREREQ.equals(op.ran().way());
                key = op.ran().memoryKey();
            }
        }
        climbS = 0;
        if (cmd != null && climb != null && (surface || pre) && !Ways.WAIT_PLAN.equals(cmd) && !Options.NOTHING.equals(cmd)) {
            if (climb.option() == null) return note(new Pick(null, false, null, climb.why() + "; then " + why), Double.NaN);
            climbS = climb.seconds();
            why = climb.why() + " (" + Math.round(climbS) + " s) first, then " + why;
            runOpt = climb.option();
            cmd = runOpt.command();
            key = climb.key();
            pre = true;
        }
        if (cmd == null && pl != null && pl.state() == JobPort.PlanState.NONE && !why.contains(pl.why())) why = why + "; planner: " + pl.why();
        Pick p = new Pick(cmd, pre, key, why);
        note(p, Double.isNaN(rowS) ? Double.NaN : rowS + climbS);
        if (cmd != null && !why.equals(lastLogged)) {
            bb.port.log(kind + " options: " + why);
            lastLogged = why;
        }
        return p;
    }

    private Pick note(Pick p, double costS) {
        lastWhy = p.why;
        lastCostS = costS;
        return p;
    }

    /**
     * rm-execution: the row that runs as a task, on its typed steps (its command is only their label). A label with no
     * row behind it runs nothing: the task is refused "no typed steps" (AaRuntime) and the need fails with that reason.
     */
    private JobPort.TaskSpec spec(TaskKind kind, String cmd) {
        Options.Option o = runOpt != null ? runOpt : picker == null ? null : picker.ranOption();
        if (o != null && o.typed() && cmd.equals(o.command())) return JobPort.TaskSpec.typed(kind, cmd, o.steps(), "needs");
        return JobPort.TaskSpec.typed(kind, cmd, List.of(), "needs");
    }

    private Status start(Blackboard bb, String cmd, boolean pre, String key, String why, long now) {
        resolved = cmd;
        ranPre = pre;
        ranKey = key;
        if (pre) preSig = sig(bb);
        bb.pendingReason("needs: " + kind + " unmet -> " + cmd + (picker == null || picker.chosenId() == null ? "" : " as " + picker.chosenId()) + " " + why);
        return ended(bb, job.tick(bb), now);
    }

    private Status ended(Blackboard bb, Status s, long now) {
        if (s == Status.SUCCESS) {
            if (!ranPre) return Status.SUCCESS;
            ranPre = false;
            // A3: the facts lag behind a prerequisite: RUNNING on "wait facts" until they move (at most settleMaxS)
            if (sig(bb).equals(preSig)) {
                waitSince = now;
                bb.fact(PLANNING_FACT, kind + ": " + WAIT_FACTS + " (0 s)");
                return Status.RUNNING;
            }
            return run0(bb);            // the facts moved already: the next row in this tick
        }
        if (s == Status.FAILURE) {
            switched = false;
            Status f = failed(bb, job.reason());
            // a switchable end: the next row starts in this tick (the picker bounds the switches, Options.MAX_SWITCHES)
            if (switched && !job.running()) { switched = false; return run0(bb); }
            return f;
        }
        return s;
    }

    /** What the facts lag shows in: the bag, the stations, the base and bed, the depth band, the pickaxe. */
    static String sig(Blackboard bb) {
        var in = bb.in;
        return Failures.items(in) + "|" + (in.bag == null ? 0 : in.bag.hashCode()) + "|" + in.tableKnown + in.tableInBag + in.furnaceKnown + in.furnaceInBag
                + in.baseKnown + in.baseChests + in.bedKnown + in.bedInBag + "|" + (in.underDepth >= AaLadder.SURFACE_DEPTH) + "|" + in.pickTier;
    }

    /** The task failed: the row that ran is held (Failures), a switchable end picks the next row at once. */
    private Status failed(Blackboard bb, String why) {
        long now = bb.clock.now();
        boolean pre = ranPre;
        ranPre = false;
        // aa-r1fix: a camp chest cell the game refused is not tried again (the next cell of ChestSpot's ranking is)
        if (kind == TaskKind.TABLE_FURNACE && ChestSpot.failedCell(why) != null) {
            ChestSpot.noteFailure(bb.in.chestFailed, why);
            bb.port.log(kind + ": chest cell refused (\"" + why + "\"), the next free cell is tried");
            return fail(why);
        }
        // S2 (aa373): the need is met, the task's failed tail is not this need's failure
        if (!lastForced && !unmet.test(bb) && !Options.ownerOrDanger(why == null ? "" : why)) {
            bb.port.log(kind + ": the task ended \"" + why + "\" but the need is met - counted done");
            return Status.SUCCESS;
        }
        if (why != null && Options.ownerOrDanger(why)) return fail(why);
        if (pre || picker == null) {
            if (ranKey != null) {
                mem.failed(ranKey, why == null ? "failed" : why, now, bb.in);
                bb.port.log(kind + ": " + ranKey + " failed (\"" + why + "\"), held " + Options.RETRY_MS / 60000 + " min x fails");
            }
            return fail(why);
        }
        if (picker.failed(bb, why)) {
            bb.port.log(kind + ": " + why + " -> the next row (the failed one held): " + picker.why());
            bb.pendingReason("needs: " + kind + " switched after \"" + why + "\"");
            switched = true;
            return Status.RUNNING;
        }
        picker.charge(bb, why);
        return fail(why + " | options: " + picker.why());
    }

    public boolean unmet(Blackboard bb) { return unmet.test(bb); }

    /**
     * Could this need start a task now? Null when it can (or a task runs), else the reason: met, no way, a held or NONE
     * planner, no row after a rescore. Touches no state but the picker's last cost; the brain asks before an interrupt.
     */
    public String probe(Blackboard bb) {
        if (job.running() || waitSince >= 0) return null;
        AaManual man = AaManual.of(bb);
        boolean forced = id.equals(man.doRung);
        if (!forced && !unmet.test(bb)) return kind + " met";
        String no = noWay.apply(bb);
        if (no != null) return kind + ": no way can run (" + no + ")";
        List<Step> g = goal.apply(bb);
        if (g == null || g.isEmpty()) return kind + " has nothing to run";
        if (forced) return null;
        if (picker == null) {
            String pg = Ways.planGoal(g);
            if (pg == null) return null;
            String held = failures.held(id, Ways.planKey(pg), bb.clock.now());
            if (held != null) return Ways.planKey(pg) + " held: " + held;
            JobPort.Resolved r = bb.port.resolveState(pg);
            if (r.state() == JobPort.PlanState.NONE && r.logsMissing() == 0) return "no plan for " + pg + ": " + r.why();
            return null;
        }
        picker.refresh(bb);
        return picker.noOption();
    }

    /** The live cost in seconds for the brain (the chosen row + the climb), re-scored at most every 10 s while idle. */
    public double liveCostS(Blackboard bb) {
        if (!job.running() && picker != null) {
            long now = bb.clock.now();
            if (refreshAt < 0 || now >= refreshAt) {
                refreshAt = now + AaDesires.RESCORE_MS;
                picker.refresh(bb);
                Ways.Pre c = surface ? Ways.climb(bb, mem, now) : null;
                climbS = c == null || c.option() == null ? 0 : c.seconds();
                double r = picker.lastCostS();
                if (!Double.isNaN(r)) lastCostS = r + climbS;
            }
        }
        return lastCostS;
    }

    /** The {@code aa why} line of the last pick: the rows with their prices, the dropped ones with their reasons. */
    public String options() { return lastWhy; }

    public Picker picker() { return picker; }

    public RunJob job() { return job; }

    /** True while the need waits for the facts after a prerequisite. */
    public boolean waitingFacts() { return waitSince >= 0; }

    private void clearPlanning(Blackboard bb) { bb.fact(PLANNING_FACT, null); }

    @Override public void halt(String why) {
        if (board != null) {
            board.fact(PLANNING_FACT, null);
            AaManual.of(board).dropAsk(id);
            if (picker != null && why != null && why.startsWith("stuck:") && job.running()) picker.stalled(board, why);
        }
        waitSince = -1;
        ranPre = false;
        job.halt(why);
    }

    @Override public List<Node> children() { return List.of(job); }

    /** The needs under the brain (or any node), in order (for the status lines and tests). */
    public static List<Need> all(Node parent) {
        List<Need> out = new ArrayList<>();
        for (Node n : parent.children()) if (n instanceof Need r) out.add(r);
        return out;
    }

    /** "12 s" for the status lines. */
    static String secs(double blocks) { return Math.round(Unit.seconds(blocks)) + " s"; }
}
