package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Round 4A N (AA_ROUND4 A2, A3): the rows every {@link Need} shares. The planner is a row: READY at its plan cost
 * (steps x Tuning planUnit), SEARCHING at Tuning plannerHandS + searchPenalty (a known way wins while it thinks; after
 * plannerWaitS it is dropped and held plannerHoldS under {@code planner <goal>}), NONE dropped with its reason - or, when
 * the reason is missing logs, the "cut N + planner" row (the best {@link WoodOptions} row, then the plan). The climb out
 * from under ground is a priced first step ({@link WayUp} over the surface facts) of a need whose work wants open
 * ground. Every number comes from Tuning or the facts and is printed in the row's reason. Option costs are walk-blocks;
 * seconds are converted with {@link Unit#blocks}. Pure but for {@code bb.port} (JUnit: NeedTest). Loader notes: none.
 */
public final class Ways {
    private Ways() {}

    /** The command of the planner row while it searches: the need stays RUNNING and asks again next tick. */
    public static final String WAIT_PLAN = "(wait for the planner)";
    /** The way of a row whose command is a prerequisite (the need waits for the facts after it, then picks again). */
    public static final String PREREQ = "prereq";

    static double s(String tuning) { return Tuning.get(tuning); }

    static double blocks(String tuning) { return Unit.blocks(s(tuning)); }

    /**
     * The planner's answer for a need's goal (null for a goal that is a plain way). rm-execution2: {@code steps} = the
     * routine as typed steps (typed at the planner's side; the sets price it, the planner row runs {@link Step.Plan}).
     */
    public record Plan(String goal, JobPort.PlanState state, List<Step> steps, double cost, String why, long searchingMs, Options.Option wood) {
        public boolean ready() { return state == JobPort.PlanState.READY && steps != null; }
    }

    /** The planner goal of a need's steps (one {@link Step.Plan}), else null: a plain way. */
    public static String planGoal(List<Step> goal) {
        return goal != null && goal.size() == 1 && goal.get(0) instanceof Step.Plan p ? p.goal() : null;
    }

    /** The Failures key a planner goal is held under. */
    public static String planKey(String goal) { return "planner " + new Step.Plan(goal).text(); }

    /** Per need: the planner's search clock and the last plan cost per goal. */
    public static final class State {
        String searchGoal, costGoal;
        long searchSince = -1, lastAsk = -1;
        double lastCost = Double.NaN;
        /** review 4A-N (4): the wood row for the last missing-logs answer, re-chosen at most once a second. */
        String woodFor;
        long woodAt = -1;
        Options.Option woodRow;
    }

    /** A gap this long between two asks means the earlier search wait is over (AA ticks at 4 Hz). */
    static long gapMs() { return (long) Tuning.get("planner.gapMs"); }

    /**
     * Asks the planner about the need's goal (one {@link Step.Plan}; any other steps: null, a plain way) and folds the
     * answer into a {@link Plan}: a searching wait past plannerWaitS is held plannerHoldS in {@code f} under
     * {@link #planKey}; an infeasible routine is held the same.
     */
    public static Plan plan(Blackboard bb, State st, Failures f, String need, Options.OptionMemory woodMem, List<Step> goalSteps, long now) {
        String goal = planGoal(goalSteps);
        if (goal == null) return null;
        String key = planKey(goal);
        String held = f.held(need, key, now);
        if (held != null) return new Plan(goal, JobPort.PlanState.NONE, null, 0, "held: " + held, 0, null);
        JobPort.Resolved r = bb.port.resolveState(goal);
        if (r.infeasible()) {
            // live 2026-10-18: a verdict that disagrees with the saved routine spun every tick; held like a failed plan
            st.searchSince = -1;
            f.failedFor(need, key, r.why(), now, bb.in, (long) (s("plannerHoldS") * 1000));
            bb.port.log(need + ": planner routine for " + goal + " held " + Math.round(s("plannerHoldS")) + " s: " + r.why());
            return new Plan(goal, JobPort.PlanState.NONE, null, 0, r.why(), 0, null);
        }
        switch (r.state()) {
            case READY -> {
                st.searchSince = -1;
                st.lastCost = r.cost();
                st.costGoal = goal;
                return new Plan(goal, JobPort.PlanState.READY, r.steps(), r.cost(), null, 0, null);
            }
            case SEARCHING -> {
                if (st.searchSince < 0 || !goal.equals(st.searchGoal) || now - st.lastAsk > gapMs()) {
                    st.searchSince = now;
                    st.searchGoal = goal;
                    bb.port.log("planning " + goal + " for " + need);
                }
                st.lastAsk = now;
                long waited = now - st.searchSince;
                if (waited > s("plannerWaitS") * 1000) {
                    st.searchSince = -1;
                    String why = "the planner still searched after " + Math.round(s("plannerWaitS")) + " s";
                    f.failedFor(need, key, why, now, bb.in, (long) (s("plannerHoldS") * 1000));
                    bb.port.log(need + ": planner row for " + goal + " dropped, held " + Math.round(s("plannerHoldS")) + " s: " + why);
                    return new Plan(goal, JobPort.PlanState.NONE, null, 0, why, 0, null);
                }
                return new Plan(goal, JobPort.PlanState.SEARCHING, null, 0, "still searching (" + waited / 1000 + " s)", waited, null);
            }
            default -> {
                st.searchSince = -1;
                if (!java.util.Objects.equals(st.woodFor, r.why()) || st.woodAt < 0 || now - st.woodAt >= 1000) {
                    st.woodRow = wood(bb, woodMem, r.logsMissing(), now);
                    st.woodFor = r.why();
                    st.woodAt = now;
                }
                return new Plan(goal, JobPort.PlanState.NONE, null, 0, "no plan for " + goal + ": " + r.why(), 0, st.woodRow);
            }
        }
    }

    /** The planner's missing logs (its number, 0 = none) -> max(4, min(64, N)) logs to cut; 0 stays 0. */
    public static int logsToCut(int missing) {
        return missing <= 0 ? 0 : Math.max(4, Math.min(64, missing));
    }

    /** The best wood row for the planner's missing logs (WoodOptions over the wood facts; none readable: the plain cut). */
    static Options.Option wood(Blackboard bb, Options.OptionMemory m, int missing, long now) {
        int n = logsToCut(missing);
        if (n <= 0) return null;
        WoodOptions.WoodFacts wf = null;
        if (bb.fact(WoodOptions.FACTS) instanceof Supplier<?> sup) {
            try {
                if (sup.get() instanceof WoodOptions.WoodFacts x) wf = x;
            } catch (RuntimeException e) {
                bb.port.log("wood facts: " + e);
            }
        }
        m.sweep(bb.in);
        Options.Choice c = WoodOptions.choose(new WoodOptions.WoodFacts(n, wf == null ? List.of() : wf.trees()), m, now);
        if (c.best() == null) {
            bb.port.log("no wood way for the planner's missing logs: " + c.why());
            return null;
        }
        return c.best();
    }

    /** The planner part's price after a prerequisite: the last plan cost for this goal, else the hand price. */
    static double after(Plan p, State st) {
        if (st != null && p.goal().equals(st.costGoal) && !Double.isNaN(st.lastCost)) return st.lastCost * Options.planUnit();
        return blocks("plannerHandS");
    }

    /**
     * The planner-derived rows added to a set's choice: SEARCHING the planner row at hand + searchPenalty; NONE for missing
     * logs the "cut N + planner" row (the wood row's price + the plan's). READY needs nothing here: the set prices the chain.
     */
    public static Options.Choice withPlan(Options.Choice c, Plan p, State st, Options.OptionMemory m, long now) {
        if (p == null || c == null) return c;
        List<Options.Option> rows = new ArrayList<>(c.ranked());
        List<String> dropped = new ArrayList<>(c.dropped());
        addPlanRows(rows, dropped, p, st, m, now);
        return Options.Choice.of(rows, dropped, "no way");
    }

    static void addPlanRows(List<Options.Option> rows, List<String> dropped, Plan p, State st, Options.OptionMemory m, long now) {
        switch (p.state()) {
            case SEARCHING -> {
                double hand = s("plannerHandS"), pen = s("searchPenalty");
                rows.add(Options.Option.sentinel("planner (searching)", "planner", WAIT_PLAN, Unit.blocks(hand + pen),
                        p.goal() + " " + p.why() + ": hand " + Math.round(hand) + " s + search penalty " + Math.round(pen) + " s", planKey(p.goal())));
            }
            case NONE -> {
                Options.Option w = p.wood();
                if (w == null) return;
                String key = w.memoryKey();
                if (m.blocked(key, now)) { dropped.add("cut + planner: " + m.reason(key)); return; }
                double a = after(p, st);
                rows.add(new Options.Option("cut + planner", PREREQ, w.steps(), w.total() + a,
                        w.id() + " " + Math.round(w.total()) + " + planner " + Math.round(a) + " = " + Math.round(w.total() + a) + " (" + w.why() + ")", key, false, null, w.site()));
            }
            default -> { }
        }
    }

    /**
     * The rows of a need with no option set of its own: its plain way at the need's hand price, or the planner's goal
     * (READY: one {@link Step.Plan} row at cost x planUnit, the routine asked for again when it runs; else the planner
     * rows above, NONE dropped with its reason).
     */
    public static Options.Choice goalRows(List<Step> goal, Plan p, State st, Options.OptionMemory m, long now, double handS) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        String label = Step.label(goal);
        if (p == null) {
            String key = "way " + label;
            if (m.blocked(key, now)) dropped.add(label + ": " + m.reason(key));
            else rows.add(Options.typed(label, "command", goal, Unit.blocks(handS), "hand " + Math.round(handS) + " s", key, m));
        } else if (p.ready()) {
            String key = planKey(p.goal());
            double c = p.cost() * Options.planUnit();
            if (m.blocked(key, now)) dropped.add("planner: " + m.reason(key));
            else rows.add(Options.typed("planner", "planner", List.of(new Step.Plan(p.goal())), c, p.goal() + ": plan cost " + Math.round(p.cost()) + " x planUnit " + Options.planUnit(), key, true, m));
        } else {
            if (p.state() == JobPort.PlanState.NONE && p.wood() == null) dropped.add("planner: " + p.why());
            addPlanRows(rows, dropped, p, st, m, now);
        }
        return Options.Choice.of(rows, dropped, "no way to " + label);
    }

    /** A priced first step (the climb): option null = the step is needed and no way does it ({@code why} says why). */
    public record Pre(Options.Option option, double cost, String why, String key) {
        public double seconds() { return Unit.seconds(cost); }
    }

    /**
     * Under {@link AaLadder#SURFACE_DEPTH}+ of ground, the climb is the first step of a surface need's row: the best of
     * WayUp's ways over the surface facts (none readable: the climb verb at depth x CLIMB_BLOCK + CLIMB_FIX); null when
     * the bot is not under ground.
     */
    public static Pre climb(Blackboard bb, Options.OptionMemory m, long now) {
        int d = bb.in.underDepth;
        if (d < AaLadder.SURFACE_DEPTH) return null;
        if (bb.fact(RungOptions.SURFACE_FACTS) instanceof Supplier<?> sup) {
            Object fo = null;
            try {
                fo = sup.get();
            } catch (RuntimeException e) {
                bb.port.log("surface facts: " + e);
            }
            if (fo instanceof RungOptions.SurfaceFacts f) {
                Options.Choice c = WayUp.choose(f, m, now);
                Options.Option b = c.best();
                if (b == null) return new Pre(null, Double.POSITIVE_INFINITY, d + " under ground, no way up (" + c.why() + ")", null);
                if (Options.NOTHING.equals(b.command())) return null;        // open sky is a short walk: the row's own walk gets there
                return new Pre(b, b.total(), "climb " + d + " up: " + b.text(), b.memoryKey());
            }
        }
        double cost = d * RungOptions.CLIMB_BLOCK + RungOptions.CLIMB_FIX;
        String key = "climb";
        if (m.blocked(key, now)) return new Pre(null, Double.POSITIVE_INFINITY, d + " under ground, the climb is held: " + m.reason(key), null);
        String why = "climb " + d + " up " + Math.round(cost) + " (no surface facts: " + d + " x " + Math.round(RungOptions.CLIMB_BLOCK) + " + " + Math.round(RungOptions.CLIMB_FIX) + ")";
        return new Pre(new Options.Option("climb", "climb", List.of(new Step.Climb()), cost, why, key, false), cost, why, key);
    }

    /** "12 s" for a row cost in walk-blocks. */
    static String secs(double blocks) { return String.format(Locale.ROOT, "%d s", Math.round(Unit.seconds(blocks))); }
}
