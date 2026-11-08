package io.github.mojolowjo.entropybot.aa.trail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D4: the world's trails, one per dimension, on the brain's board (rule 23: one store; the recorder and the deposit read the
 * same one). NOT_LOADED until the world's trail.json is read (rule 15). Pure (JUnit: D4TrailTest).
 */
public final class Trails {
    public static final String FACT = "aa.trails";
    public static final String FILE = "trail.json";

    private final Map<String, Trail> byDim = new LinkedHashMap<>();
    private String world;
    private boolean loaded;
    private JsonObject file;

    public static Trails of(Blackboard bb) {
        if (bb.fact(FACT) instanceof Trails t) return t;
        Trails t = new Trails();
        bb.fact(FACT, t);
        return t;
    }

    public boolean loaded() { return loaded; }
    public String world() { return world; }

    /** The trail of {@code dim} (NOT_LOADED until {@link #load}). */
    public Trail of(String dim) {
        return byDim.computeIfAbsent(dim, d -> {
            Trail t = new Trail();
            if (loaded) t.load(file != null && file.has(d) && file.get(d).isJsonObject() ? file.getAsJsonObject(d) : null);
            return t;
        });
    }

    /** The world's file read ({@code text} null: none yet). Returns the line to log; an unreadable file starts empty, said. */
    public String load(String worldKey, String text) {
        world = worldKey;
        byDim.clear();
        loaded = true;
        file = null;
        if (text == null) return FILE + ": none yet";
        try {
            JsonElement e = JsonParser.parseString(text);
            file = e.isJsonObject() ? e.getAsJsonObject() : null;
            if (file == null) return FILE + ": unreadable (not an object), starting empty";
            int n = 0;
            for (String d : file.keySet()) if (of(d).state() == Trail.State.OPEN) n++;
            return FILE + ": " + n + " open trail" + (n == 1 ? "" : "s");
        } catch (RuntimeException ex) {
            byDim.clear();
            file = null;
            return FILE + ": unreadable (" + ex.getMessage() + "), starting empty";
        }
    }

    /** A world left: not loaded until the next world's file is read. */
    public void unload() {
        loaded = false;
        world = null;
        byDim.clear();
        file = null;
    }

    public boolean dirty() {
        for (Trail t : byDim.values()) if (t.dirty()) return true;
        return false;
    }

    /** The file's text: every dimension's trail. */
    public String save() {
        JsonObject o = file == null ? new JsonObject() : file.deepCopy();
        for (var e : byDim.entrySet()) o.add(e.getKey(), e.getValue().toJson());
        file = o;
        return o.toString();
    }

    /** The text from {@link #save} is on disk. */
    public void written() {
        for (Trail t : byDim.values()) t.saved();
    }
}
