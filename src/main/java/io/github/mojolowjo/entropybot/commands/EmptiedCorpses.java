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

    /** Adds and writes; null when fine or nothing to write, else the write problem. */
    static synchronized String mark(UUID id, BotFiles f) {
        if (id == null || !SET.add(id)) return null;
        ORDER.addLast(id);
        while (ORDER.size() > MAX) SET.remove(ORDER.removeFirst());
        if (f == null) return null;
        JsonArray a = new JsonArray();
        for (UUID u : ORDER) a.add(u.toString());
        JsonObject o = new JsonObject();
        o.add("emptied", a);
        String r = f.writeJson(FILE, o.toString());
        return r.startsWith("ok") ? null : r;
    }

    /** At the first tick in a world and at a world switch. A line for the log. */
    static synchronized String load(BotFiles f) {
        SET.clear();
        ORDER.clear();
        String text = f.readJson(FILE);
        if (text == null) return FILE + ": none yet";
        try {
            for (JsonElement e : JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("emptied")) {
                UUID u = UUID.fromString(e.getAsString());
                if (SET.add(u)) ORDER.addLast(u);
            }
            return FILE + ": " + SET.size() + " emptied corpses";
        } catch (RuntimeException e) {
            SET.clear();
            ORDER.clear();
            return FILE + ": unreadable (" + e.getMessage() + "), starting empty";
        }
    }
}