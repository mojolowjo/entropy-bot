package io.github.mojolowjo.entropybot.sense;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.strip.OreSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * BRAIN_PLAN 2.6 (B5): where ores turn up, learned from the chunks the {@link ChunkPass} reads (multiplayer has no
 * worldgen data). Per dimension and ore family a Y histogram; each chunk counts once (its key is kept, so a resend or a
 * restart does not count it twice). Pure: ChunkPass feeds it, saves it ({@link #toJson}) and loads it ({@link #fromJson}).
 * Positions are the summary's listed spots (capped per chunk and family), so counts are samples, not exact totals.
 */
public final class OreStats {
    /** Chunks kept (a cap on the file and memory; past it new chunks are ignored). */
    public static final int MAX_CHUNKS = 150_000;
    /** The peak is the busiest band of this many blocks. */
    public static final int BAND = 8;
    private static final Gson GSON = new Gson();

    /** dim -> family -> (Y -> samples). */
    private final Map<String, Map<String, TreeMap<Integer, Integer>>> hist = new TreeMap<>();
    /** dim -> chunk keys counted. */
    private final Map<String, Set<Long>> counted = new HashMap<>();
    private boolean dirty;
    private long chunks;

    /** The learned range of a family in one dimension: n samples, the 5th..95th percentile Y, min/max seen, the busiest band's middle. */
    public record Range(String dim, String family, int n, int minY, int maxY, int lowY, int highY, int peakY) {
        public String text() {
            return family + " in " + dim + ": " + n + " seen, Y " + lowY + ".." + highY + " (extremes " + minY + ".." + maxY + "), most at " + peakY;
        }
    }

    public boolean dirty() { return dirty; }

    public void saved() { dirty = false; }

    public long chunks() { return chunks; }

    /** Count a freshly read chunk once. Returns whether it was new. */
    public synchronized boolean addChunk(ChunkSummary s) {
        if (s == null || s.dim == null) return false;
        Set<Long> seen = counted.computeIfAbsent(s.dim, k -> new HashSet<>());
        long key = ChunkSummary.key(s.cx, s.cz);
        if (seen.contains(key) || chunks >= MAX_CHUNKS) return false;
        seen.add(key);
        chunks++;
        dirty = true;
        for (ChunkSummary.Group g : s.ores.values())
            for (ChunkSummary.Spot sp : g.spots) add(s.dim, OreSpec.family(sp.id()), sp.y());
        return true;
    }

    synchronized void add(String dim, String family, int y) {
        hist.computeIfAbsent(dim, k -> new TreeMap<>()).computeIfAbsent(family, k -> new TreeMap<>()).merge(y, 1, Integer::sum);
        dirty = true;
    }

    /** The range of a family in one dimension, or null with no samples. */
    public synchronized Range range(String dim, String family) {
        Map<String, TreeMap<Integer, Integer>> d = hist.get(dim);
        TreeMap<Integer, Integer> h = d == null ? null : d.get(family);
        return h == null || h.isEmpty() ? null : build(dim, family, h);
    }

    /** The dimension where the family was seen most, or null. */
    public synchronized Range best(String family) {
        Range best = null;
        for (String dim : hist.keySet()) {
            Range r = range(dim, family);
            if (r != null && (best == null || r.n() > best.n())) best = r;
        }
        return best;
    }

    /** Every family seen, most samples first (best dimension each). */
    public synchronized List<Range> all() {
        java.util.Set<String> fams = new java.util.TreeSet<>();
        for (Map<String, TreeMap<Integer, Integer>> d : hist.values()) fams.addAll(d.keySet());
        List<Range> out = new ArrayList<>();
        for (String f : fams) {
            Range r = best(f);
            if (r != null) out.add(r);
        }
        out.sort((a, b) -> Integer.compare(b.n(), a.n()));
        return out;
    }

    private static Range build(String dim, String family, TreeMap<Integer, Integer> h) {
        int n = 0;
        for (int c : h.values()) n += c;
        int low = h.firstKey(), high = h.lastKey(), acc = 0;
        boolean gotLow = false, gotHigh = false;
        int lowY = low, highY = high;
        for (Map.Entry<Integer, Integer> e : h.entrySet()) {
            acc += e.getValue();
            if (!gotLow && acc * 20 >= n) { lowY = e.getKey(); gotLow = true; }
            if (!gotHigh && acc * 20 >= n * 19) { highY = e.getKey(); gotHigh = true; }
        }
        Map<Integer, Integer> bands = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : h.entrySet()) bands.merge(Math.floorDiv(e.getKey(), BAND), e.getValue(), Integer::sum);
        int bestBand = 0, bestN = -1;
        for (Map.Entry<Integer, Integer> e : bands.entrySet())
            if (e.getValue() > bestN) { bestN = e.getValue(); bestBand = e.getKey(); }
        return new Range(dim, family, n, low, high, lowY, highY, bestBand * BAND + BAND / 2);
    }

    // ---- file ----

    public synchronized String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject hs = new JsonObject();
        for (Map.Entry<String, Map<String, TreeMap<Integer, Integer>>> d : hist.entrySet()) {
            JsonObject fo = new JsonObject();
            for (Map.Entry<String, TreeMap<Integer, Integer>> f : d.getValue().entrySet()) {
                JsonObject ys = new JsonObject();
                for (Map.Entry<Integer, Integer> y : f.getValue().entrySet()) ys.addProperty(String.valueOf(y.getKey()), y.getValue());
                fo.add(f.getKey(), ys);
            }
            hs.add(d.getKey(), fo);
        }
        root.add("hist", hs);
        JsonObject cs = new JsonObject();
        for (Map.Entry<String, Set<Long>> d : counted.entrySet()) {
            JsonArray a = new JsonArray();
            for (long k : d.getValue()) a.add(k);
            cs.add(d.getKey(), a);
        }
        root.add("chunks", cs);
        return GSON.toJson(root);
    }

    /** Load from a saved file. Bad parts are skipped; the second value is how many were (so the caller can say so). */
    public static OreStats fromJson(String json, int[] skipped) {
        OreStats o = new OreStats();
        JsonObject root;
        try {
            JsonElement e = GSON.fromJson(json, JsonElement.class);
            if (e == null || !e.isJsonObject()) { skipped[0]++; return o; }
            root = e.getAsJsonObject();
        } catch (RuntimeException ex) {
            skipped[0]++;
            return o;
        }
        try {
            if (root.has("hist")) for (Map.Entry<String, JsonElement> d : root.getAsJsonObject("hist").entrySet())
                for (Map.Entry<String, JsonElement> f : d.getValue().getAsJsonObject().entrySet())
                    for (Map.Entry<String, JsonElement> y : f.getValue().getAsJsonObject().entrySet()) {
                        try {
                            int yy = Integer.parseInt(y.getKey()), c = y.getValue().getAsInt();
                            if (c > 0) o.hist.computeIfAbsent(d.getKey(), k -> new TreeMap<>()).computeIfAbsent(f.getKey(), k -> new TreeMap<>()).merge(yy, c, Integer::sum);
                        } catch (RuntimeException ex) { skipped[0]++; }
                    }
            if (root.has("chunks")) for (Map.Entry<String, JsonElement> d : root.getAsJsonObject("chunks").entrySet()) {
                Set<Long> set = o.counted.computeIfAbsent(d.getKey(), k -> new HashSet<>());
                for (JsonElement k : d.getValue().getAsJsonArray()) {
                    try { if (set.add(k.getAsLong())) o.chunks++; } catch (RuntimeException ex) { skipped[0]++; }
                }
            }
        } catch (RuntimeException ex) {
            skipped[0]++;
        }
        o.dirty = false;
        return o;
    }

    /** "ore info": the learned range text for a family word (matching the family or its tail), else a plain reason. */
    public synchronized String info(String word) {
        String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        if (w.isEmpty()) {
            List<Range> all = all();
            if (all.isEmpty()) return "no ores learned yet (" + chunks + " chunks counted)";
            List<String> lines = new ArrayList<>();
            for (Range r : all) lines.add(r.text());
            return "learned from " + chunks + " chunks:\n" + String.join("\n", lines);
        }
        List<String> lines = new ArrayList<>();
        for (Range r : all()) {
            String f = r.family();
            if (f.equals(w) || f.endsWith(":" + w) || f.replaceFirst("^.*:", "").equals(w) || (w.endsWith("s") && f.replaceFirst("^.*:", "").equals(w.substring(0, w.length() - 1))))
                for (String dim : hist.keySet()) {
                    Range d = range(dim, f);
                    if (d != null) lines.add(d.text());
                }
        }
        return lines.isEmpty() ? "no " + w + " seen yet (" + chunks + " chunks counted)" : String.join("\n", lines);
    }
}
