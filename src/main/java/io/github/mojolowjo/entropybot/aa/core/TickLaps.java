package io.github.mojolowjo.entropybot.aa.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * 0.39.z62 item 4 (run S 04:57:07 / 04:57:12: client ticks of 3050 and 5586 ms, all in "guard+commands (aa)", the section
 * the load probe could not split; design D3 3.1 "probe aatick"): the AA tick's stopwatch. While {@link #on} (the load probe
 * on, set per AA tick by its caller), {@link #begin} starts a tick, {@link #lap} ends a named section, {@link #resolved}
 * counts one chain resolve (Goals.chain) and its time; {@link #line} is the "probe aatick:" text when the tick took longer
 * than the limit. Off: every entry returns on one field read, nothing is built. The clock is a function (nanoseconds), so
 * JUnit drives it. Pure (JUnit: Z62RunSTest). Loader notes: none.
 */
public final class TickLaps {
    /** The AA tick's one stopwatch (client thread only). */
    public static final TickLaps AA = new TickLaps(System::nanoTime);

    private final LongSupplier clock;
    private volatile boolean on;
    private long start, last, resolveNs;
    private int resolves;
    private final List<String> names = new ArrayList<>();
    private final List<Long> ns = new ArrayList<>();

    public TickLaps(LongSupplier clock) { this.clock = clock; }

    public boolean on() { return on; }

    /** A tick starts; {@code active}: the probe is on for it (off: nothing is measured until the next begin). */
    public void begin(boolean active) {
        on = active;
        if (!active) return;
        names.clear();
        ns.clear();
        resolves = 0;
        resolveNs = 0;
        clientNodes = 0;
        clientResolved = 0;
        clientReused = 0;
        playbooks = 0;
        playbookNs = 0;
        split.clear();
        asks.clear();
        nearby.clear();
        tech.clear();
        owner = Thread.currentThread();
        renewSets = 0;
        renewMax = 0;
        renewMaxName = null;
        start = clock.getAsLong();
        last = start;
    }

    /** The section {@code name} ended now (its time: since the last lap or the begin). */
    public void lap(String name) {
        if (!on) return;
        long now = clock.getAsLong();
        names.add(name);
        ns.add(now - last);
        last = now;
    }

    /** One chain resolve took {@code nanos}. */
    public void resolved(long nanos) {
        if (!on) return;
        resolves++;
        resolveNs += Math.max(0, nanos);
    }

    /** 0.39.z78 D6 (run DT): this tick's resolves on the client thread, their node counts summed (PlanNodes.last of this thread). */
    private int clientNodes, clientResolved, clientReused;

    /** 0.39.z78 D6 (run DT): a resolve on the client tick read {@code nodes}, resolved {@code resolved} of them again and reused {@code reused}. */
    public void resolvedNodes(int nodes, int resolved, int reused) {
        if (!on) return;
        clientNodes += nodes;
        clientResolved += resolved;
        clientReused += reused;
    }

    private int playbooks;
    private long playbookNs;

    /**
     * 0.39.z77 J2 (manager's R3: the playbook must not add to the 1.0-1.4 s ticks of runs AZ/BC): one batch comparison or
     * playbook re-price took {@code nanos} (BatchWay); said in the line as "| playbook N = M ms" when any ran this tick.
     */
    public void playbook(long nanos) {
        if (!on) return;
        playbooks++;
        playbookNs += Math.max(0, nanos);
    }

    /** 0.39.z78 D6 (run DP): this tick's client part of the playbook by part (derive, check, snapshot, apply), nanoseconds. */
    private final java.util.Map<String, Long> split = new java.util.LinkedHashMap<>();

    /** 0.39.z78 D6 (run DP): one part of the playbook's client time this tick (BatchWay, PlannerPort); said as "playbook split". */
    public void split(String part, long nanos) {
        if (!on) return;
        split.merge(part, Math.max(0, nanos), Long::sum);
    }

    /** 0.39.z78 D6 (run DQ): this tick's live fact asks of the planner (check and snapshot) by field: {count, nanoseconds}. */
    private final java.util.Map<String, long[]> asks = new java.util.HashMap<>();

    /** 0.39.z78 D6 (run DQ): one live fact ask of the planner (PlannerPort's check, PlannerSnapshot.take); said as "planner asks". */
    public void ask(String field, long nanos) {
        if (!on) return;
        long[] a = asks.computeIfAbsent(field, k -> new long[2]);
        a[0]++;
        a[1] += Math.max(0, nanos);
    }

    /** 0.39.z78 D6 (run DR round): this tick's nearby lookups by kind (renew, skip, scans): {count, nanoseconds}, in first-seen order. */
    private final java.util.Map<String, long[]> nearby = new java.util.LinkedHashMap<>();

    /**
     * 0.39.z78 D6 (run DR round: "seen" carried 85-90% of the planner's asks, bimodal - a cache hit or a renewal): one nearby
     * lookup's part ({@code kind}: "renew" a cached answer made again, "skip" a held spot's fresh lookup, "scans" Baritone's
     * world scans inside them, "deferred" a renewal put off to a later tick by D10's RenewBudget); said as "nearby: renew N = ms, ...".
     */
    public void nearby(String kind, long nanos) {
        if (!on) return;
        long[] a = nearby.computeIfAbsent(kind, k -> new long[2]);
        a[0]++;
        a[1] += Math.max(0, nanos);
    }

    /** job-tick3 (run JAX: "tree" 785 ms over 22 aa ticks, max 147.5): this tick's tech tree calls by kind: {count, nanoseconds}. */
    private final java.util.Map<String, long[]> tech = new java.util.LinkedHashMap<>();
    private volatile Thread owner;

    /** job-tick3: one tech tree call ({@code kind}: norm, best, share, ideas, top); said as "tech tree: norm N = ms, ...". */
    public void tech(String kind, long nanos) {
        if (!on || Thread.currentThread() != owner) return;     // a worker's call is not this tick's (and the map is the tick's)
        long[] a = tech.computeIfAbsent(kind, k -> new long[2]);
        a[0]++;
        a[1] += Math.max(0, nanos);
    }

    /** 0.39.z78 D6: the block sets renewed this tick, and the largest (its size and its blocks' names). */
    private int renewSets, renewMax;
    private String renewMaxName;

    /** 0.39.z78 D6 (run DR round): one block set renewed ({@code size} blocks, {@code name} its blocks); said with the renewals. */
    public void nearbySet(int size, String name) {
        if (!on) return;
        renewSets++;
        if (size > renewMax) { renewMax = size; renewMaxName = name; }
    }

    /** 0.39.z78 D6: a trip was asked of the planner this tick (BatchWay; the line then carries the planner's numbers). */
    public boolean planned() { return on && playbooks > 0; }

    /** Now on this stopwatch's clock (0 when off: the caller skips the resolve count). */
    public long now() { return on ? clock.getAsLong() : 0; }

    /**
     * The "probe aatick:" text when the tick from {@link #begin} to now took more than {@code limitMs}, else null (off: null).
     * "t=12077 | aa tick 5584.9 ms (over debug.aaTickMs 20) | worldFacts 0.1, senses 0.0, ... tree 5580.2, ... | resolves 3 = 5570.0 ms".
     * The sections over a tenth of a millisecond are named, the rest summed as "others".
     */
    public String line(long tick, double limitMs) {
        if (!on) return null;
        double total = (clock.getAsLong() - start) / 1e6;     // structural: ns per ms
        if (!(total > limitMs)) return null;
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "t=%d | aa tick %.1f ms (over debug.aaTickMs %.0f) |", tick, total, limitMs));
        double others = 0;
        int named = 0;
        for (int i = 0; i < names.size(); i++) {
            double ms = ns.get(i) / 1e6;     // structural: ns per ms
            if (ms < 0.1) { others += ms; continue; }       // structural: below a tenth of a ms is noise in this line
            b.append(named++ == 0 ? " " : ", ").append(names.get(i)).append(String.format(Locale.ROOT, " %.1f", ms));
        }
        if (named == 0) b.append(" no section over 0.1 ms");
        if (others > 0) b.append(String.format(Locale.ROOT, ", others %.1f", others));
        b.append(String.format(Locale.ROOT, " | resolves %d = %.1f ms", resolves, resolveNs / 1e6));     // structural: ns per ms
        // 0.39.z78 D6 (run DT): the client thread's own node counts (the "planner:" part is the worker's)
        if (resolves > 0) b.append(String.format(Locale.ROOT, " (client nodes %d, re-resolved %d, reused %d)", clientNodes, clientResolved, clientReused));
        if (playbooks > 0) b.append(String.format(Locale.ROOT, " | playbook %d = %.1f ms", playbooks, playbookNs / 1e6));     // structural: ns per ms
        if (playbooks > 0) {
            // 0.39.z78 D6 (run DP): where the playbook's client time went this tick; rest: shape, refs, the line
            long parts = 0;
            b.append(" | playbook split:");
            for (String k : List.of("derive", "check", "snapshot", "apply")) {
                long v = split.getOrDefault(k, 0L);
                parts += v;
                b.append(String.format(Locale.ROOT, " %s %.1f,", k, v / 1e6));     // structural: ns per ms
            }
            b.append(String.format(Locale.ROOT, " rest %.1f ms", Math.max(0, playbookNs - parts) / 1e6));     // structural: ns per ms
        }
        if (!asks.isEmpty()) b.append(" | ").append(asksText());
        if (!nearby.isEmpty()) {
            b.append(" | nearby:");
            int i = 0;
            for (var e : nearby.entrySet()) b.append(i++ == 0 ? " " : ", ").append(e.getKey()).append(String.format(Locale.ROOT, " %d = %.1f", e.getValue()[0], e.getValue()[1] / 1e6));     // structural: ns per ms
            b.append(" ms");
            if (renewSets > 0) b.append(" (").append(renewSets).append(" block set").append(renewSets == 1 ? "" : "s").append(" renewed, the largest ")
                    .append(renewMax).append(" block").append(renewMax == 1 ? "" : "s").append(renewMaxName == null ? "" : ": " + renewMaxName).append(")");
        }
        if (!tech.isEmpty()) {
            b.append(" | tech tree:");
            int i = 0;
            for (var e : tech.entrySet()) b.append(i++ == 0 ? " " : ", ").append(e.getKey()).append(String.format(Locale.ROOT, " %d = %.1f", e.getValue()[0], e.getValue()[1] / 1e6));     // structural: ns per ms
            b.append(" ms");
        }
        return b.toString();
    }

    /**
     * 0.39.z78 D6 (run DQ): "planner asks 812 = 120.3 ms: price 640 = 95.1, strip 45 = 20.3, ..., others 3 = 0.1" - the live
     * fact asks of this tick, the dearest fields first (the first five named, the rest summed).
     */
    String asksText() {
        long n = 0, t = 0;
        for (long[] a : asks.values()) { n += a[0]; t += a[1]; }
        List<java.util.Map.Entry<String, long[]>> l = new java.util.ArrayList<>(asks.entrySet());
        l.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "planner asks %d = %.1f ms:", n, t / 1e6));     // structural: ns per ms
        long rn = 0, rt = 0;
        for (int i = 0; i < l.size(); i++) {
            long[] a = l.get(i).getValue();
            if (i >= 5) { rn += a[0]; rt += a[1]; continue; }      // structural: five fields named, a line a person reads
            b.append(i == 0 ? " " : ", ").append(l.get(i).getKey()).append(String.format(Locale.ROOT, " %d = %.1f", a[0], a[1] / 1e6));     // structural: ns per ms
        }
        if (rn > 0) b.append(String.format(Locale.ROOT, ", others %d = %.1f", rn, rt / 1e6));     // structural: ns per ms
        return b.toString();
    }
}
