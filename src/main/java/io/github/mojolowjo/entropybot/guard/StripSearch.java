package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 0.39.z77 mineintent (run DB 01:08:24 "travel may place at 25 5 5 (allowed x3 (travel))" inside the strip of 01:07:02-01:10:12;
 * DE #3 35 -20 18, the head under gravel, TRAVEL's "a falling block above"): the cost hook prices by the intent in force when
 * a search context is built ({@code CostAdvisorHook.begin} -> {@code intentNow}), and the strip step set none, so its searches
 * ran on the walk's TRAVEL while its "next cell" rule line asked MINE. The strip now holds MINE for its own searches (its
 * start, each next segment, every running tick it is not held by a reflex) and puts the intent before it back at its end,
 * only when the intent is still its own (a job that set another during the strip keeps it).
 *
 * <p>The refuse probe: while a strip runs with the probe on (debug.walkProbe or debug.stepProbe; off by default), the cost
 * hook says one line per cell it refuses, once per cell per search ({@link Said}, one per search token), so the next run
 * has the search's own answer beside the "next cell" line. Pure (JUnit: Z77StripMineIntentTest). Loader notes: none.
 */
public final class StripSearch {
    /** The mod's one strip search state (StripStep sets it, CostAdvisorHook reads the probe on the pathfinder thread). */
    public static final StripSearch INSTANCE = new StripSearch();

    /** Who set the intent, as the status line's "(by)" says. */
    public static final String BY = "strip";

    /** The most refuse lines one search says (Tuning debug.stripRefuseMaxLines); the next one says the rest are not printed. */
    public static int maxLines() { return Math.max(1, io.github.mojolowjo.entropybot.aa.brain.Tuning.i("debug.stripRefuseMaxLines")); }

    /** The job intent the strip sets and puts back (PolicyRuntime in the game; a plain holder in JUnit). */
    public interface Store {
        Intent intent();
        String by();
        void set(Intent i, String by);
    }

    private Intent saved;
    private String savedBy;
    private boolean on;
    private volatile boolean probe;
    /** The line cap read on the client thread at begin / hold (the pathfinder thread reads no Tuning). */
    private volatile int cap = 1;
    /** The mixin fallback's searches carry no token: one set for the strip, new at each begin. */
    private volatile Said fallback = new Said();

    /** A strip starts: the intent before it kept (once per strip), MINE set for its searches. */
    public synchronized void begin(Store s, boolean probeOn) {
        if (!on) {
            saved = s.intent();
            savedBy = s.by();
            on = true;
        }
        fallback = new Said();
        if (probeOn) cap = maxLines();
        probe = probeOn;
        s.set(Intent.MINE, BY);
    }

    /** A next segment, or a running tick not held by a reflex: MINE again if something else set the intent meanwhile. */
    public synchronized void hold(Store s, boolean probeOn) {
        if (!on) return;
        if (probeOn && !probe) cap = maxLines();
        probe = probeOn;
        if (s.intent() != Intent.MINE) s.set(Intent.MINE, BY);
    }

    /** The strip ended (done, failed, cancelled): the intent before it back if the intent is still the strip's own. */
    public synchronized void end(Store s) {
        if (!on) return;
        if (s.intent() == Intent.MINE && BY.equals(s.by())) s.set(saved == null ? Intent.TRAVEL : saved, saved == null ? "strip end" : savedBy);
        on = false;
        probe = false;
        saved = null;
        savedBy = null;
    }

    /** A strip holds the intent now. */
    public synchronized boolean running() { return on; }

    /** The refuse probe is on: a strip runs and debug.walkProbe or debug.stepProbe was on at its last tick. Any thread. */
    public boolean probing() { return probe; }

    /** A new search's said set when the probe is on (the token carries it), else null. */
    public Said forSearch() { return probe ? new Said() : null; }

    /**
     * The refuse line for this cell, or null: the probe off, or the cell said already in this search ({@code said}; null =
     * the mixin fallback's strip-wide set). Past the cap ({@link #maxLines}) one line says the rest are not printed.
     */
    public String refuse(Said said, int x, int y, int z, String block, Intent in, String action, String rule, String reason, double cost) {
        if (!probe) return null;
        Said s = said != null ? said : fallback;
        if (!s.first(x, y, z)) return null;
        int n = s.lines.incrementAndGet();
        int max = cap;
        if (n > max + 1) return null;
        if (n == max + 1) return "probe strip refuse: " + max + " lines this search, the rest not printed";
        return line(x, y, z, block, in, action, rule, reason, cost);
    }

    /** Baritone's price of an impossible move. */
    private static final double COST_INF = 1000000;   // game fact: Baritone ActionCosts.COST_INF

    /** {@code probe strip refuse: x y z <block>: <intent> <action> <rule> (<reason>) cost <c>}. */
    public static String line(int x, int y, int z, String block, Intent in, String action, String rule, String reason, double cost) {
        String c = Double.isInfinite(cost) || cost >= COST_INF ? "inf" : String.format(Locale.ROOT, "%.2f", cost);
        return "probe strip refuse: " + x + " " + y + " " + z + " " + (block == null ? "?" : block) + ": "
                + (in == null ? "none" : in.name().toLowerCase(Locale.ROOT)) + " " + action + " " + (rule == null ? "-" : rule)
                + (reason == null ? "" : " (" + reason + ")") + " cost " + c;
    }

    /** One search's cells said (identity equality: two searches never share one). Thread-safe. */
    public static final class Said {
        private final Set<Long> cells = ConcurrentHashMap.newKeySet();
        private final AtomicInteger lines = new AtomicInteger();

        boolean first(int x, int y, int z) {
            return cells.add(((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL));
        }
    }
}
