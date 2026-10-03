package io.github.mojolowjo.entropybot.poi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.io.BotFiles;

import java.util.ArrayList;
import java.util.List;

/**
 * Points of interest the bot has seen (B3b, docs/BOT_PLAN.md 5.6): dungeons, trial chambers, portals,
 * villages, geodes, mineshafts, lava lakes, diamonds... in {@code pois.json}. A find of the same kind within
 * {@link #MERGE} blocks of a known one only updates it, so a dungeon is one entry, not one per spawner
 * scan. Plain Java: JUnit drives it.
 */
public final class Pois {
    public static final String FILE = "pois.json";
    public static final int MERGE = 32, MAX = 500;
    static final long FLUSH_AFTER = 100;

    public record Poi(int id, String kind, int x, int y, int z, String dim, long first, long last, String note) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("kind", kind);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("dim", dim);
            o.addProperty("first", first);
            o.addProperty("last", last);
            if (note != null) o.addProperty("note", note);
            return o;
        }

        static Poi of(JsonObject o) {
            return new Poi(o.get("id").getAsInt(), o.get("kind").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(),
                    o.get("z").getAsInt(), o.has("dim") ? o.get("dim").getAsString() : "minecraft:overworld",
                    o.has("first") ? o.get("first").getAsLong() : 0, o.has("last") ? o.get("last").getAsLong() : 0,
                    o.has("note") ? o.get("note").getAsString() : null);
        }
    }

    private final List<Poi> list = new ArrayList<>();
    private int next = 1;
    private long dirtySince = -1;
    private BotFiles files;

    public synchronized String load(BotFiles f) {
        files = f;
        list.clear();
        String text = f.readJson(FILE);
        if (text == null) return FILE + ": none yet";
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            next = o.has("next") ? o.get("next").getAsInt() : 1;
            for (JsonElement e : o.getAsJsonArray("pois")) list.add(Poi.of(e.getAsJsonObject()));
            for (Poi p : list) next = Math.max(next, p.id() + 1);
            return FILE + ": " + list.size() + " points";
        } catch (RuntimeException e) {
            JsonObject aside = new JsonObject();
            aside.addProperty("text", text);
            f.writeJson("pois.broken.json", aside.toString());
            list.clear();
            return FILE + ": broken (" + e.getMessage() + "), set aside, starting empty";
        }
    }

    /** A sighting: a new point (returned) or null when it only refreshed a known one nearby. */
    public synchronized Poi saw(String kind, int x, int y, int z, String dim, long nowMs, long tick, String note) {
        for (int i = 0; i < list.size(); i++) {
            Poi p = list.get(i);
            if (!p.kind().equals(kind) || !p.dim().equals(dim)) continue;
            if (Math.abs(p.x() - x) <= MERGE && Math.abs(p.y() - y) <= MERGE && Math.abs(p.z() - z) <= MERGE) {
                if (nowMs - p.last() > 60_000) {
                    list.set(i, new Poi(p.id(), p.kind(), p.x(), p.y(), p.z(), p.dim(), p.first(), nowMs, p.note()));
                    touch(tick);
                }
                return null;
            }
        }
        if (list.size() >= MAX) list.remove(0);                // the oldest goes
        Poi p = new Poi(next++, kind, x, y, z, dim, nowMs, nowMs, note);
        list.add(p);
        touch(tick);
        return p;
    }

    public synchronized boolean forget(int id, long tick) {
        boolean gone = list.removeIf(p -> p.id() == id);
        if (gone) touch(tick);
        return gone;
    }

    public synchronized int size() { return list.size(); }

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("next", next);
        JsonArray a = new JsonArray();
        for (Poi p : list) a.add(p.toJson());
        o.add("pois", a);
        return o;
    }

    private void touch(long tick) {
        if (dirtySince < 0) dirtySince = tick;
    }

    public synchronized void flushIfDue(long tick) {
        if (files == null || dirtySince < 0 || tick - dirtySince < FLUSH_AFTER) return;
        if (files.writeJson(FILE, toJson().toString()).startsWith("ok")) dirtySince = -1;
    }
}
