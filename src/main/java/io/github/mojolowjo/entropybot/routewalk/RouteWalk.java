package io.github.mojolowjo.entropybot.routewalk;

import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.CostToGo;
import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RoutePlan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One walk's use of the router, without game types (plan section 4): wait for the plan at most {@link #WAIT_CAP_MS},
 * then walk with the cost-to-go table (mode goal), along legs (mode legs), or plain; after "no path" or "stuck" with a
 * routed goal, fall back to the plain goal once. The game side ({@code RouteWalker}) turns the answers into Baritone
 * goals. Not thread-safe: the game thread owns it.
 */
public final class RouteWalk {
    /** The plan's wait cap: after this the walk starts plain (the plan is kept by the planner for next time). */
    public static final long WAIT_CAP_MS = 300;

    public enum Mode {
        GOAL, LEGS, PLAIN;

        /** "goal", "legs" or "plain" (any case); null for anything else. */
        public static Mode parse(String s) {
            if (s == null) return null;
            return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "goal" -> GOAL;
                case "legs" -> LEGS;
                case "plain", "off" -> PLAIN;
                default -> null;
            };
        }

        public String word() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public enum Phase {
        /** Waiting for the plan. */
        PLANNING,
        /** Walking with the plan (the RouteGoal, or the legs). */
        ROUTED,
        /** Walking plain: no usable plan, a timeout, or an error. */
        PLAIN,
        /** Was routed, then "no path" or "stuck": walking plain now. */
        FELL_BACK
    }

    private final Mode mode;
    private final int dim;
    private final Cell start, goal;
    private final CompletableFuture<RoutePlan> future;
    private final long startMs;
    private Phase phase = Phase.PLANNING;
    private RoutePlan plan;
    private List<Cell> legs = List.of();
    private int leg;
    private String used = "planning";
    private long planWaitMs = -1;

    public RouteWalk(Mode mode, int dim, Cell start, Cell goal, CompletableFuture<RoutePlan> future, long startMs) {
        this.mode = mode;
        this.dim = dim;
        this.start = start;
        this.goal = goal;
        this.future = future;
        this.startMs = startMs;
    }

    public Mode mode() { return mode; }

    public int dim() { return dim; }

    public Cell start() { return start; }

    public Cell goal() { return goal; }

    public Phase phase() { return phase; }

    public RoutePlan plan() { return plan; }

    /** How long the walk waited for its plan (ms), -1 while it still waits. */
    public long planWaitMs() { return planWaitMs; }

    /** For the trip log: "goal", "legs(4)", "plain (no plan: goal box not built yet)", "goal -> plain (stuck)". */
    public String used() { return used; }

    /** A routed walk (not plain, not fallen back). */
    public boolean routed() { return phase == Phase.ROUTED; }

    /** The cost-to-go table of a routed goal-mode walk (never null). */
    public CostToGo table() { return plan == null ? CostToGo.empty() : plan.table(); }

    /**
     * While PLANNING: takes the plan when it is ready, or gives up after the cap (counted as a planning timeout in
     * {@code counters}). An exceptional future is a plain walk too (counted as a worker exception). Returns the phase.
     */
    public Phase poll(long nowMs, RouteCounters counters, io.github.mojolowjo.entropybot.route.RouteLog log) {
        if (phase != Phase.PLANNING) return phase;
        if (future == null) return plain(nowMs, "no plan asked");
        if (future.isDone()) {
            RoutePlan p;
            try {
                p = future.getNow(null);
            } catch (RuntimeException e) {
                Throwable t = e.getCause() != null ? e.getCause() : e;
                if (counters != null) counters.workerException("route plan", t, log);
                return plain(nowMs, "plan failed: " + t);
            }
            return take(p, nowMs);
        }
        if (nowMs - startMs >= WAIT_CAP_MS) {
            if (counters != null) counters.planningTimeout();
            return plain(nowMs, "planning took over " + WAIT_CAP_MS + " ms");
        }
        return phase;
    }

    private Phase take(RoutePlan p, long nowMs) {
        planWaitMs = nowMs - startMs;
        plan = p;
        if (p == null) return plain(nowMs, "no plan");
        if (mode == Mode.GOAL) {
            if (p.table() == null || p.table().size() == 0) return plain(nowMs, "no plan: " + p.reason());
            if (p.status() == RoutePlan.Status.BAD_REQUEST) return plain(nowMs, "no plan: " + p.reason());
            phase = Phase.ROUTED;
            used = "goal";
            return phase;
        }
        if (mode == Mode.LEGS) {
            if (p.status() != RoutePlan.Status.OK) return plain(nowMs, "no route: " + p.reason());
            List<Cell> reps = new ArrayList<>();
            for (RoutePlan.Step s : p.path()) reps.add(s.rep());
            legs = LegsSplitter.split(start, reps, goal);
            if (legs.size() <= 1) return plain(nowMs, "one leg");
            leg = 0;
            phase = Phase.ROUTED;
            used = "legs(" + legs.size() + ")";
            return phase;
        }
        return plain(nowMs, "mode plain");
    }

    private Phase plain(long nowMs, String why) {
        if (planWaitMs < 0) planWaitMs = nowMs - startMs;
        phase = Phase.PLAIN;
        used = "plain (" + why + ")";
        return phase;
    }

    // ---- legs ----

    /** Legs mode: every leg's end, the last one the goal (empty in goal mode). */
    public List<Cell> legs() { return legs; }

    public int legIndex() { return leg; }

    /** The current leg's end (null unless a routed legs walk). */
    public Cell currentLeg() {
        return routed() && mode == Mode.LEGS && leg < legs.size() ? legs.get(leg) : null;
    }

    /** The current leg is the last one: walk with the real goal. */
    public boolean lastLeg() {
        return leg >= legs.size() - 1;
    }

    /** A routed legs walk with another leg after this one. */
    public boolean hasNextLeg() {
        return routed() && mode == Mode.LEGS && leg < legs.size() - 1;
    }

    /** Moves to the next leg and returns its end. */
    public Cell nextLeg() {
        if (!hasNextLeg()) throw new IllegalStateException("no next leg");
        leg++;
        return legs.get(leg);
    }

    // ---- fallback ----

    /**
     * After "no path" ("nopath") or the stuck watchdog ("stuck") on a routed walk: true when the walk falls back to the
     * plain goal now (once; counted), false when it was not routed (the caller does today's thing).
     */
    public boolean fallBack(String why, RouteCounters counters) {
        if (phase != Phase.ROUTED) return false;
        phase = Phase.FELL_BACK;
        used = used + " -> plain (" + why + ")";
        if (counters != null) {
            if (why.startsWith("stuck")) counters.fallbackStuck();
            else counters.fallbackNoPath();
        }
        return true;
    }
}
