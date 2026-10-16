package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.io.BotFiles;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;

/** 0.31.1: the corpses I looted (Storage.EMPTIED), kept per world in corpses.json, the newest MAX. Plain Java. */
final class EmptiedCorpses {
    private EmptiedCorpses() {}

    static final String FILE = "corpses.json";
    static final int MAX = 200;
    static final Set<UUID> SET = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ArrayDeque<UUID> ORDER = new ArrayDeque<>();
    /** review 6: marks not on disk yet (no folder, or the write failed): merged into the next load. */
    private static final java.util.LinkedHashSet<UUID> PENDING = new java.util.LinkedHashSet<>();

    /** Adds and writes; null when fine or nothing to write, else the write problem. */
    static synchronized String mark(UUID id, BotFiles f) {
        if (id == null || !SET.add(id)) return null;
        ORDER.addLast(id);
        while (ORDER.size() > MAX) SET.remove(ORDER.removeFirst());
        PENDING.add(id);
        return write(f);
    }

    private static String write(BotFiles f) {
        if (f == null) return null;
        JsonArray a = new JsonArray();
        for (UUID u : ORDER) a.add(u.toString());
        JsonObject o = new JsonObject();
        o.add("emptied", a);
        String r = f.writeJson(FILE, o.toString());
        if (!r.startsWith("ok")) return r;
        PENDING.clear();
        return null;
    }

    /** At the first tick in a world and at a world switch. A line for the log. */
    static synchronized String load(BotFiles f) {
        SET.clear();
        ORDER.clear();
        String text = f.readJson(FILE);
        if (text == null) return FILE + ": none yet" + merge(f);
        try {
            for (JsonElement e : JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("emptied")) {
                UUID u = UUID.fromString(e.getAsString());
                if (SET.add(u)) ORDER.addLast(u);
            }
        } catch (RuntimeException e) {
            SET.clear();
            ORDER.clear();
            return FILE + ": unreadable (" + e.getMessage() + "), starting empty" + merge(f);
        }
        return FILE + ": " + SET.size() + " emptied corpses" + merge(f);
    }

    /** review 6: the newest MAX only, and marks not written yet joined in (then written). */
    private static String merge(BotFiles f) {
        int n = 0;
        for (UUID u : new java.util.ArrayList<>(PENDING)) if (SET.add(u)) { ORDER.addLast(u); n++; }
        int trimmed = 0;
        while (ORDER.size() > MAX) { SET.remove(ORDER.removeFirst()); trimmed++; }
        if (n == 0 && trimmed == 0) return "";
        String w = write(f);
        return " (" + (n > 0 ? n + " marked before the load joined" : "") + (n > 0 && trimmed > 0 ? ", " : "")
                + (trimmed > 0 ? trimmed + " oldest dropped" : "") + (w != null ? "; write failed: " + w : "") + ")";
    }
}