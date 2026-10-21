package io.github.mojolowjo.entropybot.gather;

import io.github.mojolowjo.entropybot.aa.brain.Failures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * regression o1 (0.39.p1): where the cobblestone quarry runs is a priced choice among stone sites, not "here". The sites
 * are my feet plus the exposed stone columns within {@link #RADIUS} (the adapter's loaded-chunk pass, cached
 * {@link #CACHE_MS}); each is priced walk + dig (the planned cells). A site whose plan meets water or lava (or that
 * failed when dug) is charged in {@link Failures} under desire {@code quarry}, option {@code "<x> <y> <z>"}, and every
 * caller (bootstrap, TOOLS, TORCHES: all go through gather) skips it while held. Pure (JUnit: QuarrySitesTest).
 * Loader notes: none (the world reads are the caller's StonePit.World).
 */
public final class QuarrySites {
    private QuarrySites() {}

    public static final String DESIRE = "site.quarry";        // review p2: a prefix no desire uses
    public static int radius() { return (int) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("quarry.radius"); }
    public static long cacheMs() { return (long) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("quarry.cacheMs"); }
    /** Dig cost per planned cell, in walk-blocks. */
    public static double dig() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("quarry.digK"); }

    private static volatile Failures failures = new Failures();

    /** The AA brain's failure record (so aa why shows the holds); a private one until bound. */
    public static void bind(Failures f) { if (f != null) failures = f; }

    public static Failures failures() { return failures; }

    public static String option(int[] feet) { return feet[0] + " " + feet[1] + " " + feet[2]; }

    /** Charges a failed site (water or lava by it, dug and failed): held for every caller. */
    public static void charge(int[] feet, String reason, long now) {
        failures.failed(DESIRE, option(feet), reason, now, null);
    }

    /** review p2: the walk to a site ended more than 2 blocks from it (it failed, whatever its text said). */
    public static boolean missed(int[] site, int[] at) { return dist(site, at) > 2; }

    public static boolean held(int[] feet, long now) { return failures.blocked(DESIRE, option(feet), now); }

    public interface Planner { StonePit.Plan plan(int[] feet); }

    /**
     * review p2: the area facts inside the planner: a plan with a cell where {@code banned} names an area (main, safe,
     * keep-out) is an error, so that site is charged and dropped before any walk.
     */
    public static Planner avoiding(Planner pl, java.util.function.Function<int[], String> banned) {
        return f -> {
            StonePit.Plan p = pl.plan(f);
            if (p.err() != null) return p;
            for (int[] c : p.cells()) {
                String a = banned.apply(c);
                if (a != null) return new StonePit.Plan(List.of(), 0, p.columns(), "error: the quarry would dig into " + a + " at " + c[0] + " " + c[1] + " " + c[2]);
            }
            return p;
        };
    }

    /** site: the feet to dig from (null: none); dropped: the sites left out and why; terms for the log. */
    public record Pick(int[] site, StonePit.Plan plan, double price, List<String> dropped, String terms) {
        public boolean here(int[] me) { return site != null && site[0] == me[0] && site[2] == me[2] && Math.abs(site[1] - me[1]) <= 1; }
    }

    static double dist(int[] a, int[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The cheapest site: candidates in walking order, each held one dropped, each planned one priced walk + DIG x cells;
     * a plan error is charged and dropped with its reason. Stops once the walk alone costs more than the best price.
     */
    public static Pick choose(List<int[]> candidates, int[] me, Planner pl, long now) {
        List<int[]> c = new ArrayList<>(candidates);
        c.sort(Comparator.comparingDouble(s -> dist(s, me)));
        List<String> dropped = new ArrayList<>();
        int[] best = null;
        StonePit.Plan bestPlan = null;
        double bestPrice = Double.MAX_VALUE;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int[] s : c) {
            if (!seen.add(option(s))) continue;
            double walk = dist(s, me);
            if (walk > bestPrice) break;
            if (held(s, now)) {
                dropped.add(option(s) + ": " + failures.held(DESIRE, option(s), now));
                continue;
            }
            StonePit.Plan p = pl.plan(s);
            if (p.err() != null) {
                String why = p.err().replaceFirst("^error: ", "").replaceFirst(" - next:.*$", "");
                charge(s, why, now);
                dropped.add(option(s) + ": " + why);
                continue;
            }
            double price = walk + dig() * p.cells().size();
            if (price < bestPrice) { bestPrice = price; best = s; bestPlan = p; }
        }
        String terms = best == null
                ? "no quarry site within " + radius() + " (" + (dropped.isEmpty() ? "no exposed stone seen" : String.join("; ", dropped)) + ")"
                : String.format(Locale.ROOT, "quarry at %s: walk %.0f + dig %d cells = %.0f%s", option(best), dist(best, me), bestPlan.cells().size(), bestPrice,
                        dropped.isEmpty() ? "" : " (dropped " + String.join("; ", dropped) + ")");
        return new Pick(best, bestPlan, best == null ? 0 : bestPrice, dropped, terms);
    }

    // ---- the candidate cache (the adapter's chunk pass) ----

    private static List<int[]> cached = List.of();
    private static long cachedAt = Long.MIN_VALUE / 2;
    private static int[] cachedAround;

    /** The scan's result around {@code me}, reused for {@link #CACHE_MS} while I stay within 8 blocks. */
    public static synchronized List<int[]> candidates(int[] me, long now, java.util.function.Supplier<List<int[]>> scan) {
        if (now - cachedAt > cacheMs() || cachedAround == null || dist(cachedAround, me) > 8) {
            cached = List.copyOf(scan.get());
            cachedAt = now;
            cachedAround = me.clone();
        }
        return cached;
    }
}
