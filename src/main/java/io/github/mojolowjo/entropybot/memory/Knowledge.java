package io.github.mojolowjo.entropybot.memory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.io.BotFiles;

import java.util.Map;
import java.util.TreeMap;

/**
 * The bot's knowledge files (B3a, docs/BOT_PLAN.md 5.9): {@code places.json} (name -> {x,y,z,dim,dir?})
 * and {@code chests.json} ("x y z" -> {dim, items:{id:n}, seen: ms, trusted?}), the same shapes the
 * bridge keeps in memory.json. Every change bumps {@link #version()}; the bridge pulls a new version
 * and mirrors it into memory.json (the rollback copy), and pushes its own changes here.
 *
 * <p>Written atomically a little after the last change ({@link #flushIfDue}), backed up to
 * {@code *.bak.json} every few minutes. A file that won't parse is set aside (never overwritten) and
 * its backup is loaded instead. Plain Java: JUnit drives it with a temp folder.
 */
public final class Knowledge {
    public static final String PLACES = "places.json", CHESTS = "chests.json", RS = "rs.json";
    static final long FLUSH_AFTER = 40, BACKUP_EVERY = 6000;

    private final Map<String, JsonObject> places = new TreeMap<>();
    private final Map<String, JsonObject> chests = new TreeMap<>();
    /** B7b: the Refined Storage readings ("x y z" of the grid -> {dim, items, seen}) and the grid "rs" goes to. */
    private final Map<String, JsonObject> rs = new TreeMap<>();
    private String rsGrid;
    private long version;
    private long dirtySince = -1, lastBackup = -1;
    private BotFiles files;
    private String problem;

    public synchronized long version() { return version; }

    public synchronized boolean isEmpty() { return places.isEmpty() && chests.isEmpty() && rs.isEmpty(); }

    public synchronized String problem() { return problem; }

    /** Loads both files (or their backups). Returns a line for the log. */
    public synchronized String load(BotFiles f) {
        files = f;
        StringBuilder sb = new StringBuilder();
        sb.append(loadOne(PLACES, places)).append("; ").append(loadOne(CHESTS, chests)).append("; ").append(loadRs());
        int pruned = prune(null);
        if (pruned > 0) {
            sb.append("; dropped the ").append(pruned).append(" oldest notes (over the limit)");
            pruneNote = null;                         // said in the load line
            if (dirtySince < 0) dirtySince = 0;
        }
        version = 1;
        loadNote = sb.toString();
        return loadNote;
    }

    private String loadNote;

    /** B7e: the load line ("chests.json: broken, loaded the backup (12 entries); ..."), for "memory". */
    public synchronized String loadNote() { return loadNote; }

    private String loadOne(String name, Map<String, JsonObject> into) {
        into.clear();
        String text = files.readJson(name);
        if (text == null) return name + ": none yet";
        String err = parseInto(text, into);
        if (err == null) return name + ": " + into.size() + " entries";
        problem = name + " was broken (" + err + ")";
        String bak = files.readJson(bakName(name));
        into.clear();
        if (bak != null && parseInto(bak, into) == null) {
            dirtySince = 0;                 // write the good copy back (the broken file is kept aside first)
            setAside(name, text);
            return name + ": broken, loaded the backup (" + into.size() + " entries)";
        }
        setAside(name, text);
        return name + ": broken and no good backup, starting empty";
    }

    /** rs.json: {"grid": "x y z", "readings": {...}}; a broken file starts empty (the next "rs" reads the grid again). */
    private String loadRs() {
        rs.clear();
        rsGrid = null;
        String text = files.readJson(RS);
        if (text == null) return RS + ": none yet";
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (o.has("grid") && o.get("grid").isJsonPrimitive()) rsGrid = o.get("grid").getAsString();
            if (o.has("readings") && o.get("readings").isJsonObject()) {
                for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("readings").entrySet()) {
                    if (en.getValue().isJsonObject()) rs.put(en.getKey(), en.getValue().getAsJsonObject());
                }
            }
            return RS + ": " + rs.size() + " readings";
        } catch (RuntimeException e) {
            setAside(RS, text);
            return RS + ": broken, starting empty";
        }
    }

    private JsonObject rsJson() {
        JsonObject o = new JsonObject();
        if (rsGrid != null) o.addProperty("grid", rsGrid);
        o.add("readings", mapJson(rs));
        return o;
    }

    private void setAside(String name, String text) {
        // the raw text may not be JSON at all: keep it as a string inside a JSON object
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        files.writeJson(name.replace(".json", ".broken.json"), o.toString());
    }

    static String bakName(String name) { return name.replace(".json", ".bak.json"); }

    static String parseInto(String text, Map<String, JsonObject> into) {
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) return "not a JSON object";
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                if (en.getValue().isJsonObject()) into.put(en.getKey(), en.getValue().getAsJsonObject());
            }
            return null;
        } catch (RuntimeException ex) {
            return ex.getMessage();
        }
    }

    /**
     * Applies changes {"places":{name: obj | null}, "chests":{key: obj | null}} (null deletes). Returns the
     * new version (unchanged when nothing changed).
     */
    public synchronized long put(String json, long now) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        boolean changed = apply(o, "places", places) | apply(o, "chests", chests) | apply(o, "rs", rs) | applyGrid(o, false);
        if (changed) {
            prune(null);
            touch(now);
        }
        return version;
    }

    /**
     * Package H: past {@link Limits#CHESTS} chest notes (or {@link Limits#RS_READINGS} readings) the oldest seen go
     * (never an untrusted chest, never {@code keep}, the one just written); the grid "rs" uses stays. The bridge prunes
     * memory.json the same way. Returns how many went.
     */
    private int prune(String keep) {
        int c = 0, r = 0;
        // chest notes near a marked place (the base's, the mine's) stay too (Limits.NEAR_PLACE)
        for (String k : Limits.oldest(chests, Limits.CHESTS, keep, places)) {
            chests.remove(k);
            c++;
        }
        for (String k : Limits.oldest(rs, Limits.RS_READINGS, rsGrid)) {
            rs.remove(k);
            r++;
        }
        if (c + r > 0) pruneNote = "dropped the oldest " + (c > 0 ? c + " chest note" + (c == 1 ? "" : "s") : "")
                + (c > 0 && r > 0 ? " and " : "") + (r > 0 ? r + " RS reading" + (r == 1 ? "" : "s") : "") + " (over the limit)";
        return c + r;
    }

    private String pruneNote;

    /** What the last pruning dropped, once (Core logs it), or null. */
    public synchronized String takePruneNote() {
        String s = pruneNote;
        pruneNote = null;
        return s;
    }

    /** "rsGrid": "x y z" | null; onlyIfNone: a merge keeps the mod's own grid. */
    private boolean applyGrid(JsonObject o, boolean onlyIfNone) {
        if (!o.has("rsGrid")) return false;
        String g = o.get("rsGrid").isJsonNull() ? null : o.get("rsGrid").getAsString();
        if (onlyIfNone && (rsGrid != null || g == null)) return false;
        if (java.util.Objects.equals(g, rsGrid)) return false;
        rsGrid = g;
        return true;
    }

    private static boolean apply(JsonObject o, String key, Map<String, JsonObject> map) {
        if (!o.has(key) || !o.get(key).isJsonObject()) return false;
        boolean changed = false;
        for (Map.Entry<String, JsonElement> en : o.getAsJsonObject(key).entrySet()) {
            JsonElement v = en.getValue();
            if (v == null || v.isJsonNull()) {
                if (map.remove(en.getKey()) != null) changed = true;
            } else if (v.isJsonObject() && !v.equals(map.get(en.getKey()))) {
                map.put(en.getKey(), v.getAsJsonObject().deepCopy());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * The bridge's notes at its load: places it has win (it is where "mark" happens), a chest note wins
     * when it was seen later; what only one side has is kept. Returns the new version.
     */
    public synchronized long merge(String json, long now) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        boolean changed = false;
        if (o.has("places") && o.get("places").isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("places").entrySet()) {
                if (!en.getValue().isJsonObject() || en.getValue().equals(places.get(en.getKey()))) continue;
                places.put(en.getKey(), en.getValue().getAsJsonObject().deepCopy());
                changed = true;
            }
        }
        if (o.has("chests") && o.get("chests").isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("chests").entrySet()) {
                if (!en.getValue().isJsonObject()) continue;
                JsonObject theirs = en.getValue().getAsJsonObject(), mine = chests.get(en.getKey());
                if (mine != null && (mine.equals(theirs) || seen(mine) > seen(theirs))) continue;
                chests.put(en.getKey(), theirs.deepCopy());
                changed = true;
            }
        }
        if (o.has("rs") && o.get("rs").isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("rs").entrySet()) {
                if (!en.getValue().isJsonObject()) continue;
                JsonObject theirs = en.getValue().getAsJsonObject(), mine = rs.get(en.getKey());
                if (mine != null && (mine.equals(theirs) || seen(mine) > seen(theirs))) continue;
                rs.put(en.getKey(), theirs.deepCopy());
                changed = true;
            }
        }
        changed |= applyGrid(o, true);
        if (changed) {
            prune(null);
            touch(now);
        }
        return version;
    }

    static long seen(JsonObject chest) {
        try { return chest.has("seen") ? chest.get("seen").getAsLong() : 0; } catch (RuntimeException e) { return 0; }
    }

    /** A chest's note from the mod itself (the food run opened it). */
    public synchronized void noteChest(String key, JsonObject note, long now) {
        if (note.equals(chests.get(key))) return;
        chests.put(key, note.deepCopy());
        prune(key);
        touch(now);
    }

    /** Drops a chest note (a scan found the block gone, or the other half of a double chest). */
    public synchronized void forgetChest(String key, long now) {
        if (chests.remove(key) != null) touch(now);
    }

    /** A Refined Storage reading from the mod ("rs"); that grid becomes the one "rs take/put" use. */
    public synchronized void noteRs(String key, JsonObject reading, long now) {
        boolean changed = !reading.equals(rs.get(key)) || !key.equals(rsGrid);
        rs.put(key, reading.deepCopy());
        rsGrid = key;
        if (changed) {
            prune(null);
            touch(now);
        }
    }

    public synchronized Map<String, JsonObject> rs() { return copy(rs); }

    public synchronized String rsGrid() { return rsGrid; }

    private void touch(long now) {
        version++;
        if (dirtySince < 0) dirtySince = now;
    }

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", version);
        o.add("places", mapJson(places));
        o.add("chests", mapJson(chests));
        o.add("rs", mapJson(rs));
        if (rsGrid != null) o.addProperty("rsGrid", rsGrid);
        return o;
    }

    public synchronized Map<String, JsonObject> places() { return copy(places); }

    public synchronized Map<String, JsonObject> chests() { return copy(chests); }

    private static Map<String, JsonObject> copy(Map<String, JsonObject> m) {
        Map<String, JsonObject> out = new TreeMap<>();
        for (Map.Entry<String, JsonObject> e : m.entrySet()) out.put(e.getKey(), e.getValue().deepCopy());
        return out;
    }

    private static JsonObject mapJson(Map<String, JsonObject> m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, JsonObject> e : m.entrySet()) o.add(e.getKey(), e.getValue().deepCopy());
        return o;
    }

    /** Once a tick: writes the files FLUSH_AFTER ticks after a change, and the backups every BACKUP_EVERY ticks. */
    public synchronized void flushIfDue(long now) {
        if (files == null) return;
        if (dirtySince >= 0 && now - dirtySince >= FLUSH_AFTER) flush();
        if (lastBackup < 0) lastBackup = now;
        if (now - lastBackup >= BACKUP_EVERY) {
            lastBackup = now;
            if (!places.isEmpty()) files.writeJson(bakName(PLACES), mapJson(places).toString());
            if (!chests.isEmpty()) files.writeJson(bakName(CHESTS), mapJson(chests).toString());
            if (!rs.isEmpty()) files.writeJson(bakName(RS), rsJson().toString());
        }
    }

    /** Writes both files now. "ok" or the error. */
    public synchronized String flush() {
        if (files == null) return "error: no folder yet";
        String a = files.writeJson(PLACES, mapJson(places).toString());
        String b = files.writeJson(CHESTS, mapJson(chests).toString());
        String c = files.writeJson(RS, rsJson().toString());
        if (a.startsWith("ok") && b.startsWith("ok") && c.startsWith("ok")) {
            dirtySince = -1;
            return "ok";
        }
        return !a.startsWith("ok") ? a : !b.startsWith("ok") ? b : c;
    }
}
