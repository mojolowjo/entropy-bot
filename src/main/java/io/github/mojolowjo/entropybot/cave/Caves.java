package io.github.mojolowjo.entropybot.cave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.io.BotFiles;
import io.github.mojolowjo.entropybot.memory.Limits;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The caves the bot has explored (B4, docs/BOT_PLAN.md 5.5; the owner's answer of 2026-10-01: remember each
 * cave and how far it got): {@code caves.json} holds per cave its entrance, the coarse cells (4x4x4) it has been
 * through, the furthest it got from the entrance, and whether frontier is left. A later "mine cave" carries on in
 * the nearest cave with frontier left. Plain Java.
 */
public final class Caves {
    public static final String FILE = "caves.json";
    /** Package H: the cells of one cave, of all caves together, and the number of caves ({@code memory/Limits}). */
    public static final int MAX_VISITED = Limits.CAVE_CELLS_EACH, MAX_CELLS = Limits.CAVE_CELLS, MAX_CAVES = Limits.CAVES, PICK_WITHIN = 64;
    static final long FLUSH_AFTER = 100;

    public static final class Cave {
        public final String name, dim;
        public final int ex, ey, ez;
        public final Set<Long> visited = new HashSet<>();
        public int furthest;
        public boolean frontierLeft = true;
        public long created, updated;

        Cave(String name, String dim, int ex, int ey, int ez) {
            this.name = name;
            this.dim = dim;
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
        }

        public JsonObject toJsonPublic() { return toJson(false); }

        JsonObject toJson(boolean withCells) {
            JsonObject o = new JsonObject();
            o.addProperty("dim", dim);
            JsonObject e = new JsonObject();
            e.addProperty("x", ex);
            e.addProperty("y", ey);
            e.addProperty("z", ez);
            o.add("entrance", e);
            o.addProperty("furthest", furthest);
            o.addProperty("frontierLeft", frontierLeft);
            o.addProperty("explored", visited.size());
            o.addProperty("created", created);
            o.addProperty("updated", updated);
            if (withCells) {
                JsonArray a = new JsonArray();
                for (Long l : visited) a.add(l);
                o.add("visited", a);
            }
            return o;
        }
    }

    private final Map<String, Cave> caves = new LinkedHashMap<>();
    private int next = 1;
    private long dirtySince = -1;
    private BotFiles files;

    public synchronized String load(BotFiles f) {
        files = f;
        caves.clear();
        String text = f.readJson(FILE);
        if (text == null) return FILE + ": none yet";
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            next = o.has("next") ? o.get("next").getAsInt() : 1;
            for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("caves").entrySet()) {
                JsonObject c = en.getValue().getAsJsonObject(), e = c.getAsJsonObject("entrance");
                Cave cv = new Cave(en.getKey(), c.get("dim").getAsString(), e.get("x").getAsInt(), e.get("y").getAsInt(), e.get("z").getAsInt());
                cv.furthest = c.has("furthest") ? c.get("furthest").getAsInt() : 0;
                cv.frontierLeft = !c.has("frontierLeft") || c.get("frontierLeft").getAsBoolean();
                cv.created = c.has("created") ? c.get("created").getAsLong() : 0;
                cv.updated = c.has("updated") ? c.get("updated").getAsLong() : 0;
                if (c.has("visited")) for (JsonElement v : c.getAsJsonArray("visited")) cv.visited.add(v.getAsLong());
                caves.put(cv.name, cv);
            }
            int gone = prune(null);
            pruneNote = null;                              // said in the load line
            if (gone > 0) touch(0);
            return FILE + ": " + caves.size() + " caves" + (gone > 0 ? " (dropped the " + gone + " oldest, over the limit)" : "");
        } catch (RuntimeException e) {
            JsonObject aside = new JsonObject();
            aside.addProperty("text", text);
            f.writeJson("caves.broken.json", aside.toString());
            caves.clear();
            return FILE + ": broken (" + e.getMessage() + "), set aside, starting empty";
        }
    }

    public synchronized Cave get(String name) { return caves.get(name); }

    /** Camera v2: x y z lies in a coarse cell some cave of this dimension has been through. */
    public synchronized boolean visitedAt(String dim, int x, int y, int z) {
        long c = CaveSearch.coarse(x, y, z);
        for (Cave cv : caves.values()) if (cv.dim.equals(dim) && cv.visited.contains(c)) return true;
        return false;
    }

    /** B7e: how many caves it remembers (for "memory"). */
    public synchronized int size() { return caves.size(); }

    /**
     * The cave for a "mine cave" starting at x y z: the named one, else the nearest one in this dimension with frontier
     * left whose entrance is within PICK_WITHIN, else a new one with its entrance here. Null for an unknown name.
     */
    public synchronized Cave pick(String name, String dim, int x, int y, int z, long nowMs, long tick) {
        if (name != null && !name.isEmpty()) return caves.get(name);
        Cave best = null;
        long bestD = Long.MAX_VALUE;
        for (Cave c : caves.values()) {
            if (!c.dim.equals(dim) || !c.frontierLeft) continue;
            long d = (long) (c.ex - x) * (c.ex - x) + (long) (c.ey - y) * (c.ey - y) + (long) (c.ez - z) * (c.ez - z);
            if (d <= (long) PICK_WITHIN * PICK_WITHIN && d < bestD) { bestD = d; best = c; }
        }
        if (best != null) return best;
        String n;
        do { n = "cave_" + next++; } while (caves.containsKey(n));
        Cave c = new Cave(n, dim, x, y, z);
        c.created = nowMs;
        c.updated = nowMs;
        caves.put(n, c);
        prune(c);
        touch(tick);
        return c;
    }

    /**
     * Package H: past {@link #MAX_CAVES} caves or {@link #MAX_CELLS} cells in all, whole caves go: the oldest finished
     * one (no frontier left) first, then the oldest; never {@code keep} (the cave being explored). How many went.
     */
    synchronized int prune(Cave keep) {
        // at load (no cave being explored) the latest updated one stays: a cave bigger than the whole budget (a file from
        // before the caps) is kept whole, never every cave dropped
        if (keep == null) for (Cave c : caves.values()) if (keep == null || c.updated > keep.updated) keep = c;
        int gone = 0;
        while (true) {
            long cells = 0;
            for (Cave c : caves.values()) cells += c.visited.size();
            if (caves.size() <= MAX_CAVES && cells <= MAX_CELLS) {
                if (gone > 0) pruneNote = "dropped the " + gone + " oldest cave" + (gone == 1 ? "" : "s") + " (over the limit)";
                return gone;
            }
            Cave drop = null;
            for (Cave c : caves.values()) {
                if (c == keep) continue;
                if (drop == null || (drop.frontierLeft && !c.frontierLeft)
                        || (drop.frontierLeft == c.frontierLeft && c.updated < drop.updated)) drop = c;
            }
            if (drop == null) {
                if (gone > 0) pruneNote = "dropped the " + gone + " oldest cave" + (gone == 1 ? "" : "s") + " (over the limit)";
                return gone;
            }
            caves.remove(drop.name);
            gone++;
        }
    }

    private String pruneNote;

    /** What the last pruning dropped, once (Core logs it), or null. */
    public synchronized String takePruneNote() {
        String s = pruneNote;
        pruneNote = null;
        return s;
    }

    /** The bot is at x y z inside the cave: the coarse cells within 4 blocks count as explored. */
    public synchronized void visit(Cave c, int x, int y, int z, long nowMs, long tick) {
        int before = c.visited.size();
        for (int dx = -4; dx <= 4; dx += 4) for (int dy = -4; dy <= 4; dy += 4) for (int dz = -4; dz <= 4; dz += 4) {
            if (c.visited.size() >= MAX_VISITED) break;
            c.visited.add(CaveSearch.coarse(x + dx, y + dy, z + dz));
        }
        int d = (int) Math.round(Math.sqrt((double) (x - c.ex) * (x - c.ex) + (double) (y - c.ey) * (y - c.ey) + (double) (z - c.ez) * (z - c.ez)));
        if (d > c.furthest) c.furthest = d;
        c.updated = nowMs;
        if (c.visited.size() != before) {
            prune(c);                                  // a sum over at most MAX_CAVES caves: cheap
            touch(tick);
        }
    }

    public synchronized void finish(Cave c, boolean frontierLeft, long nowMs, long tick) {
        c.frontierLeft = frontierLeft;
        c.updated = nowMs;
        touch(tick);
    }

    /** Renames a cave ("caves rename <old> <new>"). */
    public synchronized String rename(String from, String to, long tick) {
        Cave c = caves.get(from);
        if (c == null) return "error: no cave called " + from;
        if (caves.containsKey(to)) return "error: there is a cave called " + to + " already";
        caves.remove(from);
        Cave n = new Cave(to, c.dim, c.ex, c.ey, c.ez);
        n.visited.addAll(c.visited);
        n.furthest = c.furthest;
        n.frontierLeft = c.frontierLeft;
        n.created = c.created;
        n.updated = c.updated;
        caves.put(to, n);
        touch(tick);
        return "ok: " + from + " is called " + to + " now";
    }

    /** The caves for listing (no cell lists). */
    public synchronized JsonObject toJson(boolean withCells) {
        JsonObject o = new JsonObject();
        o.addProperty("next", next);
        JsonObject cs = new JsonObject();
        for (Cave c : caves.values()) cs.add(c.name, c.toJson(withCells));
        o.add("caves", cs);
        return o;
    }

    private void touch(long tick) {
        if (dirtySince < 0) dirtySince = tick;
    }

    public synchronized void flushIfDue(long tick) {
        if (files == null || dirtySince < 0 || tick - dirtySince < FLUSH_AFTER) return;
        if (files.writeJson(FILE, toJson(true).toString()).startsWith("ok")) dirtySince = -1;
    }
}
