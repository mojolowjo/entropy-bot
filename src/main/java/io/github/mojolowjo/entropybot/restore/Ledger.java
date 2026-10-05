package io.github.mojolowjo.entropybot.restore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 (survival plan): the restore ledger. Every block the bot broke that was not the purpose of its job (Baritone
 * tunnelling in {@code mine ... dig}, the escape dig-out) is one entry, newest last. An entry starts {@code pending}
 * (the client predicted the break) and is confirmed once the level shows the cell empty, or dropped when the server
 * put the block back. Confirmed entries are what {@code restore} puts back, newest first. Capped at {@link #CAP}: the
 * oldest go, and {@link #takeNote} says how many. Loader-neutral: JUnit drives it; the JSON is restore.json's "entries".
 */
public final class Ledger {
    public static final int CAP = 2000;
    /** A pending break is looked at after this many ticks, and dropped when the cell is still full after the max. */
    public static final long CONFIRM_AFTER = 5, CONFIRM_MAX = 40;

    public static final String PATH = "path", ESCAPE = "escape", REACH = "reach";

    public static final class Entry {
        public final long seq;
        public final int x, y, z;
        public final String dim, block, state;
        /** The items that may fill the hole, best first (empty: never re-placed, see {@link #why}). */
        public final List<String> items;
        /** Why it is never re-placed ("an ore", "not terrain"), null for a restorable one. */
        public final String why;
        public final long job;
        public final String jobLabel, reason;
        public final long tick, at;
        public boolean pending;

        public Entry(long seq, int x, int y, int z, String dim, String block, String state, List<String> items, String why,
                     long job, String jobLabel, String reason, long tick, long at, boolean pending) {
            this.seq = seq;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.block = block;
            this.state = state;
            this.items = items == null ? List.of() : List.copyOf(items);
            this.why = why;
            this.job = job;
            this.jobLabel = jobLabel;
            this.reason = reason;
            this.tick = tick;
            this.at = at;
            this.pending = pending;
        }

        public boolean restorable() { return !items.isEmpty(); }

        public int[] pos() { return new int[]{x, y, z}; }

        public String key() { return dim + " " + x + " " + y + " " + z; }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("seq", seq);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("dim", dim);
            o.addProperty("block", block);
            if (state != null) o.addProperty("state", state);
            JsonArray a = new JsonArray();
            items.forEach(a::add);
            o.add("items", a);
            if (why != null) o.addProperty("why", why);
            o.addProperty("job", job);
            if (jobLabel != null) o.addProperty("jobLabel", jobLabel);
            o.addProperty("reason", reason);
            o.addProperty("tick", tick);
            o.addProperty("at", at);
            return o;
        }

        public static Entry fromJson(JsonObject o) {
            List<String> items = new ArrayList<>();
            if (o.has("items") && o.get("items").isJsonArray()) for (JsonElement e : o.getAsJsonArray("items")) items.add(e.getAsString());
            return new Entry(o.get("seq").getAsLong(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                    str(o, "dim", "minecraft:overworld"), str(o, "block", "minecraft:stone"), str(o, "state", null), items, str(o, "why", null),
                    o.has("job") ? o.get("job").getAsLong() : 0, str(o, "jobLabel", null), str(o, "reason", PATH),
                    o.has("tick") ? o.get("tick").getAsLong() : 0, o.has("at") ? o.get("at").getAsLong() : 0, false);
        }

        private static String str(JsonObject o, String k, String def) {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
        }
    }

    private final List<Entry> list = new ArrayList<>();
    private long nextSeq = 1;
    private int droppedByCap;
    private final int cap;

    public Ledger() { this(CAP); }

    public Ledger(int cap) { this.cap = cap; }

    public synchronized int size() { return list.size(); }

    public synchronized List<Entry> all() { return Collections.unmodifiableList(new ArrayList<>(list)); }

    /** Confirmed entries (what restore works on). */
    public synchronized List<Entry> confirmed() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) if (!e.pending) out.add(e);
        return out;
    }

    public synchronized List<Entry> pending() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) if (e.pending) out.add(e);
        return out;
    }

    /** Confirmed entries a job recorded. */
    public synchronized List<Entry> forJob(long job) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) if (!e.pending && e.job == job) out.add(e);
        return out;
    }

    public synchronized boolean hasJob(long job) {
        for (Entry e : list) if (e.job == job) return true;
        return false;
    }

    /** Restorable confirmed entries (the ones a check line counts). */
    public synchronized int waiting() {
        int n = 0;
        for (Entry e : list) if (!e.pending && e.restorable()) n++;
        return n;
    }

    public synchronized Entry at(String dim, int x, int y, int z) {
        for (Entry e : list) if (e.x == x && e.y == y && e.z == z && e.dim.equals(dim)) return e;
        return null;
    }

    /**
     * A new pending break. A cell already in the ledger keeps its first entry (the original terrain) and null comes
     * back. Over the cap the oldest go.
     */
    public synchronized Entry add(int x, int y, int z, String dim, String block, String state, List<String> items, String why,
                                  long job, String jobLabel, String reason, long tick, long at) {
        if (at(dim, x, y, z) != null) return null;
        Entry e = new Entry(nextSeq++, x, y, z, dim, block, state, items, why, job, jobLabel, reason, tick, at, true);
        list.add(e);
        while (list.size() > cap) {
            list.remove(0);
            droppedByCap++;
        }
        return e;
    }

    /** The cap's note since the last call ("dropped 3 oldest ..."), else null. */
    public synchronized String takeNote() {
        if (droppedByCap == 0) return null;
        String s = "dropped the " + droppedByCap + " oldest restore entr" + (droppedByCap == 1 ? "y" : "ies") + " (I keep at most " + cap + ")";
        droppedByCap = 0;
        return s;
    }

    /** What a pending entry's cell says now: EMPTY (air or fluid: confirmed), FULL, or UNKNOWN (not loaded). */
    public enum Cell { EMPTY, FULL, UNKNOWN }

    public interface CellReader {
        Cell at(String dim, int x, int y, int z);
    }

    /** Confirms or drops the pending entries old enough; the count that changed (either way). */
    public synchronized int settle(CellReader r, long now) {
        int changed = 0;
        for (var it = list.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (!e.pending || now - e.tick < CONFIRM_AFTER) continue;
            Cell c = r.at(e.dim, e.x, e.y, e.z);
            if (c == Cell.EMPTY) {
                e.pending = false;
                changed++;
            } else if (now - e.tick >= CONFIRM_MAX) {
                it.remove();                               // the server kept the block (or it never loaded): nothing to put back
                changed++;
            }
        }
        return changed;
    }

    public synchronized boolean remove(Entry e) { return list.remove(e); }

    /** Forgets the n oldest confirmed entries; how many went. */
    public synchronized int forgetOldest(int n) {
        int gone = 0;
        for (var it = list.iterator(); it.hasNext() && gone < n; ) {
            if (it.next().pending) continue;
            it.remove();
            gone++;
        }
        return gone;
    }

    public synchronized int forgetAll() {
        int n = list.size();
        list.clear();
        return n;
    }

    /** "3 path (mine iron_ore), 1 escape (going to 1 2 3)": counts by reason and job label. */
    public synchronized String breakdown() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Entry e : list) {
            if (e.pending) continue;
            String k = e.reason + (e.jobLabel == null ? "" : " (" + e.jobLabel + ")");
            m.merge(k, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        m.forEach((k, v) -> parts.add(v + " " + k));
        return String.join(", ", parts);
    }

    /** Only confirmed entries are saved: a pending one is settled within two seconds of play anyway. */
    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("nextSeq", nextSeq);
        JsonArray a = new JsonArray();
        for (Entry e : list) if (!e.pending) a.add(e.toJson());
        o.add("entries", a);
        return o;
    }

    /** Reads what {@link #toJson} wrote; a broken entry is skipped (counted in the returned note). */
    public static Ledger fromJson(JsonObject o, int cap, StringBuilder note) {
        Ledger l = new Ledger(cap);
        if (o == null) return l;
        int bad = 0;
        if (o.has("entries") && o.get("entries").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("entries")) {
                try {
                    Entry e = Entry.fromJson(el.getAsJsonObject());
                    l.list.add(e);
                    l.nextSeq = Math.max(l.nextSeq, e.seq + 1);
                } catch (RuntimeException ex) {
                    bad++;
                }
            }
        }
        if (o.has("nextSeq")) l.nextSeq = Math.max(l.nextSeq, o.get("nextSeq").getAsLong());
        while (l.list.size() > cap) {
            l.list.remove(0);
            l.droppedByCap++;
        }
        if (note != null && bad > 0) note.append(bad).append(" broken entr").append(bad == 1 ? "y" : "ies").append(" skipped");
        return l;
    }
}
