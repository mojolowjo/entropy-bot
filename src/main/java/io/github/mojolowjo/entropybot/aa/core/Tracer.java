package io.github.mojolowjo.entropybot.aa.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AA v0 (AA_PLAN 6): per-tick node statuses for the status line and the JSON snapshot, the last decisions (the same
 * shape as brain/DecisionLog: {t, ref, branch, need, chain, score, reason} plus an outcome line {t, ref, outcome}),
 * and the counters (switches, pointless, preempts, fails, lost, ticks, tickUs). Pure: JSON is built by hand, so the
 * core needs no Gson; the adapter writes the files.
 */
public final class Tracer {
    public static final int KEEP_DECISIONS = 200;

    public static final class Counts {
        public long switches, pointless, preempts, fails, lost, ticks, tickUs;
    }

    public final Counts counts = new Counts();
    private final Map<String, Status> nodes = new LinkedHashMap<>();
    private final Deque<String> decisions = new ArrayDeque<>();
    private final Deque<String> events = new ArrayDeque<>();
    private final List<java.util.function.Consumer<String>> decisionSinks = new ArrayList<>();
    private long lastRef, openRef = -1;
    private String openKind;
    private long lastSwitchAt = -1;
    private String lastSwitch;

    void node(Node n, Status s) { nodes.put(n.id, s); }

    public void beginTick() { nodes.clear(); }

    /** The statuses of the last tick (node id -> status), tick order. */
    public Map<String, Status> nodes() { return nodes; }

    /** Told of every decision and outcome line (the adapter appends them to decisions.jsonl). */
    public void sink(java.util.function.Consumer<String> s) { decisionSinks.add(s); }

    /** A task starts: one decision line. */
    public long decision(long now, String source, TaskKind kind, String chain, String reason) {
        long ref = Math.max(now, lastRef + 1);
        lastRef = ref;
        if (openRef >= 0 && openKind != null) {
            // the last task had no end line at all: something replaced it live (AA_PLAN 10: a switch)
            countSwitch(now, reason, openKind + " -> " + kind);
        }
        openRef = ref;
        openKind = kind == null ? null : kind.name();
        String line = "{\"t\":" + ref + ",\"ref\":" + ref + ",\"branch\":" + q(source) + ",\"need\":" + q(kind == null ? null : kind.name())
                + ",\"chain\":" + q(chain) + ",\"score\":0,\"reason\":" + q(reason) + ",\"outcome\":null}";
        push(decisions, line, KEEP_DECISIONS);
        for (var s : decisionSinks) s.accept(line);
        return ref;
    }

    /** The open task's end: an outcome line; "done", "failed: ...", "timeout", "stuck", "lost: ...", "cancelled: ...". */
    public void outcome(long now, String outcome) {
        if (openRef < 0) return;
        String line = "{\"t\":" + now + ",\"ref\":" + openRef + ",\"outcome\":" + q(outcome) + "}";
        push(decisions, line, KEEP_DECISIONS);
        for (var s : decisionSinks) s.accept(line);
        if (outcome.startsWith("cancelled")) countSwitch(now, outcome.startsWith("cancelled: ") ? outcome.substring(11) : outcome, openKind + " " + outcome);
        else {
            lastSwitchAt = now;
            lastSwitch = outcome + " " + openKind;
        }
        openRef = -1;
        openKind = null;
    }

    /** AA_PLAN 10: a switch replaced a live task; justified only by danger, the owner's order or aa off. */
    public static boolean justified(String why) {
        return why != null && (why.startsWith("danger") || why.startsWith("preempted by owner") || why.startsWith("aa off") || why.startsWith("the owner's stop"));
    }

    private void countSwitch(long now, String why, String text) {
        counts.switches++;
        boolean ok = justified(why);
        if (!ok) counts.pointless++;
        lastSwitchAt = now;
        lastSwitch = text + (ok ? "" : " (pointless)");
    }

    /** A note for the status line ("paused slot: danger"). */
    public void event(String text) { push(events, text, 20); }

    public List<String> decisions() { return new ArrayList<>(decisions); }

    public List<String> events() { return new ArrayList<>(events); }

    /** "2m ago: done TOOLS" or "never". */
    public String lastSwitchText(long now) {
        if (lastSwitchAt < 0) return "never";
        long s = (now - lastSwitchAt) / 1000;
        return (s < 120 ? s + "s" : s / 60 + "m") + " ago: " + lastSwitch;
    }

    /** The snapshot JSON (AA_PLAN 6), with the caller's header fields and the inputs block. */
    public String snapshot(long now, boolean on, String driver, String taskJson, Inputs in) {
        StringBuilder b = new StringBuilder("{\"t\":").append(now).append(",\"on\":").append(on).append(",\"driver\":").append(q(driver));
        b.append(",\"task\":").append(taskJson == null ? "null" : taskJson).append(",\"nodes\":[");
        boolean first = true;
        for (Map.Entry<String, Status> e : nodes.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(q(e.getKey())).append(",\"s\":").append(q(e.getValue().name())).append('}');
        }
        b.append("],\"inputs\":{");
        float[] v = in.vector();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) b.append(',');
            b.append(q(Inputs.NAMES[i])).append(':').append(Math.round(v[i] * 1000) / 1000.0);
        }
        b.append("},\"counts\":{\"switches\":").append(counts.switches).append(",\"pointless\":").append(counts.pointless)
                .append(",\"preempts\":").append(counts.preempts).append(",\"fails\":").append(counts.fails).append(",\"lost\":").append(counts.lost)
                .append(",\"ticks\":").append(counts.ticks).append(",\"tickUs\":").append(counts.tickUs).append("}}");
        return b.toString();
    }

    private static void push(Deque<String> d, String s, int keep) {
        d.addLast(s);
        while (d.size() > keep) d.removeFirst();
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
