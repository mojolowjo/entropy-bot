package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;

/** The last vetoes (and, in log mode, would-be vetoes), newest last. */
public final class VetoLog {
    public static final int CAPACITY = 50;

    private final ArrayDeque<JsonObject> entries = new ArrayDeque<>();
    private long vetoes, wouldVetoes;

    public synchronized void record(long tick, String action, String dim, int x, int y, int z, String reason, boolean floor, boolean enforced) {
        JsonObject o = new JsonObject();
        o.addProperty("tick", tick);
        o.addProperty("action", action);
        o.addProperty("dim", dim);
        o.addProperty("pos", x + " " + y + " " + z);
        o.addProperty("reason", reason);
        o.addProperty("floor", floor);
        o.addProperty("enforced", enforced);
        entries.addLast(o);
        while (entries.size() > CAPACITY) entries.removeFirst();
        if (enforced) vetoes++; else wouldVetoes++;
    }

    public synchronized String recent(int max) {
        JsonArray a = new JsonArray();
        int skip = Math.max(0, entries.size() - Math.max(1, max));
        int i = 0;
        for (JsonObject o : entries) {
            if (i++ < skip) continue;
            a.add(o);
        }
        return a.toString();
    }

    public synchronized long vetoes() { return vetoes; }

    public synchronized long wouldVetoes() { return wouldVetoes; }
}
