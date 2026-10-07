package io.github.mojolowjo.entropybot.vocab;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * V1b (VOCABULARY 5): {@code queue <task>} queues a task after the current one. Only tasks with a finish may be queued
 * (building, crafting, mining, gathering...): {@code follow}, {@code escort}, {@code defend}, {@code guard},
 * {@code repeat forever} and a {@code twerk} without seconds never end, so they are refused with the reason. A queued
 * task waits while an escort, defend or guard runs (until {@code dismiss} or {@code stop}). Pure.
 */
public final class QueueRules {
    private QueueRules() {}

    public static final int MAX = 10;
    static final Set<String> ENDLESS = Set.of("follow", "escort", "defend", "guard");
    /** Answers, not tasks: nothing to wait for. */
    static final Set<String> INSTANT = Set.of("status", "inv", "help", "queue", "places", "have", "stock", "needs", "goals", "kinds", "stop", "dismiss", "confirm", "check");

    /** Null when the line may be queued, else the refusal. */
    public static String refusal(String line) {
        String l = line == null ? "" : line.trim();
        if (l.isEmpty()) return "usage: queue <task> (queue alone shows the queue)";
        for (String step : l.split("(?i)\\s+then\\s+|\\s*;\\s*")) {
            List<String> w = List.of(step.trim().toLowerCase(Locale.ROOT).split("\\s+"));
            String v = w.get(0);
            if (ENDLESS.contains(v))
                return "error: " + v + " can't be queued - it never finishes; queue after it: start it, then queue <task>";
            if (v.equals("repeat") && w.size() > 1 && w.get(1).equals("forever")) return "error: repeat forever can't be queued - it never finishes";
            if (v.equals("debug") && w.size() > 1 && (w.get(1).equals("watch") || w.get(1).equals("twerk") && w.size() == 2))
                return "error: " + w.get(1) + " can't be queued - it never finishes";
            if (INSTANT.contains(v)) return "error: " + v + " answers at once - just say it";
        }
        return null;
    }

    /** May the next queued task start? Nothing runs, and no escort/defend/guard holds the bot. */
    public static boolean mayStart(boolean busy, boolean holding) {
        return !busy && !holding;
    }

    /** "queue": the running chain (or nothing) and what waits. */
    public static String show(String running, List<String> waiting) {
        String r = running == null || running.isEmpty() ? "nothing running" : running;
        if (waiting.isEmpty()) return r + " - nothing queued (queue <task>)";
        StringBuilder sb = new StringBuilder(r + " - queued: ");
        for (int i = 0; i < waiting.size(); i++) sb.append(i == 0 ? "" : ", ").append(i + 1).append(". ").append(waiting.get(i));
        return sb.toString();
    }
}
