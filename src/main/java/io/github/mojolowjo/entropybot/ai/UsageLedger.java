package io.github.mojolowjo.entropybot.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 0.35.0: what the operator spent: a rolling hour (each call's time, tokens, USD) and today's totals by local date.
 * Saved to {@code ai-usage.json} after each call, so a game restart does not reset the day's cap. Plain Java.
 */
public final class UsageLedger {
    record Entry(long at, long tokens, double usd, int requests) {}

    static final long HOUR_MS = 3_600_000L;

    private final Path file;
    private final ZoneId zone;
    private final Deque<Entry> hour = new ArrayDeque<>();
    private String day = "";
    private long dayTokens, dayRequests;
    private double dayUsd;
    private String saveError, corrupt;

    public UsageLedger(Path file, ZoneId zone) {
        this.file = file;
        this.zone = zone;
        load();
    }

    private String dayOf(long now) { return LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).toString(); }

    private void roll(long now) {
        String d = dayOf(now);
        if (!d.equals(day)) {
            day = d;
            dayTokens = 0;
            dayRequests = 0;
            dayUsd = 0;
        }
        while (!hour.isEmpty() && now - hour.peekFirst().at() >= HOUR_MS) hour.pollFirst();
    }

    public synchronized void record(long now, long tokens, double usd) { record(now, tokens, usd, 1); }

    /** 0.35.1: requests 0 = a correction of a request already counted (tokens may be negative: the estimate was too high). */
    public synchronized void record(long now, long tokens, double usd, int requests) {
        roll(now);
        if (requests > 0 || hour.isEmpty()) hour.addLast(new Entry(now, Math.max(0, tokens), Math.max(0, usd), Math.max(0, requests)));
        else {
            Entry last = hour.pollLast();
            hour.addLast(new Entry(last.at(), Math.max(0, last.tokens() + tokens), last.usd() + Math.max(0, usd), last.requests()));
        }
        dayTokens = Math.max(0, dayTokens + tokens);
        dayUsd += Math.max(0, usd);
        dayRequests += Math.max(0, requests);
        if (corrupt == null) save();                       // never overwrite the unreadable file
    }

    /** null: fine; else why the ledger can't be trusted (the caps count as reached). */
    public String corrupt() { return corrupt; }

    public synchronized long hourTokens(long now) { roll(now); long t = 0; for (Entry e : hour) t += e.tokens(); return t; }

    public synchronized double hourUsd(long now) { roll(now); double t = 0; for (Entry e : hour) t += e.usd(); return t; }

    public synchronized int hourRequests(long now) { roll(now); int n = 0; for (Entry e : hour) n += e.requests(); return n; }

    public synchronized long dayTokens(long now) { roll(now); return dayTokens; }

    public synchronized double dayUsd(long now) { roll(now); return dayUsd; }

    public synchronized long dayRequests(long now) { roll(now); return dayRequests; }

    /** null: saved fine (or no file); else the last write error (shown in ai status). */
    public String saveError() { return saveError; }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            day = o.has("day") ? o.get("day").getAsString() : "";
            dayTokens = o.has("dayTokens") ? o.get("dayTokens").getAsLong() : 0;
            dayUsd = o.has("dayUsd") ? o.get("dayUsd").getAsDouble() : 0;
            dayRequests = o.has("dayRequests") ? o.get("dayRequests").getAsLong() : 0;
            if (o.has("hour")) for (JsonElement e : o.getAsJsonArray("hour")) {
                JsonArray a = e.getAsJsonArray();
                hour.addLast(new Entry(a.get(0).getAsLong(), a.get(1).getAsLong(), a.get(2).getAsDouble(), a.size() > 3 ? a.get(3).getAsInt() : 1));
            }
        } catch (IOException | RuntimeException e) {
            // 0.35.1 review: fail closed: an unreadable ledger is a reached cap until the owner deletes the file
            corrupt = "ai-usage.json is unreadable (" + e.getMessage() + "): delete entropybot/ai-usage.json, then ai reload";
        }
    }

    private void save() {
        if (file == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("day", day);
        o.addProperty("dayTokens", dayTokens);
        o.addProperty("dayUsd", dayUsd);
        o.addProperty("dayRequests", dayRequests);
        JsonArray h = new JsonArray();
        for (Entry e : hour) {
            JsonArray a = new JsonArray();
            a.add(e.at());
            a.add(e.tokens());
            a.add(e.usd());
            a.add(e.requests());
            h.add(a);
        }
        o.add("hour", h);
        try {
            AiSettings.writeAtomic(file, o.toString());
            saveError = null;
        } catch (IOException | RuntimeException e) {
            saveError = "couldn't save ai-usage.json: " + e.getMessage();
        }
    }
}
