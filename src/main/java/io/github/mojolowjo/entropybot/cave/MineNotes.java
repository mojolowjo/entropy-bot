package io.github.mojolowjo.entropybot.cave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.clear.OreBook;
import io.github.mojolowjo.entropybot.io.BotFiles;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B7d (D3): the miner's notes that lived in the bridge's memory.json, now the mod's: {@code ores.json} (the ores left
 * in place for players, "x y z" -> {id, dim, seen}; PM "ores") and {@code explored.json} (the chunks explore and the
 * "new land" walks have seen, "dim cx cz", in the order noted). The first load moves memory.ores and memory.explored
 * over from the bridge's memory.json (left there as the rollback copy). Caps as the bridge's LIMITS (package H): 1000
 * listed ores (the oldest seen go), 5000 explored chunks (past it the first-noted go, down to 4500). Written a little
 * after the last change. Plain Java: JUnit drives it with a temp folder.
 */
public final class MineNotes {
    public static final String ORES = "ores.json", EXPLORED = "explored.json";
    public static final int MAX_ORES = 1000, MAX_EXPLORED = 5000, EXPLORED_TO = 4500;
    static final long FLUSH_AFTER = 100;

    private final Map<String, JsonObject> ores = new LinkedHashMap<>();
    private final Set<String> explored = new LinkedHashSet<>();
    private BotFiles files;
    private long oresDirty = -1, exploredDirty = -1, lastTick;
    private String pruneNote;

    /**
     * Loads both files; a missing file is filled from the bridge's memory.json (bridge may be null) and written. A
     * broken file is set aside and starts empty. Returns a line for the log.
     */
    public synchronized String load(BotFiles f, BotFiles bridge) {
        files = f;
        ores.clear();
        explored.clear();
        JsonObject memory = null;
        StringBuilder sb = new StringBuilder();
        String o = f.readJson(ORES), e = f.readJson(EXPLORED);
        if ((o == null || e == null) && bridge != null) {
            String m = bridge.readJson("memory.json");
            if (m != null && !m.startsWith("error")) {
                try { memory = JsonParser.parseString(m).getAsJsonObject(); } catch (RuntimeException ignored) {}
            }
        }
        if (o != null) sb.append(loadOres(o));
        else {
            int n = 0;
            if (memory != null && memory.has("ores") && memory.get("ores").isJsonObject()) n = putOres(memory.getAsJsonObject("ores"));
            sb.append(ORES).append(n > 0 ? ": " + n + " ores from memory.json" : ": none yet");
            if (n > 0) oresDirty = 0;
        }
        sb.append("; ");
        if (e != null) sb.append(loadExplored(e));
        else {
            int n = 0;
            if (memory != null && memory.has("explored") && memory.get("explored").isJsonObject()) {
                for (String k : memory.getAsJsonObject("explored").keySet()) if (explored.add(k)) n++;
            }
            sb.append(EXPLORED).append(n > 0 ? ": " + n + " chunks from memory.json" : ": none yet");
            if (n > 0) exploredDirty = 0;
        }
        int gone = prune();
        if (gone > 0) {
            sb.append("; ").append(pruneNote);
            pruneNote = null;
        }
        return sb.toString();
    }

    private String loadOres(String text) {
        if (text.startsWith("error")) return ORES + ": " + text;
        try {
            int n = putOres(JsonParser.parseString(text).getAsJsonObject());
            return ORES + ": " + n + " ores";
        } catch (RuntimeException ex) {
            setAside(ORES, text);
            ores.clear();
            return ORES + ": broken (" + ex.getMessage() + "), set aside, starting empty";
        }
    }

    private int putOres(JsonObject o) {
        int n = 0;
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            if (!en.getValue().isJsonObject()) continue;
            JsonObject v = en.getValue().getAsJsonObject();
            if (!v.has("id")) continue;
            ores.put(en.getKey(), v.deepCopy());
            n++;
        }
        return n;
    }

    private String loadExplored(String text) {
        if (text.startsWith("error")) return EXPLORED + ": " + text;
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            for (JsonElement k : o.getAsJsonArray("chunks")) explored.add(k.getAsString());
            return EXPLORED + ": " + explored.size() + " chunks";
        } catch (RuntimeException ex) {
            setAside(EXPLORED, text);
            explored.clear();
            return EXPLORED + ": broken (" + ex.getMessage() + "), set aside, starting empty";
        }
    }

    private void setAside(String name, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        files.writeJson(name.replace(".json", ".broken.json"), o.toString());
    }

    // ---- the listed ores ----

    /** Lists the ore at x y z in dim (name: "iron_ore", "oritech:nickel_ore"); true when it wasn't listed yet. */
    public synchronized boolean note(int x, int y, int z, String name, String dim, long nowMs) {
        String k = x + " " + y + " " + z;
        if (ores.containsKey(k)) return false;
        JsonObject o = new JsonObject();
        o.addProperty("id", name);
        o.addProperty("dim", dim);
        o.addProperty("seen", nowMs);
        ores.put(k, o);
        if (ores.size() > MAX_ORES) prune();
        touchOres();
        return true;
    }

    public synchronized boolean forget(String key) {
        if (ores.remove(key) == null) return false;
        touchOres();
        return true;
    }

    public synchronized void clearOres() {
        if (ores.isEmpty()) return;
        ores.clear();
        touchOres();
    }

    public synchronized int oreCount() { return ores.size(); }

    /** A copy of the list: "x y z" -> {id, dim, seen}. */
    public synchronized Map<String, JsonObject> ores() {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        ores.forEach((k, v) -> out.put(k, v.deepCopy()));
        return out;
    }

    /** The clear engine's book for one dimension (D1's clear notes the ores it leaves and forgets the ones it mines). */
    public OreBook book(String dim) {
        return new OreBook() {
            @Override public boolean note(int x, int y, int z, String name) { return MineNotes.this.note(x, y, z, name, dim, System.currentTimeMillis()); }
            @Override public boolean forget(String key) { return MineNotes.this.forget(key); }
        };
    }

    // ---- the explored chunks ----

    public synchronized boolean explored(String key) { return explored.contains(key); }

    /** Marks chunk keys explored; how many were new. */
    public synchronized int markExplored(List<String> keys) {
        int n = 0;
        for (String k : keys) if (explored.add(k)) n++;
        if (n > 0) {
            if (explored.size() > MAX_EXPLORED) prune();
            touchExplored();
        }
        return n;
    }

    public synchronized int exploredCount() { return explored.size(); }

    // ---- caps and files ----

    /** Over a cap: the oldest seen ores go (to MAX_ORES), the first-noted chunks go (to EXPLORED_TO). How many went. */
    synchronized int prune() {
        int o = 0, c = 0;
        if (ores.size() > MAX_ORES) {
            List<Map.Entry<String, JsonObject>> list = new ArrayList<>(ores.entrySet());
            list.sort((a, b) -> Long.compare(seen(a.getValue()), seen(b.getValue())));
            int over = ores.size() - MAX_ORES;
            for (int i = 0; i < over; i++) ores.remove(list.get(i).getKey());
            o = over;
            touchOres();
        }
        if (explored.size() > MAX_EXPLORED) {
            int over = explored.size() - EXPLORED_TO;
            Iterator<String> it = explored.iterator();
            for (int i = 0; i < over && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
            c = over;
            touchExplored();
        }
        if (o + c > 0) pruneNote = "dropped the oldest " + (o > 0 ? o + " listed ores" : "") + (o > 0 && c > 0 ? ", " : "") + (c > 0 ? c + " explored chunks" : "") + " (over the limit)";
        return o + c;
    }

    /** What the last pruning dropped, once, or null. */
    public synchronized String takePruneNote() {
        String s = pruneNote;
        pruneNote = null;
        return s;
    }

    static long seen(JsonObject o) {
        try { return o.has("seen") ? o.get("seen").getAsLong() : 0; } catch (RuntimeException e) { return 0; }
    }

    private void touchOres() { if (oresDirty < 0) oresDirty = lastTick; }

    private void touchExplored() { if (exploredDirty < 0) exploredDirty = lastTick; }

    public synchronized JsonObject oresJson() {
        JsonObject o = new JsonObject();
        ores.forEach((k, v) -> o.add(k, v.deepCopy()));
        return o;
    }

    public synchronized JsonObject exploredJson() {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        for (String k : explored) a.add(k);
        o.add("chunks", a);
        return o;
    }

    /** Once a tick: writes a file FLUSH_AFTER ticks after its first unsaved change. */
    public synchronized void flushIfDue(long tick) {
        lastTick = tick;
        if (files == null) return;
        if (oresDirty >= 0 && tick - oresDirty >= FLUSH_AFTER && files.writeJson(ORES, oresJson().toString()).startsWith("ok")) oresDirty = -1;
        if (exploredDirty >= 0 && tick - exploredDirty >= FLUSH_AFTER && files.writeJson(EXPLORED, exploredJson().toString()).startsWith("ok")) exploredDirty = -1;
    }

    /** Writes whatever is unsaved now. */
    public synchronized void flush() {
        if (files == null) return;
        if (oresDirty >= 0 && files.writeJson(ORES, oresJson().toString()).startsWith("ok")) oresDirty = -1;
        if (exploredDirty >= 0 && files.writeJson(EXPLORED, exploredJson().toString()).startsWith("ok")) exploredDirty = -1;
    }
}
