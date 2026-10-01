package io.github.mojolowjo.entropybot.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;

/**
 * The last few hundred things that happened, each with a sequence number, for scripts that poll:
 * Baritone path events ("path"), Baritone's chat lines ("log"), guard decisions ("guard") and the
 * mod's own notes ("job"). Readers remember the last seq they saw and ask for everything after it.
 */
public final class EventRing {
    public static final int CAPACITY = 300;

    private final ArrayDeque<JsonObject> ring = new ArrayDeque<>();
    private long seq;
    private volatile long tick;

    public void setTick(long t) { tick = t; }

    public synchronized long push(String kind, String text, JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("seq", ++seq);
        o.addProperty("tick", tick);
        o.addProperty("kind", kind);
        o.addProperty("text", text == null ? "" : text);
        if (data != null) o.add("data", data);
        ring.addLast(o);
        while (ring.size() > CAPACITY) ring.removeFirst();
        return seq;
    }

    public synchronized long lastSeq() { return seq; }

    /** Events with seq greater than {@code afterSeq}, oldest first, at most {@code max}, as a JSON array. */
    public synchronized String since(long afterSeq, int max) {
        JsonArray a = new JsonArray();
        int limit = max <= 0 ? CAPACITY : Math.min(max, CAPACITY);
        for (JsonObject o : ring) {
            if (o.get("seq").getAsLong() <= afterSeq) continue;
            a.add(o);
            if (a.size() >= limit) break;
        }
        return a.toString();
    }
}
