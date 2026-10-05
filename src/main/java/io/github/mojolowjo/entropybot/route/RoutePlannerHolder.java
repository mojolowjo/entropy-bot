package io.github.mojolowjo.entropybot.route;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Where the walking side (R3) finds the planner and the dumper. R2's RouteRuntime calls {@link #set} (and
 * {@link #setDumper}) when it starts, and {@code set(null)} when it stops. The defaults do nothing: {@link #NONE} is
 * never {@code available()}, so every walk is a plain Baritone walk and nothing here needs R2 to run or to be tested.
 *
 * <p>{@link #counters()} is the walk side's {@link RouteCounters} (planning timeouts, fallbacks after nopath or stuck).
 * R2 may build its planner on this same instance so {@code stats()} covers both sides; {@code route status} shows the
 * walk side's counts either way.
 */
public final class RoutePlannerHolder {
    private RoutePlannerHolder() {
    }

    /** The no-op planner: not available, plans nothing, builds nothing. */
    public static final RoutePlanner NONE = new RoutePlanner() {
        @Override
        public boolean available() { return false; }

        @Override
        public CompletableFuture<RoutePlan> plan(int dim, Cell start, Cell goal, int goalRadius) {
            return CompletableFuture.completedFuture(new RoutePlan(RoutePlan.Status.BAD_REQUEST, Double.POSITIVE_INFINITY,
                    List.of(), CostToGo.empty(), 0, 0, "no route planner"));
        }

        @Override
        public RouteStats stats() {
            return new RouteStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");
        }

        @Override
        public void requestBuildAlong(int dim, Cell a, Cell b) {
        }
    };

    /** The no-op dumper. */
    public static final RouteDumper NO_DUMPER = (dim, x, y, z, outDir) -> false;

    private static volatile RoutePlanner planner = NONE;
    private static volatile RouteDumper dumper = NO_DUMPER;
    private static final RouteCounters COUNTERS = new RouteCounters();

    public static RoutePlanner get() { return planner; }

    /** Installs the planner; null puts the no-op one back. */
    public static void set(RoutePlanner p) { planner = p == null ? NONE : p; }

    public static RouteDumper dumper() { return dumper; }

    public static void setDumper(RouteDumper d) { dumper = d == null ? NO_DUMPER : d; }

    public static RouteCounters counters() { return COUNTERS; }

    /** The dim id of {@link SectionKey}: 0 = the overworld (the only one stage 1 routes in), -1 = any other. */
    public static int dimId(String dimension) {
        return "minecraft:overworld".equals(dimension) ? 0 : -1;
    }
}
