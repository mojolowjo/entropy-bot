package io.github.mojolowjo.entropybot.strip;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.clear.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B7d D2: the strip mines' progress, the bridge's memory.mine / memory.mines in the same shapes, kept in the mod's
 * commands.json: "mine" = the current mine {at: "x y z dir", k, setup, chests: [{x,y,z}], table: {x,y,z},
 * collectOres?, bad?: ["7 left"], turnedFrom?}, "mines" = the others by key (selectMine). Pure: works on the
 * JsonObject it is given (the caller saves).
 */
public final class MineBook {
    /** Old mine notes kept (wave 1 H: LIMITS.mines). */
    public static final int MAX_MINES = 20;

    private final JsonObject root;

    public MineBook(JsonObject root) { this.root = root; }

    /** The current mine's note, or null before the first select. */
    public JsonObject current() {
        return root.has("mine") && root.get("mine").isJsonObject() ? root.getAsJsonObject("mine") : null;
    }

    private JsonObject mines() {
        if (!root.has("mines") || !root.get("mines").isJsonObject()) root.add("mines", new JsonObject());
        return root.getAsJsonObject("mines");
    }

    public static JsonObject fresh(String key) {
        JsonObject o = new JsonObject();
        o.addProperty("at", key);
        o.addProperty("k", 1);
        o.addProperty("setup", false);
        return o;
    }

    /** selectMine: mine `key` becomes the current one; the old current one's note goes to "mines". */
    public JsonObject select(String key) {
        JsonObject cur = current();
        if (cur != null && key.equals(str(cur, "at"))) return cur;
        JsonObject ms = mines();
        if (cur != null && cur.has("at")) ms.add(str(cur, "at"), cur);
        JsonObject next = ms.has(key) && ms.get(key).isJsonObject() ? ms.getAsJsonObject(key) : fresh(key);
        ms.remove(key);
        root.add("mine", next);
        return next;
    }

    /** "stripmine reset": branch 1 again (the chests stay where they are, but the note forgets them). */
    public void reset(String key) { root.add("mine", fresh(key)); }

    /** An old mine's note (switching mines keeps progress), or null. */
    public JsonObject old(String key) {
        JsonObject ms = mines();
        return ms.has(key) && ms.get(key).isJsonObject() ? ms.getAsJsonObject(key) : null;
    }

    /** Drops the oldest old notes past MAX_MINES (insertion order), never one a marked place points at. How many went. */
    public int prune(Set<String> live) {
        JsonObject ms = mines();
        int over = ms.size() - MAX_MINES, n = 0;
        if (over <= 0) return 0;
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : ms.entrySet()) if (!live.contains(e.getKey())) keys.add(e.getKey());
        for (int i = 0; i < keys.size() && n < over; i++, n++) ms.remove(keys.get(i));
        return n;
    }

    // ---- one note's fields ----

    public static int k(JsonObject m) { return m != null && m.has("k") ? m.get("k").getAsInt() : 1; }

    public static void setK(JsonObject m, int k) { m.addProperty("k", k); }

    public static boolean setup(JsonObject m) { return m != null && m.has("setup") && m.get("setup").getAsBoolean(); }

    /** collectOres: true unless switched off ("stripmine ores list"). */
    public static boolean collect(JsonObject m) {
        return m == null || !m.has("collectOres") || m.get("collectOres").isJsonNull() || m.get("collectOres").getAsBoolean();
    }

    public static List<Pos> chests(JsonObject m) {
        List<Pos> out = new ArrayList<>();
        if (m == null || !m.has("chests") || !m.get("chests").isJsonArray()) return out;
        for (JsonElement e : m.getAsJsonArray("chests")) if (e.isJsonObject()) out.add(pos(e.getAsJsonObject()));
        return out;
    }

    public static Pos table(JsonObject m) {
        return m != null && m.has("table") && m.get("table").isJsonObject() ? pos(m.getAsJsonObject("table")) : null;
    }

    public static void setSetup(JsonObject m, List<Pos> chests, Pos table) {
        m.addProperty("setup", true);
        JsonArray a = new JsonArray();
        for (Pos p : chests) a.add(json(p));
        m.add("chests", a);
        if (table != null) m.add("table", json(table));
        else m.remove("table");
    }

    public static List<String> bad(JsonObject m) {
        List<String> out = new ArrayList<>();
        if (m != null && m.has("bad") && m.get("bad").isJsonArray()) for (JsonElement e : m.getAsJsonArray("bad")) out.add(e.getAsString());
        return out;
    }

    public static void addBad(JsonObject m, String branch) {
        if (!m.has("bad") || !m.get("bad").isJsonArray()) m.add("bad", new JsonArray());
        m.getAsJsonArray("bad").add(branch);
    }

    public static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    public static Pos pos(JsonObject o) {
        return new Pos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
    }

    public static JsonObject json(Pos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.x());
        o.addProperty("y", p.y());
        o.addProperty("z", p.z());
        return o;
    }

    /** A place note {x, y, z, dim?, dir?} as a mine, or null when it has no direction. */
    public static MineGeom geom(JsonObject place) {
        if (place == null || !place.has("dir") || !MineGeom.DIRS.containsKey(str(place, "dir"))) return null;
        return new MineGeom(place.get("x").getAsInt(), place.get("y").getAsInt(), place.get("z").getAsInt(), str(place, "dir"));
    }
}
