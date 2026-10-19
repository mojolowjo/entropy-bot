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

    // ---- aa-food: the measured food drain per task kind (FoodModel) ----

    /** Samples before a kind's measured drain replaces the hand rate; the shortest sample (minutes). */
    public static final int DRAIN_MIN_N = 2;
    public static final double DRAIN_MIN_MINUTES = 1;
    /** Saved as "drain.KIND" next to the desires (n/ok/avgS too, so an older loader reads it as a harmless row). */
    public static final String DRAIN_PREFIX = "drain.";

    /** kind -> {samples, energy per minute (exponential, ALPHA)}. */
    private final Map<String, double[]> drain = new LinkedHashMap<>();

    /** One sample: {@code points} of food + saturation spent over {@code minutes} of one kind of work. False: too short. */
    public synchronized boolean recordDrain(String kind, double points, double minutes) {
        if (kind == null || minutes < DRAIN_MIN_MINUTES || points < 0 || Double.isNaN(points)) return false;
        double rate = points / minutes;
        double[] d = drain.computeIfAbsent(kind, k -> new double[]{0, -1});
        d[1] = d[1] < 0 ? rate : d[1] + ALPHA * (rate - d[1]);
        d[0]++;
        dirty = true;
        return true;
    }

    /** The measured drain (energy per minute) after DRAIN_MIN_N samples, else {@code hand}. */
    public synchronized double drain(String kind, double hand) {
        double[] d = drain.get(kind);
        return d != null && d[0] >= DRAIN_MIN_N && d[1] >= 0 ? d[1] : hand;
    }

    /** The sample-weighted mean of the measured kinds (DRAIN_MIN_N+ samples), else {@code hand}. */
    public synchronized double meanDrain(double hand) {
        double sum = 0, n = 0;
        for (double[] d : drain.values()) if (d[0] >= DRAIN_MIN_N && d[1] >= 0) { sum += d[0] * d[1]; n += d[0]; }
        return n == 0 ? hand : sum / n;
    }

    public synchronized int drainN(String kind) { double[] d = drain.get(kind); return d == null ? 0 : (int) d[0]; }

    /** From the file; a late load never overwrites this session's samples. */
    public synchronized void putDrain(String kind, int n, double perMin) {
        if (kind == null || n <= 0 || perMin < 0 || Double.isNaN(perMin)) return;
        drain.putIfAbsent(kind, new double[]{n, perMin});
    }

    public synchronized String toJson() {
        StringBuilder b = new StringBuilder("{");
        for (var e : stats.entrySet()) {
            if (b.length() > 1) b.append(',');
            b.append("\n  \"").append(e.getKey().replace("\"", "")).append("\":{\"n\":").append(e.getValue().n).append(",\"ok\":")
                    .append(e.getValue().ok).append(",\"avgS\":").append(String.format(Locale.ROOT, "%.1f", e.getValue().avgS)).append('}');
        }
        for (var e : drain.entrySet()) {
            if (b.length() > 1) b.append(',');
            int n = (int) e.getValue()[0];
            b.append("\n  \"").append(DRAIN_PREFIX).append(e.getKey().replace("\"", "")).append("\":{\"n\":").append(n).append(",\"ok\":").append(n)
                    .append(",\"avgS\":-1.0,\"perMin\":").append(String.format(Locale.ROOT, "%.3f", e.getValue()[1])).append('}');
        }
        return b.append("\n}\n").toString();
    }

    /** "needs.iron 4 runs, 3 ok, 412 s" lines for aa stats. */
    public synchronized String text() {
        if (stats.isEmpty() && drain.isEmpty()) return "aa stats: nothing measured yet";
        StringBuilder b = new StringBuilder("aa stats (runs, ok, avg s of a success):");
        for (var e : stats.entrySet())
            b.append('\n').append(e.getKey()).append(' ').append(e.getValue().n).append(" runs, ").append(e.getValue().ok).append(" ok, ")
                    .append(e.getValue().avgS < 0 ? "-" : Math.round(e.getValue().avgS) + " s");
        for (var e : drain.entrySet())
            b.append("\nfood drain ").append(e.getKey()).append(' ').append((int) e.getValue()[0]).append(" samples, ")
                    .append(String.format(Locale.ROOT, "%.2f", e.getValue()[1])).append(" per min");
        return b.toString();
    }
}
