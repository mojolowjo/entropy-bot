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
    public static final String PLACES = "places.json", CHESTS = "chests.json";
    static final long FLUSH_AFTER = 40, BACKUP_EVERY = 6000;

    private final Map<String, JsonObject> places = new TreeMap<>();
    private final Map<String, JsonObject> chests = new TreeMap<>();
    private long version;
    private long dirtySince = -1, lastBackup = -1;
    private BotFiles files;
    private String problem;

    public synchronized long version() { return version; }

    public synchronized boolean isEmpty() { return places.isEmpty() && chests.isEmpty(); }

    public synchronized String problem() { return problem; }

    /** Loads both files (or their backups). Returns a line for the log. */
    public synchronized String load(BotFiles f) {
        files = f;
        StringBuilder sb = new StringBuilder();
        sb.append(loadOne(PLACES, places)).append("; ").append(loadOne(CHESTS, chests));
        version = 1;
        return sb.toString();
    }

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
        boolean changed = apply(o, "places", places) | apply(o, "chests", chests);
        if (changed) touch(now);
        return version;
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
        if (changed) touch(now);
        return version;
    }

    static long seen(JsonObject chest) {
        try { return chest.has("seen") ? chest.get("seen").getAsLong() : 0; } catch (RuntimeException e) { return 0; }
    }

    /** A chest's note from the mod itself (the food run opened it). */
    public synchronized void noteChest(String key, JsonObject note, long now) {
        if (note.equals(chests.get(key))) return;
        chests.put(key, note.deepCopy());
        touch(now);
    }

    private void touch(long now) {
        version++;
        if (dirtySince < 0) dirtySince = now;
    }

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", version);
        o.add("places", mapJson(places));
        o.add("chests", mapJson(chests));
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
        }
    }

    /** Writes both files now. "ok" or the error. */
    public synchronized String flush() {
        if (files == null) return "error: no folder yet";
        String a = files.writeJson(PLACES, mapJson(places).toString());
        String b = files.writeJson(CHESTS, mapJson(chests).toString());
        if (a.startsWith("ok") && b.startsWith("ok")) {
            dirtySince = -1;
            return "ok";
        }
        return a.startsWith("ok") ? b : a;
    }
}
