package io.github.mojolowjo.entropybot.aa.brain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * BRAIN_PLAN 2.9 (B2): measured runs per desire: runs, successes, the running average time of a success (seconds,
 * exponential, alpha {@link #ALPHA}). Cost and P(success) use them after {@link #MIN_RUNS} runs; before that the hand
 * values. Pure, synchronized (aa stats may read it off the game thread); the adapter saves {@link #toJson()} to {@code entropybot/aa-stats.json} and loads it with {@link #put}.
 */
public final class BrainStats {
    public static final int MIN_RUNS = 3;
    public static final double ALPHA = 0.3;

    public static final class Stat {
        public int n, ok;
        public double avgS = -1;
    }

    private final Map<String, Stat> stats = new LinkedHashMap<>();
    private boolean dirty;

    public synchronized void record(String id, boolean success, double seconds) {
        Stat s = stats.computeIfAbsent(id, k -> new Stat());
        s.n++;
        if (success) {
            s.ok++;
            s.avgS = s.avgS < 0 ? seconds : s.avgS + ALPHA * (seconds - s.avgS);
        }
        dirty = true;
    }

    public synchronized Stat get(String id) { return stats.get(id); }

    /** The measured cost (seconds) after MIN_RUNS runs with a success, else the hand value. */
    public synchronized double cost(String id, double hand) {
        Stat s = stats.get(id);
        return s != null && s.n >= MIN_RUNS && s.avgS >= 0 ? s.avgS : hand;
    }

    public synchronized int n(String id) { Stat s = stats.get(id); return s == null ? 0 : s.n; }

    public synchronized int ok(String id) { Stat s = stats.get(id); return s == null ? 0 : s.ok; }

    public synchronized void put(String id, int n, int ok, double avgS) {
        Stat s = new Stat();
        s.n = Math.max(0, n);
        s.ok = Math.max(0, Math.min(s.n, ok));
        s.avgS = avgS;
        stats.putIfAbsent(id, s);       // review: a late load never overwrites runs measured this session
    }

    /** True once after a change (the adapter writes then). */
    public synchronized boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public synchronized Map<String, Stat> all() { return new LinkedHashMap<>(stats); }

    public synchronized String toJson() {
        StringBuilder b = new StringBuilder("{");
        for (var e : stats.entrySet()) {
            if (b.length() > 1) b.append(',');
            b.append("\n  \"").append(e.getKey().replace("\"", "")).append("\":{\"n\":").append(e.getValue().n).append(",\"ok\":")
                    .append(e.getValue().ok).append(",\"avgS\":").append(String.format(Locale.ROOT, "%.1f", e.getValue().avgS)).append('}');
        }
        return b.append("\n}\n").toString();
    }

    /** "needs.iron 4 runs, 3 ok, 412 s" lines for aa stats. */
    public synchronized String text() {
        if (stats.isEmpty()) return "aa stats: nothing measured yet";
        StringBuilder b = new StringBuilder("aa stats (runs, ok, avg s of a success):");
        for (var e : stats.entrySet())
            b.append('\n').append(e.getKey()).append(' ').append(e.getValue().n).append(" runs, ").append(e.getValue().ok).append(" ok, ")
                    .append(e.getValue().avgS < 0 ? "-" : Math.round(e.getValue().avgS) + " s");
        return b.toString();
    }
}
