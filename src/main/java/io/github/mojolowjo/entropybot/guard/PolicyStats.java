package io.github.mojolowjo.entropybot.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mining plan 5: the policy's counters (PLANNING.md 3: anything that runs every tick reports). Asks per site, denies by
 * reason, errors. Thread-safe,
 * lock-free on the hot path; pure Java.
 */
public final class PolicyStats {
    /** Where a question came from: the click veto, Baritone's A* hooks, our own planners. */
    public static final String SITE_CLICK = "click", SITE_ASTAR_BOXES = "astar-boxes", SITE_ASTAR_COST = "astar-cost", SITE_ASTAR_PLACE = "astar-place";

    private final Map<String, LongAdder> asks = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> denies = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> allowedByTravel = new ConcurrentHashMap<>();
    private final LongAdder errors = new LongAdder();
    private volatile String lastError;
    public static final long LOG_EVERY_MS = 10_000; // structural

    public void ask(String site) { bump(asks, site); }

    public void deny(String site, String reason) { bump(denies, site + ": " + (reason == null ? "?" : reason)); }

    /** Phase 1: a click or a plan the box rules would have refused, let through under the TRAVEL intent. */
    public void travelOverride(String site) { bump(allowedByTravel, site); }

    public void error(String what) {
        errors.increment();
        lastError = what;
    }

    public long errors() { return errors.sum(); }

    public String lastError() { return lastError; }

    public long asks() { return asks.values().stream().mapToLong(LongAdder::sum).sum(); }

    public long travelOverrides() { return allowedByTravel.values().stream().mapToLong(LongAdder::sum).sum(); }

    /** The top n entries of a counter map as "key n", biggest first. */
    static List<String> top(Map<String, LongAdder> m, int n) {
        List<Map.Entry<String, Long>> es = new ArrayList<>();
        for (var e : m.entrySet()) es.add(Map.entry(e.getKey(), e.getValue().sum()));
        es.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, es.size()); i++) out.add(es.get(i).getKey() + " " + es.get(i).getValue());
        return out;
    }

    /** One line for "fence rules" and the debug verbs. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("asks ").append(asks());
        List<String> a = top(asks, 4);
        if (!a.isEmpty()) sb.append(" (").append(String.join(", ", a)).append(")");
        List<String> d = top(denies, 5);
        sb.append(" | denies: ").append(d.isEmpty() ? "none" : String.join("; ", d));
        long t = travelOverrides();
        if (t > 0) sb.append(" | travel let through ").append(t);
        long e = errors();
        if (e > 0) sb.append(" | errors ").append(e).append(" (last: ").append(lastError).append(")");
        return sb.toString();
    }

    /** Keys carry reason text; a run that invents new reasons (exception text) stops at this many and counts "other". */
    static final int MAX_KEYS = 200; // structural

    private static void bump(Map<String, LongAdder> m, String key) {
        LongAdder a = m.get(key);
        if (a == null) {
            if (m.size() >= MAX_KEYS) key = "other";
            a = m.computeIfAbsent(key, k -> new LongAdder());
        }
        a.increment();
    }
}
