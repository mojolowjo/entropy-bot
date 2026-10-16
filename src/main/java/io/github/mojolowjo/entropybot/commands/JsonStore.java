package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.io.BotFiles;

/**
 * One JSON object kept in a file under the bot's folder (B7a: {@code commands.json}, {@code areas.json},
 * {@code pm.json}), the way {@code Knowledge} keeps its files: written atomically a second after a change, a
 * backup every few minutes, a broken file set aside (never overwritten) and its backup loaded instead.
 */
public final class JsonStore {
    static final long FLUSH_AFTER = 20, BACKUP_EVERY = 6000;

    private final String name;
    private JsonObject data = new JsonObject();
    private BotFiles files;
    private long dirtySince = -1, lastBackup = -1;
    private String problem;
    private boolean existed;

    public JsonStore(String name) {
        this.name = name;
    }

    // ---- 0.31.0: a store split between the root (global keys) and the world's folder (world keys) ----
    private java.util.Set<String> worldKeys;
    private String worldName;
    private java.util.function.BooleanSupplier mainWorld = () -> false;
    /** World keys found in the root file (before 0.31.0), kept there as "worldLegacy" until the main server takes them. */
    private JsonObject stash = new JsonObject();
    public static final String LEGACY = "worldLegacy";

    /** The world keys go to worldName (a world file, see WorldState) in the current world's folder. */
    public JsonStore(String name, String worldName, java.util.Set<String> worldKeys, java.util.function.BooleanSupplier mainWorld) {
        this.name = name;
        this.worldName = worldName;
        this.worldKeys = worldKeys;
        this.mainWorld = mainWorld;
    }

    private boolean split() { return worldKeys != null; }

    /** The world part of the data from the current world's folder (the root's legacy keys for the main server once). */
    public synchronized String loadWorldPart(BotFiles f) {
        if (!split()) return name + ": not split";
        files = f;
        for (String k : worldKeys) data.remove(k);
        String text = f.readJson(worldName);
        JsonObject w = parse(text);
        if (w != null) {
            for (String k : worldKeys) if (w.has(k)) data.add(k, w.get(k));
            return worldName + ": " + w.size() + " keys";
        }
        if (text != null) {
            LOG_PROBLEM(worldName + " was broken: the world keys start empty (the file is kept as " + worldName.replace(".json", ".broken.json") + ")");
            JsonObject keep = new JsonObject();
            keep.addProperty("text", text);
            f.writeJson(worldName.replace(".json", ".broken.json"), keep.toString());
            return worldName + ": broken, world keys empty";
        }
        if (stash.size() > 0 && mainWorld.getAsBoolean()) {
            int n = 0;
            for (String k : stash.keySet()) { data.add(k, stash.get(k)); n++; }
            stash = new JsonObject();
            dirtySince = 0;
            return worldName + ": none yet, took " + n + " keys from the root's " + name + " (main server)";
        }
        return worldName + ": none yet (starts empty" + (stash.size() > 0 ? "; the root keeps " + stash.size() + " old keys for the main server" : "") + ")";
    }

    private void LOG_PROBLEM(String p) { problem = p; }

    /** Moves world keys found at the root's top level into the stash (never overwriting an older stash entry). */
    private void stashLegacy() {
        if (!split()) return;
        JsonElement l = data.remove(LEGACY);
        if (l != null && l.isJsonObject()) stash = l.getAsJsonObject();
        else stash = new JsonObject();
        for (String k : worldKeys) {
            JsonElement v = data.remove(k);
            if (v != null && !stash.has(k)) stash.add(k, v);
        }
    }

    public synchronized JsonObject data() { return data; }

    public synchronized boolean existed() { return existed; }

    public synchronized String problem() { return problem; }

    /** B7e: the file's name and the load line ("pm.json: broken, loaded the backup"), for "memory". */
    public String name() { return name; }

    public synchronized String loadNote() { return loadNote; }

    private String loadNote;

    /** Loads the file (or its backup). A line for the log. */
    public synchronized String load(BotFiles f) {
        loadNote = load0(f);
        return loadNote;
    }

    private String load0(BotFiles f) {
        files = f;
        String text = f.readJson(name);
        if (text == null) {
            existed = false;
            data = new JsonObject();
            stash = new JsonObject();
            return name + ": none yet" + (split() ? "; " + loadWorldPart(f) : "");
        }
        existed = true;
        JsonObject o = parse(text);
        if (o != null) {
            data = o;
            stashLegacy();
            return name + ": ok" + (split() ? "; " + loadWorldPart(f) : "");
        }
        problem = name + " was broken";
        JsonObject keep = new JsonObject();
        keep.addProperty("text", text);
        f.writeJson(name.replace(".json", ".broken.json"), keep.toString());
        JsonObject bak = parse(f.readJson(name.replace(".json", ".bak.json")));
        data = bak == null ? new JsonObject() : bak;
        stashLegacy();
        if (split()) loadWorldPart(f);
        dirtySince = 0;
        return name + ": broken, " + (bak == null ? "no good backup, starting empty" : "loaded the backup");
    }

    static JsonObject parse(String text) {
        if (text == null || text.startsWith("error:")) return null;
        try {
            JsonElement e = JsonParser.parseString(text);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public synchronized void changed(long now) {
        if (dirtySince < 0) dirtySince = now;
    }

    /** Once a tick. */
    public synchronized void flushIfDue(long now) {
        if (files == null) return;
        if (dirtySince >= 0 && now - dirtySince >= FLUSH_AFTER) flush();
        if (lastBackup < 0) lastBackup = now;
        if (now - lastBackup >= BACKUP_EVERY) {
            lastBackup = now;
            if (data.size() > 0) files.writeJson(name.replace(".json", ".bak.json"), rootJson().toString());
        }
    }

    /** The root file's object: without the world keys, with the legacy stash while there is one. */
    private JsonObject rootJson() {
        if (!split()) return data;
        JsonObject o = new JsonObject();
        for (String k : data.keySet()) if (!worldKeys.contains(k)) o.add(k, data.get(k));
        if (stash.size() > 0) o.add(LEGACY, stash);
        return o;
    }

    public synchronized String flush() {
        if (files == null) return "error: no folder yet";
        String r = files.writeJson(name, rootJson().toString());
        if (r.startsWith("ok") && split()) {
            JsonObject w = new JsonObject();
            for (String k : worldKeys) if (data.has(k)) w.add(k, data.get(k));
            r = files.writeJson(worldName, w.toString());
        }
        if (r.startsWith("ok")) {
            dirtySince = -1;
            existed = true;
        }
        return r;
    }
}
