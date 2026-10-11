package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * B1 (BRAIN_PLAN 4.5): the decision log, the later learning step's data. One compact JSON line per decision in
 * {@code entropybot/brain/decisions-YYYY-MM-DD.jsonl}: {@code {t, tick, branch, scores, chosen, reason, state, outcome:null}};
 * the outcome follows as its own line {@code {t, ref, outcome}} (finished | failed:<why> | interrupted:<by> |
 * overridden_by_owner:<verb>) when the job ends. Days older than 14 are deleted; a day stops at 5 MB (counted as
 * dropped). Writes through {@link Sink}, so JUnit runs it in memory. Never throws: errors are counted.
 */
public final class DecisionLog {
    public static final int KEEP_DAYS = 14;
    public static final long DAY_CAP = 5L * 1024 * 1024;

    /** Where the lines go: one file per day. */
    public interface Sink {
        void append(String day, String line) throws IOException;

        long size(String day);

        /** The days that have a file ("2026-10-06"). */
        List<String> days();

        void delete(String day) throws IOException;
    }

    private final Sink sink;
    private final ZoneId zone;
    private long lastRef, written, dropped, errors;
    private String lastError, rotatedFor;

    public DecisionLog(Sink sink, ZoneId zone) {
        this.sink = sink;
        this.zone = zone;
    }

    public static String day(long ms, ZoneId zone) { return LocalDate.ofInstant(Instant.ofEpochMilli(ms), zone).toString(); }

    /** Logs one decision; its ref (unique, the ms it was made, bumped on a tie) for the outcome line. */
    public long decision(long now, long tick, String branch, Map<String, Integer> scores, String chosen, String reason, JsonObject state) {
        return decision(now, tick, branch, scores, chosen, reason, state, null);
    }

    /** 0.26.1 R1: with the leaves the tree skipped ("light here 8 failed NO_ITEM 3 min ago"), as {@code skipped} when any. */
    public long decision(long now, long tick, String branch, Map<String, Integer> scores, String chosen, String reason, JsonObject state, List<String> skipped) {
        return decision(now, tick, branch, scores, chosen, reason, state, skipped, null);
    }

    /** 0.26.2: with the feasibility verdict ({step, missing, class, subchain}) when the brain judged one. */
    public long decision(long now, long tick, String branch, Map<String, Integer> scores, String chosen, String reason, JsonObject state, List<String> skipped, JsonObject feasibility) {
        long ref = Math.max(now, lastRef + 1);
        lastRef = ref;
        JsonObject o = new JsonObject();
        o.addProperty("t", ref);
        o.addProperty("tick", tick);
        o.addProperty("branch", branch);
        JsonObject sc = new JsonObject();
        if (scores != null) scores.forEach(sc::addProperty);
        o.add("scores", sc);
        o.addProperty("chosen", chosen);
        o.addProperty("reason", reason);
        o.add("state", state == null ? new JsonObject() : state);
        if (skipped != null && !skipped.isEmpty()) {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            skipped.forEach(a::add);
            o.add("skipped", a);
        }
        if (feasibility != null) o.add("feasibility", feasibility);
        if (dayPlanCtx != null) o.add("dayPlan", dayPlanCtx.deepCopy());      // 0.26.3
        o.add("outcome", com.google.gson.JsonNull.INSTANCE);
        write(now, o);
        return ref;
    }

    private JsonObject dayPlanCtx;

    /** 0.26.3: the day plan ({id, goals, active, state, step}) each decision line carries from now on; null = none. */
    public void dayPlanContext(JsonObject plan) { dayPlanCtx = plan; }

    /** 0.26.3: one line per day plan state change: {t, branch: dayplan, from, to, reason, dayPlan}. */
    public void dayPlan(long now, String from, String to, String reason, JsonObject plan) {
        JsonObject o = new JsonObject();
        o.addProperty("t", now);
        o.addProperty("branch", "dayplan");
        o.addProperty("from", from);
        o.addProperty("to", to);
        o.addProperty("reason", reason);
        if (plan != null) o.add("dayPlan", plan);
        write(now, o);
    }

    /** The outcome of the decision ref. */
    public void outcome(long now, long ref, String outcome) {
        JsonObject o = new JsonObject();
        o.addProperty("t", now);
        o.addProperty("ref", ref);
        o.addProperty("outcome", outcome);
        write(now, o);
    }

    /** A line copied from the old autominer log (the history, read-only): {t, branch: autominer, chosen, reason, outcome}. */
    public void history(long at, String what, String why, String result) {
        JsonObject o = new JsonObject();
        o.addProperty("t", at);
        o.addProperty("branch", "autominer");
        o.addProperty("chosen", what);
        o.addProperty("reason", why);
        o.addProperty("outcome", result);
        o.addProperty("history", true);
        write(at, o);
    }

    private void write(long now, JsonObject o) {
        String d = day(now, zone), line = o.toString();
        try {
            if (!d.equals(rotatedFor)) rotate(now);
            if (sink.size(d) + line.length() + 1 > DAY_CAP) {
                dropped++;
                return;
            }
            sink.append(d, line);
            written++;
        } catch (IOException | RuntimeException e) {
            errors++;
            lastError = e.toString();
        }
    }

    /** Deletes the days older than {@link #KEEP_DAYS}. */
    public void rotate(long now) {
        rotatedFor = day(now, zone);
        LocalDate keepFrom = LocalDate.parse(rotatedFor).minusDays(KEEP_DAYS - 1);
        try {
            for (String d : sink.days()) {
                try {
                    if (LocalDate.parse(d).isBefore(keepFrom)) sink.delete(d);
                } catch (java.time.format.DateTimeParseException ignored) {
                    // not one of ours
                }
            }
        } catch (IOException | RuntimeException e) {
            errors++;
            lastError = e.toString();
        }
    }

    public long written() { return written; }

    public long dropped() { return dropped; }

    public long errors() { return errors; }

    public String lastError() { return lastError; }
}
