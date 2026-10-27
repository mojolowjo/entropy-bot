package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>0.39.z77 strip 4 (manager 2026-10-27; run DF: 4,867 refuse lines for 564 cells, none past the cap - each of ~100
 * searches said its own ~40-58 far lava / water cells again): per search, each (cell, rule) once; a per-cell line only for
 * the strip's next cell (Baritone's goal, feet and head) and its neighbours ({@link #focus}); every refusal counted into one
 * summary line per search ({@link Said#summary}), said when the next search starts or the strip ends. The cap holds.
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
    private volatile Said fallback = new Said(null);
    /** 0.39.z77 strip 4: the strip's next cell (Baritone's goal: the feet cell, {x, y, z}); null: not known. */
    private volatile int[] focus;
    /** 0.39.z77 strip 4: the last search's said set, summed up when the next one starts or the strip ends. */
    private final java.util.concurrent.atomic.AtomicReference<Said> last = new java.util.concurrent.atomic.AtomicReference<>();

    /** 0.39.z77 strip 4: the strip's next cell as Baritone's goal names it ({x, y, z}; null: not known). Client thread. */
    public void focus(int[] cell) { focus = cell == null || cell.length < 3 ? null : cell.clone(); }   // structural: {x, y, z}

    /** A strip starts: the intent before it kept (once per strip), MINE set for its searches. */
    public synchronized void begin(Store s, boolean probeOn) {
        if (!on) {
            saved = s.intent();
            savedBy = s.by();
            on = true;
        }
        fallback = new Said(null);
        focus = null;
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

    /**
     * The strip ended (done, failed, cancelled): the intent before it back if the intent is still the strip's own.
     * 0.39.z77 strip 4: returns the summary lines not said yet (the last search's, the fallback's; empty: none) for the
     * caller's log.
     */
    public synchronized java.util.List<String> end(Store s) {
        if (!on) return java.util.List.of();
        if (s.intent() == Intent.MINE && BY.equals(s.by())) s.set(saved == null ? Intent.TRAVEL : saved, saved == null ? "strip end" : savedBy);
        on = false;
        probe = false;
        saved = null;
        savedBy = null;
        java.util.List<String> out = new java.util.ArrayList<>();
        Said l = last.getAndSet(null);
        String ll = l == null ? null : l.summary();
        if (ll != null) out.add(ll);
        Said fb = fallback;
        fallback = new Said(null);
        String fl = fb.summary(focus);
        if (fl != null) out.add(fl);
        focus = null;
        return out;
    }

    /** A strip holds the intent now. */
    public synchronized boolean running() { return on; }

    /** The refuse probe is on: a strip runs and debug.walkProbe or debug.stepProbe was on at its last tick. Any thread. */
    public boolean probing() { return probe; }

    /** A new search's said set when the probe is on (the token carries it), else null. */
    public Said forSearch() { return forSearch(null); }

    /**
     * 0.39.z77 strip 4: a new search's said set when the probe is on (else null), holding the strip's next cell as it is now;
     * the last search's summary line goes to {@code say} (null: dropped - JUnit only). Any thread.
     */
    public Said forSearch(java.util.function.Consumer<String> say) {
        Said s = probe ? new Said(focus) : null;
        Said was = last.getAndSet(s);
        String l = was == null ? null : was.summary();
        if (l != null && say != null) say.accept(l);
        return s;
    }

    /**
     * The refuse line for this cell, or null: the probe off, the (cell, rule) said already in this search ({@code said};
     * null = the mixin fallback's strip-wide set), or (0.39.z77 strip 4) the cell not the strip's next cell or one of its
     * neighbours - counted into the search's summary line then. Past the cap ({@link #maxLines}) one line says the rest are
     * not printed.
     */
    public String refuse(Said said, int x, int y, int z, String block, Intent in, String action, String rule, String reason, double cost) {
        if (!probe) return null;
        Said s = said != null ? said : fallback;
        if (!s.first(x, y, z, rule)) return null;
        int[] f = said != null ? s.focus : focus;
        if (!near(f, x, y, z)) return null;
        int n = s.lines.incrementAndGet();
        int max = cap;
        if (n > max + 1) return null;
        if (n == max + 1) return "probe strip refuse: " + max + " lines this search, the rest not printed";
        s.printed.incrementAndGet();
        return line(x, y, z, block, in, action, rule, reason, cost);
    }

    /**
     * The strip's next cell (the feet cell {@code f}; the head is the one above) or a neighbour of either: one block off on
     * x and z, from the block under the feet to the one over the head. {@code f} null (not known): no cell is near.
     */
    static boolean near(int[] f, int x, int y, int z) {
        return f != null && Math.abs(x - f[0]) <= 1 && Math.abs(z - f[2]) <= 1 && y >= f[1] - 1 && y <= f[1] + 2;   // game fact: the feet and head cells
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

    /**
     * One search's (cell, rule) pairs said, counted per rule for its summary (identity equality: two searches never share
     * one). Thread-safe.
     */
    public static final class Said {
        private final Set<String> pairs = ConcurrentHashMap.newKeySet();
        private final Set<Long> cells = ConcurrentHashMap.newKeySet();
        private final Map<String, AtomicInteger> rules = new ConcurrentHashMap<>();
        private final AtomicInteger lines = new AtomicInteger();
        private final AtomicInteger printed = new AtomicInteger();
        /** The strip's next cell when this search started (null: not known, or the fallback's - it reads the live one). */
        private final int[] focus;

        Said(int[] focus) { this.focus = focus; }

        boolean first(int x, int y, int z, String rule) {
            long c = ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
            String r = rule == null ? "-" : rule;
            if (!pairs.add(c + " " + r)) return false;
            cells.add(c);
            rules.computeIfAbsent(r, k -> new AtomicInteger()).incrementAndGet();
            return true;
        }

        /**
         * "probe strip refuse: 46 cells refused: floor x 20, lava x 13, water x 13 (this search; 2 printed: the next cell
         * 30 16 6 and its neighbours)"; null: nothing refused. Rules by count, then name.
         */
        public String summary() { return summary(focus); }

        /** {@link #summary()} naming the next cell {@code focus} (the fallback's: the strip's live one). */
        String summary(int[] focus) {
            if (cells.isEmpty()) return null;
            List<Map.Entry<String, Integer>> rs = new ArrayList<>();
            for (var e : rules.entrySet()) rs.add(Map.entry(e.getKey(), e.getValue().get()));
            rs.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            List<String> parts = new ArrayList<>();
            for (var e : rs) parts.add(e.getKey() + " x " + e.getValue());
            String next = focus == null ? "the next cell not known" : "the next cell " + focus[0] + " " + focus[1] + " " + focus[2] + " and its neighbours";
            return "probe strip refuse: " + cells.size() + " cells refused: " + String.join(", ", parts) + " (this search; "
                    + printed.get() + " printed: " + next + ")";
        }
    }
}
