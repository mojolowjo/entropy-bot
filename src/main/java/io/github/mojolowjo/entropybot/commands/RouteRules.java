package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.route.RouteStats;
import io.github.mojolowjo.entropybot.routewalk.RouteTestPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The texts and argument rules of the {@code route} verb (routing stage 1, R3). Pure: JUnit tests it; the game side is
 * {@link RouteCommand}.
 */
public final class RouteRules {
    private RouteRules() {}

    public static final String USAGE = "route status | route on|off | route mode goal|legs | route build [place|x y z] | route dump x y z"
            + " | route test <placeA> <placeB> [trips]";

    /** route test's default trip count: every mode once each way. */
    public static final int DEFAULT_TRIPS = 6;

    /** "test ..." is the one form that starts a job; everything else answers at once. */
    public static boolean isTest(String rest) {
        return first(rest).equals("test");
    }

    static String first(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        int sp = t.indexOf(' ');
        return sp < 0 ? t : t.substring(0, sp);
    }

    static String after(String rest) {
        String t = rest == null ? "" : rest.trim();
        int sp = t.indexOf(' ');
        return sp < 0 ? "" : t.substring(sp + 1).trim();
    }

    /** route test's arguments: {placeA, placeB, trips}, or an error line ("usage: ...") in [0] with nulls after. */
    public record TestArgs(String a, String b, int trips, String error) {}

    public static TestArgs testArgs(String rest) {
        String[] w = after(rest).toLowerCase(Locale.ROOT).split("\\s+");
        if (w.length < 2 || w[0].isEmpty()) return new TestArgs(null, null, 0, "usage: route test <placeA> <placeB> [trips] (e.g. route test base farm 6)");
        int n = DEFAULT_TRIPS;
        if (w.length >= 3) {
            if (!w[2].matches("\\d{1,3}")) return new TestArgs(null, null, 0, "usage: route test <placeA> <placeB> [trips] - trips is a number (1-" + RouteTestPlan.MAX_TRIPS + ")");
            n = Integer.parseInt(w[2]);
        }
        if (n < 1 || n > RouteTestPlan.MAX_TRIPS) return new TestArgs(null, null, 0, "error: trips must be 1-" + RouteTestPlan.MAX_TRIPS);
        if (w[0].equals(w[1])) return new TestArgs(null, null, 0, "error: the two places must differ");
        return new TestArgs(w[0], w[1], n, null);
    }

    /** "x y z" -> {x, y, z}, else null. */
    static int[] coords(String s) {
        String t = s == null ? "" : s.trim();
        if (!t.matches("^-?\\d+ -?\\d+ -?\\d+$")) return null;
        String[] w = t.split(" ");
        return new int[]{Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2])};
    }

    /**
     * The status answer: settings, the walk side's tallies and counters, the last decision, and the planner's line.
     * walk: the walk side's counters ({@code RoutePlannerHolder.counters()}).
     */
    public static String status(boolean on, String mode, boolean available, long asked, long plain, String last, RouteStats walk,
                                RouteStats planner) {
        StringBuilder b = new StringBuilder();
        b.append("route: ").append(on ? "on" : "off").append(", mode ").append(mode).append(", planner ")
                .append(available ? "running" : "not running (every walk is plain)");
        b.append("\nwalks: ").append(asked).append(" asked the planner, ").append(plain).append(" plain; planning timeouts ")
                .append(walk.planningTimeouts()).append(", fallbacks nopath ").append(walk.fallbacksNoPath()).append(" stuck ")
                .append(walk.fallbacksStuck());
        if (walk.workerExceptions() > 0) b.append(", errors ").append(walk.workerExceptions()).append(" (last: ").append(walk.lastError()).append(')');
        b.append("\nlast walk: ").append(last == null ? "none yet" : last);
        if (planner != null) b.append("\nplanner: ").append(planner.line());
        return b.toString();
    }

    /** check's findings about routing (none when it is off or all is well). */
    public static List<SelfCheck.Finding> check(boolean on, boolean available, RouteStats walk, RouteStats planner) {
        List<SelfCheck.Finding> out = new ArrayList<>();
        if (!on) return out;
        if (!available) {
            out.add(new SelfCheck.Finding("route", "routing is on but the route planner isn't running: walks are plain Baritone walks", "route status"));
            return out;
        }
        long errors = (planner == null ? 0 : planner.workerExceptions()) + (walk == null ? 0 : walk.workerExceptions());
        if (errors > 0) {
            String last = planner != null && !planner.lastError().isEmpty() ? planner.lastError() : walk == null ? "" : walk.lastError();
            out.add(new SelfCheck.Finding("routeerrors", "the route planner had " + errors + " error" + (errors == 1 ? "" : "s")
                    + (last.isEmpty() ? "" : " (last: " + cut(last, 100) + ")"), "route status"));
        }
        long fallbacks = walk == null ? 0 : walk.fallbacksNoPath() + walk.fallbacksStuck();
        if (fallbacks >= 5) {
            out.add(new SelfCheck.Finding("routefallbacks", fallbacks + " routed walks fell back to plain (a wrong map?)", "route mode legs, or route off"));
        }
        return out;
    }

    static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
