package io.github.mojolowjo.entropybot.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.memory.Limits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.28.0: the chunk summaries on disk, {@code entropybot/sense/<dim folder>/<cx>.<cz>.json}, one per chunk, written on
 * change at most every {@link Limits#SENSE_WRITE_MS} ({@link Dirty}), kept across restarts and joins (never wiped). The
 * index is not a file: it is rebuilt from these at load ({@link #loadDir}), every summary stale until its chunk arrives.
 *
 * <p>Format v1: {@code {"v":1,"dim":..,"cx":..,"cz":..,"seen":ms,"ores":{"iron":{"n":3,"p":[[x,y,z,"minecraft:iron_ore"],..]}},
 * "pois":{"spawner":{"n":1,"p":[[x,y,z,"minecraft:spawner"]]}},"lava":{"3":[count,x,y,z]},"tops":[256]}}.
 * Plain Java (Gson, java.nio), JUnit. Loader notes: none. Errors: a broken file is skipped and counted (never fatal).
 */
public final class SummaryFiles {
    private SummaryFiles() {}

    public static final String FOLDER = "sense";

    public static String dimFolder(String dim) { return dim.replace(':', '_').replace('/', '_'); }

    public static String fileName(int cx, int cz) { return cx + "." + cz + ".json"; }

    public static String toJson(ChunkSummary s) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("dim", s.dim);
        o.addProperty("cx", s.cx);
        o.addProperty("cz", s.cz);
        o.addProperty("seen", s.seenMs);
        o.add("ores", groups(s.ores));
        o.add("pois", groups(s.pois));
        JsonObject lava = new JsonObject();
        for (Map.Entry<Integer, int[]> e : s.lava.entrySet()) {
            JsonArray a = new JsonArray();
            for (int v : e.getValue()) a.add(v);
            lava.add(String.valueOf(e.getKey()), a);
        }
        o.add("lava", lava);
        JsonArray tops = new JsonArray();
        for (int t : s.tops) tops.add(t);
        o.add("tops", tops);
        return o.toString();
    }

    private static JsonObject groups(Map<String, ChunkSummary.Group> m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, ChunkSummary.Group> e : m.entrySet()) {
            JsonObject g = new JsonObject();
            g.addProperty("n", e.getValue().n);
            JsonArray p = new JsonArray();
            for (ChunkSummary.Spot sp : e.getValue().spots) {
                JsonArray a = new JsonArray();
                a.add(sp.x());
                a.add(sp.y());
                a.add(sp.z());
                a.add(sp.id());
                p.add(a);
            }
            g.add("p", p);
            o.add(e.getKey(), g);
        }
        return o;
    }

    /** Throws (IllegalArgumentException, IllegalStateException, ...) on anything malformed. */
    public static ChunkSummary fromJson(String text) {
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        if (!o.has("v") || o.get("v").getAsInt() != 1) throw new IllegalArgumentException("not version 1");
        ChunkSummary s = new ChunkSummary(o.get("dim").getAsString(), o.get("cx").getAsInt(), o.get("cz").getAsInt());
        s.seenMs = o.has("seen") ? o.get("seen").getAsLong() : 0;
        readGroups(o.getAsJsonObject("ores"), s.ores);
        readGroups(o.getAsJsonObject("pois"), s.pois);
        if (o.has("lava")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("lava").entrySet()) {
            JsonArray a = e.getValue().getAsJsonArray();
            if (a.size() != 4) throw new IllegalArgumentException("lava entry of " + a.size());
            s.lava.put(Integer.parseInt(e.getKey()), new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt()});
        }
        JsonArray tops = o.getAsJsonArray("tops");
        if (tops == null || tops.size() != 256) throw new IllegalArgumentException("tops not 256");
        for (int i = 0; i < 256; i++) s.tops[i] = tops.get(i).getAsInt();
        return s;
    }

    private static void readGroups(JsonObject o, Map<String, ChunkSummary.Group> into) {
        if (o == null) return;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            JsonObject g = e.getValue().getAsJsonObject();
            ChunkSummary.Group gr = new ChunkSummary.Group();
            for (JsonElement p : g.getAsJsonArray("p")) {
                JsonArray a = p.getAsJsonArray();
                if (gr.spots.size() < Limits.SENSE_SPOTS)
                    gr.spots.add(new ChunkSummary.Spot(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsString()));
            }
            gr.n = Math.max(g.get("n").getAsInt(), gr.spots.size());
            if (gr.n > 0) into.put(e.getKey(), gr);
        }
    }

    public record Loaded(List<ChunkSummary> list, int broken, String firstError) {}

    /** Every summary file in a dimension's folder (a missing folder: none). A broken file is skipped and counted. */
    public static Loaded loadDir(Path dir) {
        List<ChunkSummary> out = new ArrayList<>();
        int broken = 0;
        String first = null;
        if (dir == null || !Files.isDirectory(dir)) return new Loaded(out, 0, null);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : ds) {
                try {
                    out.add(fromJson(Files.readString(f, StandardCharsets.UTF_8)));
                } catch (IOException | RuntimeException e) {
                    broken++;
                    if (first == null) first = f.getFileName() + ": " + e;
                }
            }
        } catch (IOException | RuntimeException e) {
            broken++;
            if (first == null) first = dir.getFileName() + ": " + e;
        }
        return new Loaded(out, broken, first);
    }

    public static Path file(Path root, ChunkSummary s) {
        return root.resolve(FOLDER).resolve(dimFolder(s.dim)).resolve(fileName(s.cx, s.cz));
    }

    /**
     * Which summaries to write: a change marks one dirty; it is due when its last write is at least {@link Limits#SENSE_WRITE_MS}
     * ago; a failed write leaves it dirty (due again on the next turn). Keys are "dim|cx|cz".
     */
    public static final class Dirty {
        private final Map<String, Long> dirty = new HashMap<>(), lastWrite = new HashMap<>();
        private final long every;

        public Dirty() { this(Limits.SENSE_WRITE_MS); }

        public Dirty(long every) { this.every = every; }

        public static String key(String dim, int cx, int cz) { return dim + "|" + cx + "|" + cz; }

        public void mark(String k, long now) { dirty.putIfAbsent(k, now); }

        public List<String> due(long now, int max) {
            List<String> out = new ArrayList<>();
            for (String k : dirty.keySet()) {
                Long lw = lastWrite.get(k);
                if (lw == null || now - lw >= every) out.add(k);
                if (out.size() >= max) break;
            }
            for (String k : out) dirty.remove(k);
            return out;
        }

        public void written(String k, long now) {
            lastWrite.put(k, now);
            if (lastWrite.size() > 20000) lastWrite.values().removeIf(t -> now - t > every);
        }

        public void failed(String k, long now) { dirty.putIfAbsent(k, now); }

        public int pending() { return dirty.size(); }

        /** Everything dirty, at once (leaving the world). */
        public List<String> all() {
            List<String> out = new ArrayList<>(dirty.keySet());
            dirty.clear();
            return out;
        }
    }
}
