package io.github.mojolowjo.entropybot.move;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * 0.23.3 movement: extra cost on route-map edges that let a walk down (a leg that failed, or ran over 2x its straight
 * distance). The key is the edge's leaving door: {@code <box key>@x,y,z} of the door's representative cell, the same
 * cell a leg's waypoint is. {@code DoorGraphRouter} adds {@link #penalty} to the crossing that leaves through that door,
 * so later routes avoid it. A penalty halves every {@link #HALF_LIFE_MS} (2 days) and is forgotten under 1 tick; it is
 * saved with the route map ({@code route/penalties.json}). Thread-safe (the planner thread reads it).
 */
public final class EdgePenalties {
    public static final EdgePenalties GLOBAL = new EdgePenalties();

    public static final long HALF_LIFE_MS = 2L * 24 * 3600 * 1000;
    /** Ticks added for a failed leg / a leg that ran long. */
    public static final double FAIL = 600, LONG = 300;
    public static final double MAX = 6000;

    private record P(double ticks, long at) {}

    private final Map<String, P> map = new HashMap<>();
    private volatile boolean dirty;

    public static String key(String box, int x, int y, int z) { return box + "@" + x + "," + y + "," + z; }

    static double decayed(double ticks, long at, long now) {
        if (now <= at) return ticks;
        return ticks * Math.pow(0.5, (now - at) / (double) HALF_LIFE_MS);
    }

    public synchronized void add(String key, double ticks, long now) {
        double cur = penalty(key, now);
        map.put(key, new P(Math.min(MAX, cur + ticks), now));
        dirty = true;
    }

    public synchronized double penalty(String key, long now) {
        P p = map.get(key);
        if (p == null) return 0;
        double v = decayed(p.ticks, p.at, now);
        return v < 1 ? 0 : v;
    }

    /** Penalised edges still worth 1 tick or more. */
    public synchronized int count(long now) {
        int n = 0;
        for (P p : map.values()) if (decayed(p.ticks, p.at, now) >= 1) n++;
        return n;
    }

    public synchronized boolean isEmpty() { return map.isEmpty(); }

    public boolean dirty() { return dirty; }

    public synchronized String toJson(long now) {
        JsonObject o = new JsonObject();
        map.forEach((k, p) -> {
            double v = decayed(p.ticks, p.at, now);
            if (v < 1) return;
            JsonObject e = new JsonObject();
            e.addProperty("ticks", Math.round(v * 10) / 10.0);
            e.addProperty("at", now);
            o.add(k, e);
        });
        dirty = false;
        return o.toString();
    }

    public synchronized void loadJson(String json) {
        map.clear();
        if (json == null || json.isBlank()) return;
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        for (String k : o.keySet()) {
            JsonObject e = o.getAsJsonObject(k);
            map.put(k, new P(e.get("ticks").getAsDouble(), e.get("at").getAsLong()));
        }
        dirty = false;
    }

    public synchronized void clear() {
        map.clear();
        dirty = true;
    }
}
