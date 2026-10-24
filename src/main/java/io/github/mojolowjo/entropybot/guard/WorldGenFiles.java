package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 0.39.z26 (2): the pure half of the world-gen block read (the structure fact's "crafted and not world-gen" leg). The block
 * ids world gen places, from the data the client's jars carry (the same data packs the loot book reads, JarPacks): every
 * block state's {@code "Name"} in {@code data/<ns>/worldgen/configured_feature/*.json} and
 * {@code data/<ns>/worldgen/noise_settings/*.json} (the same JSON the registries' codecs write). 0.39.z26 (3): the structure
 * templates ({@code data/<ns>/structure/*.nbt}) are not read: villages and fossils are built things, so their blocks stay
 * structure blocks (STRUCTURE_BLOCKS.md). Then the cache file (per mod list) and the owner's override
 * file. No name of ours: every id comes from the files.
 */
public final class WorldGenFiles {
    private WorldGenFiles() {}

    /** The JSON folders under data/<ns>/ that hold block states world gen places. */
    public static final List<String> JSON_DIRS = List.of("worldgen/configured_feature", "worldgen/noise_settings");
    /**
     * 0.39.z45 (rule 19): the folders the ore placements are read from as well, on singleplayer and a server alike: the
     * placed features (their placement modifiers), the biomes (which placed features each lists) and NeoForge's biome
     * modifiers ({@code add_features}) with the placed-feature tags they may name. Read with {@link #JSON_DIRS} in one read.
     */
    public static final List<String> PLACEMENT_DIRS = List.of("worldgen/placed_feature", "worldgen/biome", "neoforge/biome_modifier", "tags/worldgen/placed_feature");
    /** The owner's correction, in the entropybot folder: {"<block id>": true (world gen places it) | false (it does not)}. */
    public static final String OVERRIDE_FILE = "worldgen-override.json";
    static final int CACHE_VERSION = 3;  // structural: the cache file's format version (2: no template blocks; 3: the ore placements)

    /** One file of the read: its id ("ns:path" under data/ns) and how to open it. */
    public interface Entry {
        String id();
        InputStream open() throws IOException;
    }

    /** What one read made: the block ids, the files read, the files skipped and the first such error, and the ore placements. */
    public record Read(Set<String> blocks, int files, int bad, String firstBad, Ores ores) {
        public Read(Set<String> blocks, int files, int bad, String firstBad) { this(blocks, files, bad, firstBad, Ores.EMPTY); }
    }

    /**
     * 0.39.z45: one placed feature that places ore: its id, the blocks its configured feature targets (an ore-shaped config:
     * {@code size} and {@code targets[].state}), the vein size and the placement list as the JSON writes it.
     */
    public record Ore(String id, List<String> blocks, int size, JsonArray placement) {}

    /** A biome modifier's add: the biomes it names (ids or #tags, the game's biome tags resolve them) and the placed features it adds. */
    public record Modifier(List<String> biomes, List<String> features) {}

    /**
     * 0.39.z45: the ore placements of the read: placed feature id -> its {@link Ore}; biome id -> the placed features it lists
     * (tags expanded); the biome modifiers' adds; and the entries not read (a placed feature whose configured feature is
     * not in the jars, an unknown feature tag, a biome modifier of another kind), counted for the log.
     */
    public record Ores(Map<String, Ore> placed, Map<String, List<String>> biomes, List<Modifier> modifiers, int unread, String firstUnread) {
        public static final Ores EMPTY = new Ores(Map.of(), Map.of(), List.of(), 0, null);
    }

    /** Reads the JSON entries' block-state names into one set and the ore placements; a broken file is counted, never thrown. */
    public static Read read(Collection<? extends Entry> json) {
        Set<String> names = new HashSet<>();
        int files = 0, bad = 0;
        String firstBad = null;
        Map<String, JsonElement> configured = new HashMap<>(), placed = new HashMap<>(), biomes = new HashMap<>(), modifiers = new HashMap<>(), tags = new HashMap<>();
        for (Entry e : json) {
            try (InputStream in = e.open(); Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement j = JsonParser.parseReader(r);
                String dir = dirOf(e.id());
                String id = shortId(e.id(), dir);
                if (dir == null || JSON_DIRS.contains(dir)) collect(j, names);
                if ("worldgen/configured_feature".equals(dir)) configured.put(id, j);
                else if ("worldgen/placed_feature".equals(dir)) placed.put(id, j);
                else if ("worldgen/biome".equals(dir)) biomes.put(id, j);
                else if ("neoforge/biome_modifier".equals(dir)) modifiers.put(id, j);
                else if ("tags/worldgen/placed_feature".equals(dir)) tags.put(id, j);
                files++;
            } catch (IOException | RuntimeException x) {
                bad++;
                if (firstBad == null) firstBad = e.id() + ": " + x;
            }
        }
        return new Read(Set.copyOf(names), files, bad, firstBad, ores(configured, placed, biomes, modifiers, tags));
    }

    /** The read's folder of an entry id ("ns:worldgen/placed_feature/x.json" -> "worldgen/placed_feature"); null: none of ours. */
    static String dirOf(String entryId) {
        String path = entryId.substring(entryId.indexOf(':') + 1);
        for (String d : JSON_DIRS) if (path.startsWith(d + "/")) return d;
        for (String d : PLACEMENT_DIRS) if (path.startsWith(d + "/")) return d;
        return null;
    }

    /** "ns:worldgen/placed_feature/ore_iron_middle.json" -> "ns:ore_iron_middle". */
    static String shortId(String entryId, String dir) {
        int c = entryId.indexOf(':');
        String path = entryId.substring(c + 1);
        if (dir != null) path = path.substring(dir.length() + 1);
        if (path.endsWith(".json")) path = path.substring(0, path.length() - ".json".length());
        return entryId.substring(0, c + 1) + path;
    }

    /** The join: each placed feature to the configured feature it places (an ore-shaped one), the biomes' lists and the modifiers, tags expanded. */
    static Ores ores(Map<String, JsonElement> configured, Map<String, JsonElement> placed, Map<String, JsonElement> biomes,
                     Map<String, JsonElement> modifiers, Map<String, JsonElement> tags) {
        int[] unread = {0};
        String[] first = {null};
        java.util.function.Consumer<String> miss = w -> { unread[0]++; if (first[0] == null) first[0] = w; };
        Map<String, Ore> out = new java.util.TreeMap<>();
        for (var e : placed.entrySet()) {
            if (!e.getValue().isJsonObject()) { miss.accept(e.getKey() + ": not an object"); continue; }
            JsonObject p = e.getValue().getAsJsonObject();
            JsonElement f = p.get("feature");
            JsonElement cf = f != null && f.isJsonPrimitive() ? configured.get(id(f.getAsString())) : f;
            if (cf == null) { miss.accept(e.getKey() + ": its configured feature " + f + " is not in the jars"); continue; }
            Ore o = ore(e.getKey(), cf, p.get("placement"));
            if (o != null) out.put(e.getKey(), o);
        }
        Map<String, List<String>> bs = new java.util.TreeMap<>();
        for (var e : biomes.entrySet()) {
            List<String> l = new java.util.ArrayList<>();
            JsonElement fs = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("features") : null;
            if (fs != null && fs.isJsonArray()) for (JsonElement step : fs.getAsJsonArray()) features(step, tags, l, miss, e.getKey());
            bs.put(e.getKey(), List.copyOf(l));
        }
        List<Modifier> ms = new java.util.ArrayList<>();
        for (var e : new java.util.TreeMap<>(modifiers).entrySet()) {
            JsonObject m = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
            String type = m != null && m.has("type") ? m.get("type").getAsString() : "";
            if (m == null || !type.endsWith(":add_features")) { miss.accept(e.getKey() + ": biome modifier " + type + " not read (only add_features is)"); continue; }
            List<String> b = new java.util.ArrayList<>(), f = new java.util.ArrayList<>();
            strings(m.get("biomes"), b);
            features(m.get("features"), tags, f, miss, e.getKey());
            ms.add(new Modifier(List.copyOf(b), List.copyOf(f)));
        }
        return new Ores(Map.copyOf(out), Map.copyOf(bs), List.copyOf(ms), unread[0], first[0]);
    }

    /** A configured feature JSON of the ore shape (config.size, config.targets[].state.Name) -> its Ore; null: not ore-shaped. */
    static Ore ore(String placedId, JsonElement cf, JsonElement placement) {
        if (cf == null || !cf.isJsonObject() || placement == null || !placement.isJsonArray()) return null;
        JsonElement c = cf.getAsJsonObject().get("config");
        if (c == null || !c.isJsonObject()) return null;
        JsonObject co = c.getAsJsonObject();
        if (!co.has("size") || !co.get("size").isJsonPrimitive() || !co.has("targets") || !co.get("targets").isJsonArray()) return null;
        List<String> blocks = new java.util.ArrayList<>();
        for (JsonElement t : co.getAsJsonArray("targets")) {
            if (!t.isJsonObject() || !t.getAsJsonObject().has("state")) continue;
            Set<String> n = new java.util.LinkedHashSet<>();
            collect(t.getAsJsonObject().get("state"), n);
            for (String b : n) if (!blocks.contains(b)) blocks.add(b);
        }
        if (blocks.isEmpty()) return null;
        return new Ore(placedId, List.copyOf(blocks), co.get("size").getAsInt(), placement.getAsJsonArray());
    }

    /** A feature list (an id, "#tag", or an array of them) into ids, the tags expanded from the jars' placed-feature tags. */
    private static void features(JsonElement e, Map<String, JsonElement> tags, List<String> out, java.util.function.Consumer<String> miss, String where) {
        List<String> raw = new java.util.ArrayList<>();
        strings(e, raw);
        for (String s : raw) expand(s, tags, out, miss, where, 0);
    }

    private static void expand(String s, Map<String, JsonElement> tags, List<String> out, java.util.function.Consumer<String> miss, String where, int depth) {
        if (!s.startsWith("#")) { String i = id(s); if (!out.contains(i)) out.add(i); return; }
        JsonElement t = tags.get(id(s.substring(1)));
        if (t == null || !t.isJsonObject() || depth > tags.size()) { miss.accept(where + ": placed-feature tag " + s + " not in the jars"); return; }
        List<String> vals = new java.util.ArrayList<>();
        JsonElement v = t.getAsJsonObject().get("values");
        if (v != null && v.isJsonArray()) for (JsonElement x : v.getAsJsonArray())
            if (x.isJsonPrimitive()) vals.add(x.getAsString());
            else if (x.isJsonObject() && x.getAsJsonObject().has("id")) vals.add(x.getAsJsonObject().get("id").getAsString());
        for (String x : vals) expand(x, tags, out, miss, where, depth + 1);
    }

    private static void strings(JsonElement e, List<String> out) {
        if (e == null) return;
        if (e.isJsonPrimitive()) out.add(e.getAsString());
        else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) strings(x, out);
    }

    private static String id(String s) { return s.contains(":") ? s : "minecraft:" + s; }

    /**
     * 0.39.z45: the ore band facts for the given biomes (the client's own biomes where it stands: the chunk data the server
     * sends every client): block id -> its height-placed features, each placed feature once. {@code modifierBiome}: whether
     * a biome modifier's biome entry (an id or a #tag) names one of the biomes (the game's synced biome tags).
     * {@code bottom}/{@code top}: the dimension's build height for the anchors. A placement whose count or height the
     * reader does not know is left out and counted in {@code unpriced[0]} when given.
     */
    public static Map<String, List<io.github.mojolowjo.entropybot.aa.brain.OreBand.Feature>> features(Ores o, Collection<String> biomes,
            java.util.function.Predicate<String> modifierBiome, int bottom, int top, int[] unpriced) {
        Set<String> pfs = new java.util.LinkedHashSet<>();
        for (String b : biomes) pfs.addAll(o.biomes().getOrDefault(b, List.of()));
        for (Modifier m : o.modifiers()) {
            boolean in = false;
            for (String b : m.biomes()) if (modifierBiome.test(b)) { in = true; break; }
            if (in) pfs.addAll(m.features());
        }
        Map<String, List<io.github.mojolowjo.entropybot.aa.brain.OreBand.Feature>> out = new java.util.TreeMap<>();
        for (String pf : pfs) {
            Ore ore = o.placed().get(pf);
            if (ore == null) continue;                       // not an ore placement
            var f = io.github.mojolowjo.entropybot.aa.brain.OreBand.of(ore.placement(), ore.size(), bottom, top);
            if (f == null) { if (unpriced != null && heightPlaced(ore.placement())) unpriced[0]++; continue; }
            for (String b : ore.blocks()) out.computeIfAbsent(b, k -> new java.util.ArrayList<>()).add(f);
        }
        return out;
    }

    /** Every biome the read knows and every biome modifier's entry: the whole data's ore features (for the resolver's Y range). */
    public static Set<String> allBiomes(Ores o) { return o.biomes().keySet(); }

    private static boolean heightPlaced(JsonArray placement) {
        for (JsonElement e : placement)
            if (e.isJsonObject() && e.getAsJsonObject().has("type") && e.getAsJsonObject().get("type").getAsString().endsWith("height_range")) return true;
        return false;
    }

    /**
     * One block's structure fact from the game's facts and the world-gen set (null: not loaded yet, a crafted block then
     * answers NOT_LOADED, never cached as no). The table (StructureTable) and the tests use it.
     */
    public static BreakPolicy.Structure fact(boolean tagged, boolean oreOrLog, boolean entity, boolean station, boolean crafted, Set<String> gen, String id) {
        return BreakPolicy.structureOf(true, tagged, oreOrLog, entity, station, crafted, gen == null ? null : gen.contains(id));
    }

    /**
     * One block's structure fact from its tags (StructureTable's tag keys {@code structureTags} / {@code neverTags} and
     * {@code inTag}, the game's tag lookup of the block), the other game facts and the world-gen set; {@code unread}: a
     * recipe making it could not be read (NOT_LOADED, never "not crafted"). StructureTable's whole per-block rule, so the
     * tests run it over a fixture tag holder.
     */
    public static <T> BreakPolicy.Structure classify(java.util.List<T> structureTags, java.util.List<T> neverTags, java.util.function.Predicate<T> inTag,
                                                     boolean entity, boolean station, boolean crafted, boolean unread, Set<String> gen, String id) {
        if (unread) return BreakPolicy.Structure.NOT_LOADED;
        boolean tagged = false, never = false;
        for (T t : structureTags) if (inTag.test(t)) { tagged = true; break; }
        for (T t : neverTags) if (inTag.test(t)) { never = true; break; }
        return fact(tagged, never, entity, station, crafted, gen, id);
    }

    /** Every block state's "Name" in the JSON. */
    public static void collect(JsonElement e, Set<String> names) {
        if (e == null) return;
        if (e.isJsonArray()) { for (JsonElement c : e.getAsJsonArray()) collect(c, names); return; }
        if (!e.isJsonObject()) return;
        for (var en : e.getAsJsonObject().entrySet()) {
            if (en.getKey().equals("Name") && en.getValue().isJsonPrimitive()) names.add(en.getValue().getAsString());
            else collect(en.getValue(), names);
        }
    }

    // ---- the cache: entropybot/worldgen-<hash>.json (hash: LootFiles.hash of the mod list) ----

    public static String cacheName(String hash) { return "worldgen-" + hash + ".json"; }

    public static String toCache(String hash, Read r) {
        JsonObject o = new JsonObject();
        o.addProperty("version", CACHE_VERSION);
        o.addProperty("hash", hash);
        o.addProperty("files", r.files());
        o.addProperty("bad", r.bad());
        JsonArray a = new JsonArray();
        for (String b : new TreeSet<>(r.blocks())) a.add(b);
        o.add("blocks", a);
        Ores ores = r.ores() == null ? Ores.EMPTY : r.ores();
        JsonObject oj = new JsonObject();
        JsonArray pl = new JsonArray();
        for (Ore x : ores.placed().values()) {
            JsonObject p = new JsonObject();
            p.addProperty("id", x.id());
            JsonArray bl = new JsonArray();
            for (String b : x.blocks()) bl.add(b);
            p.add("blocks", bl);
            p.addProperty("size", x.size());
            p.add("placement", x.placement());
            pl.add(p);
        }
        oj.add("placed", pl);
        JsonObject bi = new JsonObject();
        for (var e : new java.util.TreeMap<>(ores.biomes()).entrySet()) bi.add(e.getKey(), array(e.getValue()));
        oj.add("biomes", bi);
        JsonArray ms = new JsonArray();
        for (Modifier m : ores.modifiers()) {
            JsonObject mo = new JsonObject();
            mo.add("biomes", array(m.biomes()));
            mo.add("features", array(m.features()));
            ms.add(mo);
        }
        oj.add("modifiers", ms);
        oj.addProperty("unread", ores.unread());
        if (ores.firstUnread() != null) oj.addProperty("firstUnread", ores.firstUnread());
        o.add("ores", oj);
        return o.toString();
    }

    private static JsonArray array(List<String> l) {
        JsonArray a = new JsonArray();
        for (String s : l) a.add(s);
        return a;
    }

    private static List<String> list(JsonElement e) {
        List<String> l = new java.util.ArrayList<>();
        strings(e, l);
        return List.copyOf(l);
    }

    private static Ores oresFromCache(JsonObject o) {
        if (!o.has("ores") || !o.get("ores").isJsonObject()) throw new IllegalArgumentException("cache has no ore placements");
        JsonObject oj = o.getAsJsonObject("ores");
        Map<String, Ore> placed = new java.util.TreeMap<>();
        for (JsonElement e : oj.getAsJsonArray("placed")) {
            JsonObject p = e.getAsJsonObject();
            placed.put(p.get("id").getAsString(), new Ore(p.get("id").getAsString(), list(p.get("blocks")), p.get("size").getAsInt(), p.getAsJsonArray("placement")));
        }
        Map<String, List<String>> biomes = new java.util.TreeMap<>();
        for (var e : oj.getAsJsonObject("biomes").entrySet()) biomes.put(e.getKey(), list(e.getValue()));
        List<Modifier> ms = new java.util.ArrayList<>();
        for (JsonElement e : oj.getAsJsonArray("modifiers")) ms.add(new Modifier(list(e.getAsJsonObject().get("biomes")), list(e.getAsJsonObject().get("features"))));
        return new Ores(Map.copyOf(placed), Map.copyOf(biomes), List.copyOf(ms), oj.has("unread") ? oj.get("unread").getAsInt() : 0,
                oj.has("firstUnread") ? oj.get("firstUnread").getAsString() : null);
    }

    /** The read back from a cache file; one that is not this hash's or not whole throws IllegalArgumentException with the reason. */
    public static Read fromCache(String text, String hash) {
        JsonObject o = object(text, "cache");
        if (!o.has("version") || o.get("version").getAsInt() != CACHE_VERSION) throw new IllegalArgumentException("cache version is not " + CACHE_VERSION);
        if (!o.has("hash") || !hash.equals(o.get("hash").getAsString())) throw new IllegalArgumentException("cache is for another mod list");
        if (!o.has("blocks") || !o.get("blocks").isJsonArray()) throw new IllegalArgumentException("cache has no blocks list");
        Set<String> s = new HashSet<>();
        for (JsonElement e : o.getAsJsonArray("blocks")) s.add(e.getAsString());
        Ores ores;
        try {
            ores = oresFromCache(o);
        } catch (RuntimeException x) {
            throw new IllegalArgumentException("cache's ore placements are not whole (" + x + ")");
        }
        return new Read(Set.copyOf(s), o.has("files") ? o.get("files").getAsInt() : 0, o.has("bad") ? o.get("bad").getAsInt() : 0, null, ores);
    }

    // ---- the owner's override: entropybot/worldgen-override.json ----

    /** The override file: block id -> true (count it as world-gen) or false (do not). A broken entry throws IllegalArgumentException naming it. */
    public static Map<String, Boolean> override(String text) {
        JsonObject o = object(text, OVERRIDE_FILE);
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (var e : o.entrySet()) {
            JsonElement v = e.getValue();
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(e.getKey() + ": must be true (world gen places it) or false");
            String id = e.getKey().trim().toLowerCase(Locale.ROOT);
            out.put(id.contains(":") ? id : "minecraft:" + id, v.getAsBoolean());
        }
        return out;
    }

    /** The set with the override applied. */
    public static Set<String> apply(Set<String> blocks, Map<String, Boolean> override) {
        Set<String> s = new HashSet<>(blocks);
        for (var e : override.entrySet()) if (e.getValue()) s.add(e.getKey()); else s.remove(e.getKey());
        return Set.copyOf(s);
    }

    private static JsonObject object(String text, String what) {
        JsonElement e;
        try {
            e = JsonParser.parseString(text == null ? "" : text);
        } catch (JsonParseException x) {
            throw new IllegalArgumentException(what + " is not JSON (" + x.getMessage() + ")");
        }
        if (e == null || !e.isJsonObject()) throw new IllegalArgumentException(what + " is not a JSON object");
        return e.getAsJsonObject();
    }
}
