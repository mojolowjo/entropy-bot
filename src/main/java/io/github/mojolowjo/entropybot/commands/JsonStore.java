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

    public synchronized JsonObject data() { return data; }

    public synchronized boolean existed() { return existed; }

    public synchronized String problem() { return problem; }

    /** Loads the file (or its backup). A line for the log. */
    public synchronized String load(BotFiles f) {
        files = f;
        String text = f.readJson(name);
        if (text == null) {
            existed = false;
            data = new JsonObject();
            return name + ": none yet";
        }
        existed = true;
        JsonObject o = parse(text);
        if (o != null) {
            data = o;
            return name + ": ok";
        }
        problem = name + " was broken";
        JsonObject keep = new JsonObject();
        keep.addProperty("text", text);
        f.writeJson(name.replace(".json", ".broken.json"), keep.toString());
        JsonObject bak = parse(f.readJson(name.replace(".json", ".bak.json")));
        data = bak == null ? new JsonObject() : bak;
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
            if (data.size() > 0) files.writeJson(name.replace(".json", ".bak.json"), data.toString());
        }
    }

    public synchronized String flush() {
        if (files == null) return "error: no folder yet";
        String r = files.writeJson(name, data.toString());
        if (r.startsWith("ok")) {
            dirtySince = -1;
            existed = true;
        }
        return r;
    }
}
