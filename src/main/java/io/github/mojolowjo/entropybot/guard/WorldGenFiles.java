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

import io.github.mojolowjo.entropybot.aa.brain.GenWhere;

/**
 * 0.39.z26 (2): the pure half of the world-gen block read (the structure fact's "crafted and not world-gen" leg). The block
 * ids world gen places, from the data the client's jars carry (the same data packs the loot book reads, JarPacks): every
 * block state's {@code "Name"} in {@code data/<ns>/worldgen/configured_feature/*.json} and
 * {@code data/<ns>/worldgen/noise_settings/*.json} (the same JSON the registries' codecs write), and since 0.39.z77 the plain
 * block ids the configured features name ({@link #blockIds}: dripstone_block), and the carvers' tags ({@link #CARVER_DIRS}).
 * 0.39.z26 (3): the structure templates ({@code data/<ns>/structure/*.nbt}) are not read: villages and fossils are built
 * things, so their blocks stay structure blocks (STRUCTURE_BLOCKS.md). Then the cache file (per mod list). 0.39.z77
 * natural 2: the owner's override file is deleted (a name list, rule 4). No name of ours: every id comes from the files.
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
    public static final List<String> PLACEMENT_DIRS = List.of("worldgen/placed_feature", "worldgen/biome", "neoforge/biome_modifier", "tags/worldgen/placed_feature",
            "dimension", "worldgen/world_preset");     // 0.39.z74 item 4: which noise settings each dimension generates with
    /**
     * 0.39.z77 natural 2: the carvers' folder. A carver's {@code replaceable} is the block set it cuts through, the
     * generated terrain (vanilla: {@code #overworld_carver_replaceables}, which holds the ore veins' raw_iron_block and
     * raw_copper_block). Only its tags and plain block ids are taken, never its states: a carver's {@code debug_settings}
     * name glass, buttons and candles.
     */
    public static final List<String> CARVER_DIRS = List.of("worldgen/configured_carver");
    static final int CACHE_VERSION = 8;  // structural: the cache file's format version (2: no template blocks; 3: the ore placements; 4: the "where" placers; 5: vein size and air-exposure discard; 6: the base blocks; 7: the configured features' plain block ids; 8: the carvers' tags)
    /** 0.39.z77 natural 2: the structure fact's reason for a crafted block no world-gen source names (the dropped list says it). */
    public static final String NOT_PLACED = "not placed by any feature the jars name";

    /** One file of the read: its id ("ns:path" under data/ns) and how to open it. */
    public interface Entry {
        String id();
        InputStream open() throws IOException;
    }

    /**
     * What one read made: the block ids, the files read, the files skipped and the first such error, the ore placements, and
     * (0.39.z77 natural 2) the block tags the carvers name ({@code carverTags}: tag ids without the "#").
     */
    public record Read(Set<String> blocks, int files, int bad, String firstBad, Ores ores, Set<String> carverTags) {
        public Read {
            carverTags = carverTags == null ? Set.of() : Set.copyOf(carverTags);
        }
        public Read(Set<String> blocks, int files, int bad, String firstBad, Ores ores) { this(blocks, files, bad, firstBad, ores, Set.of()); }
        public Read(Set<String> blocks, int files, int bad, String firstBad) { this(blocks, files, bad, firstBad, Ores.EMPTY); }
    }

    /**
     * 0.39.z45: one placed feature that places ore: its id, the blocks its configured feature targets (an ore-shaped config:
     * {@code size} and {@code targets[].state}), the vein size and the placement list as the JSON writes it.
     */
    public record Ore(String id, List<String> blocks, int size, JsonArray placement, double discard) {
        /** No air-exposure discard (0.39.z69: the configured feature's {@code discard_chance_on_air_exposure}, 0 when absent). */
        public Ore(String id, List<String> blocks, int size, JsonArray placement) { this(id, blocks, size, placement, 0); }
    }

    /** A biome modifier's add: the biomes it names (ids or #tags, the game's biome tags resolve them) and the placed features it adds. */
    public record Modifier(List<String> biomes, List<String> features) {}

    /**
     * 0.39.z45: the ore placements of the read: placed feature id -> its {@link Ore}; biome id -> the placed features it lists
     * (tags expanded); the biome modifiers' adds; and the entries not read (a placed feature whose configured feature is
     * not in the jars, an unknown feature tag, a biome modifier of another kind), counted for the log.
     */
    public record Ores(Map<String, Ore> placed, Map<String, List<String>> biomes, List<Modifier> modifiers, int unread, String firstUnread,
                       List<GenWhere.Placer> placers, Map<String, GenWhere.Base> bases, Map<String, String> dims) {
        public static final Ores EMPTY = new Ores(Map.of(), Map.of(), List.of(), 0, null, List.of());

        /**
         * 0.39.z74 item 4: {@code bases} - noise settings id -> its base block ({@link GenWhere#base}); {@code dims} - dimension
         * id -> the noise settings it generates with (the dimension / world preset files).
         */
        public Ores {
            placers = placers == null ? List.of() : List.copyOf(placers);
            bases = bases == null ? Map.of() : Map.copyOf(bases);
            dims = dims == null ? Map.of() : Map.copyOf(dims);
        }

        public Ores(Map<String, Ore> placed, Map<String, List<String>> biomes, List<Modifier> modifiers, int unread, String firstUnread, List<GenWhere.Placer> placers) {
            this(placed, biomes, modifiers, unread, firstUnread, placers, Map.of(), Map.of());
        }

        /** The base block of dimension {@code dim}: its generator's settings (else the settings of its own id); null: not known. */
        public GenWhere.Base baseOf(String dim) {
            if (dim == null) return null;
            GenWhere.Base b = bases.get(dims.getOrDefault(dim, dim));
            return b != null ? b : bases.get(dim);
        }

        /** No "where" placers (0.39.z68): the ore placements only. */
        public Ores(Map<String, Ore> placed, Map<String, List<String>> biomes, List<Modifier> modifiers, int unread, String firstUnread) {
            this(placed, biomes, modifiers, unread, firstUnread, List.of());
        }
    }

    /**
     * The read without the game's block registry (the ore / placement tests): block-state names only, no plain block ids
     * ({@link #blockIds}). The mod's read (WorldGenReader) always passes the registry.
     */
    public static Read read(Collection<? extends Entry> json) { return read(json, null); }

    /**
     * Reads the JSON entries' block-state names into one set, plus the plain block ids the configured features name
     * ({@link #blockIds}; {@code isBlock}: the game's block registry, null: not taken), the carvers' tags and plain block
     * ids ({@link #CARVER_DIRS}), and the ore placements; a broken file is counted, never thrown.
     */
    public static Read read(Collection<? extends Entry> json, java.util.function.Predicate<String> isBlock) {
        Set<String> names = new HashSet<>(), carverTags = new java.util.TreeSet<>();
        int files = 0, bad = 0;
        String firstBad = null;
        Map<String, JsonElement> configured = new HashMap<>(), placed = new HashMap<>(), biomes = new HashMap<>(), modifiers = new HashMap<>(), tags = new HashMap<>();
        Map<String, GenWhere.Base> bases = new java.util.TreeMap<>();      // 0.39.z74 item 4
        Map<String, String> dims = new java.util.TreeMap<>();
        for (Entry e : json) {
            try (InputStream in = e.open(); Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement j = JsonParser.parseReader(r);
                String dir = dirOf(e.id());
                String id = shortId(e.id(), dir);
                if (dir == null || JSON_DIRS.contains(dir)) collect(j, names);
                if ("worldgen/configured_feature".equals(dir) && isBlock != null) blockIds(j, isBlock, names, false);   // 0.39.z77 natural
                if (dir != null && CARVER_DIRS.contains(dir)) {                         // 0.39.z77 natural 2: tags and plain ids, never the debug states
                    tags(j, carverTags);
                    if (isBlock != null) blockIds(j, isBlock, names, true);
                }
                if ("worldgen/configured_feature".equals(dir)) configured.put(id, j);
                else if ("worldgen/placed_feature".equals(dir)) placed.put(id, j);
                else if ("worldgen/biome".equals(dir)) biomes.put(id, j);
                else if ("neoforge/biome_modifier".equals(dir)) modifiers.put(id, j);
                else if ("tags/worldgen/placed_feature".equals(dir)) tags.put(id, j);
                else if ("worldgen/noise_settings".equals(dir)) { GenWhere.Base b = GenWhere.base(id, j); if (b != null) bases.put(id, b); }
                else if ("dimension".equals(dir)) GenWhere.dimensions(id, j, false, dims);
                else if ("worldgen/world_preset".equals(dir)) GenWhere.dimensions(id, j, true, dims);
                files++;
            } catch (IOException | RuntimeException x) {
                bad++;
                if (firstBad == null) firstBad = e.id() + ": " + x;
            }
        }
        Ores o = ores(configured, placed, biomes, modifiers, tags);
        return new Read(Set.copyOf(names), files, bad, firstBad, new Ores(o.placed(), o.biomes(), o.modifiers(), o.unread(), o.firstUnread(), o.placers(), bases, dims),
                carverTags);
    }

    /** The read's folder of an entry id ("ns:worldgen/placed_feature/x.json" -> "worldgen/placed_feature"); null: none of ours. */
    static String dirOf(String entryId) {
        String path = entryId.substring(entryId.indexOf(':') + 1);
        for (String d : JSON_DIRS) if (path.startsWith(d + "/")) return d;
        for (String d : CARVER_DIRS) if (path.startsWith(d + "/")) return d;
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
        return new Ores(Map.copyOf(out), Map.copyOf(bs), List.copyOf(ms), unread[0], first[0], placers(configured, placed));
    }

    /**
     * 0.39.z68: every placed feature whose placement says where (a {@code heightmap} or a {@code height_range} modifier,
     * {@link io.github.mojolowjo.entropybot.aa.brain.GenWhere}) with the blocks its feature tree places: the block-state
     * {@code Name}s of its configured feature, following the placed / configured features the tree names by id (a tree
     * selector names its placed trees). The placement lists of nested placed features are not read for blocks (their
     * {@code would_survive} states are predicates, not placed blocks); every {@code type} string is skipped.
     */
    static List<GenWhere.Placer> placers(Map<String, JsonElement> configured, Map<String, JsonElement> placed) {
        List<GenWhere.Placer> out = new java.util.ArrayList<>();
        for (var e : new java.util.TreeMap<>(placed).entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject p = e.getValue().getAsJsonObject();
            JsonElement pl = p.get("placement");
            if (pl == null || !pl.isJsonArray() || !says(pl.getAsJsonArray())) continue;
            Set<String> names = new java.util.TreeSet<>();
            Set<String> visited = new HashSet<>();
            visited.add("placed " + e.getKey());
            reach(p.get("feature"), configured, placed, names, visited, true);
            if (names.isEmpty()) continue;
            // 0.39.z69 item 2: an ore-shaped configured feature gives its vein size and air-exposure discard (the density curve)
            JsonElement f = p.get("feature");
            JsonElement cf = f != null && f.isJsonPrimitive() ? configured.get(id(f.getAsString())) : f;
            Ore o = ore(e.getKey(), cf, pl);
            out.add(o == null ? new GenWhere.Placer(e.getKey(), List.copyOf(names), pl.getAsJsonArray())
                    : new GenWhere.Placer(e.getKey(), List.copyOf(names), pl.getAsJsonArray(), o.size(), o.discard()));
        }
        return List.copyOf(out);
    }

    /** A placement list with a heightmap or a height_range modifier (the two kinds that say where). */
    private static boolean says(JsonArray placement) {
        for (JsonElement m : placement) {
            if (!m.isJsonObject() || !m.getAsJsonObject().has("type")) continue;
            String t = m.getAsJsonObject().get("type").getAsString();
            if (t.endsWith("heightmap") || t.endsWith("height_range")) return true;
        }
        return false;
    }

    /** The block names a feature tree places; {@code feature}: a string here is a configured feature's id (a placed feature's "feature"). */
    private static void reach(JsonElement e, Map<String, JsonElement> configured, Map<String, JsonElement> placed, Set<String> names, Set<String> visited, boolean feature) {
        if (e == null) return;
        if (e.isJsonArray()) { for (JsonElement c : e.getAsJsonArray()) reach(c, configured, placed, names, visited, false); return; }
        if (e.isJsonPrimitive()) {
            if (!e.getAsJsonPrimitive().isString()) return;
            String id = id(e.getAsString());
            if (!feature && placed.containsKey(id) && visited.add("placed " + id)) {
                JsonElement p = placed.get(id);
                if (p.isJsonObject()) reach(p.getAsJsonObject().get("feature"), configured, placed, names, visited, true);
            } else if (configured.containsKey(id) && visited.add("configured " + id)) reach(configured.get(id), configured, placed, names, visited, false);
            return;
        }
        if (!e.isJsonObject()) return;
        for (var en : e.getAsJsonObject().entrySet()) {
            String k = en.getKey();
            if (k.equals("type") || k.equals("placement")) continue;
            if (k.equals("Name") && en.getValue().isJsonPrimitive()) { names.add(en.getValue().getAsString()); continue; }
            reach(en.getValue(), configured, placed, names, visited, false);   // a placed id first (a selector's entry), else a configured one
        }
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
        // 0.39.z69 item 2: OreConfiguration's discard_chance_on_air_exposure (absent: 0, the codec's default)
        JsonElement dc = co.get("discard_chance_on_air_exposure");
        double discard = dc != null && dc.isJsonPrimitive() ? dc.getAsDouble() : 0;
        return new Ore(placedId, List.copyOf(blocks), co.get("size").getAsInt(), placement.getAsJsonArray(), discard);
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
        Set<String> pfs = placedIn(o, biomes, modifierBiome);
        Map<String, List<io.github.mojolowjo.entropybot.aa.brain.OreBand.Feature>> out = new java.util.TreeMap<>();
        for (String pf : pfs) {
            Ore ore = o.placed().get(pf);
            if (ore == null) continue;                       // not an ore placement
            var f = io.github.mojolowjo.entropybot.aa.brain.OreBand.of(ore.placement(), ore.size(), ore.discard(), bottom, top);
            if (f == null) { if (unpriced != null && heightPlaced(ore.placement())) unpriced[0]++; continue; }
            for (String b : ore.blocks()) out.computeIfAbsent(b, k -> new java.util.ArrayList<>()).add(f);
        }
        return out;
    }

    /**
     * 0.39.z69 fix 2 (run AA: #stone_tool_materials got blackstone's band 5..31 in the overworld, the nether's ore_blackstone
     * merged in): the placed features the given biomes place - each biome JSON's {@code features} lists (tags expanded) and
     * the NeoForge biome modifiers whose biome entry names one of them ({@code modifierBiome}: the game's synced biome tags).
     * How the dimension is known from the data: a placed feature belongs to a dimension only through the biomes that list
     * it, and the biomes of the bot's dimension are the ones the server's chunk data carries for the level the bot is in
     * (the client's biome cells); a nether ore is listed only by nether biomes, so it never shows in the overworld's set.
     */
    public static Set<String> placedIn(Ores o, Collection<String> biomes, java.util.function.Predicate<String> modifierBiome) {
        Set<String> pfs = new java.util.LinkedHashSet<>();
        for (String b : biomes) pfs.addAll(o.biomes().getOrDefault(b, List.of()));
        for (Modifier m : o.modifiers()) {
            boolean in = false;
            for (String b : m.biomes()) if (modifierBiome.test(b)) { in = true; break; }
            if (in) pfs.addAll(m.features());
        }
        return pfs;
    }

    /** 0.39.z69 fix 2: the "where" placers of the given biomes only ({@link #placedIn}: the bot's dimension's biomes). */
    public static List<GenWhere.Placer> placersIn(Ores o, Collection<String> biomes, java.util.function.Predicate<String> modifierBiome) {
        Set<String> pfs = placedIn(o, biomes, modifierBiome);
        List<GenWhere.Placer> out = new java.util.ArrayList<>();
        for (GenWhere.Placer p : o.placers()) if (pfs.contains(p.id())) out.add(p);
        return out;
    }

    /**
     * 0.39.z69 fix 2: {minY, maxY} where any of {@code blocks} is height-placed in {@code features} (block -> its features,
     * {@link #features} for the bot's dimension's biomes); null: none of them is.
     */
    public static int[] band(Map<String, List<io.github.mojolowjo.entropybot.aa.brain.OreBand.Feature>> features, Collection<String> blocks) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (String b : blocks) for (var f : features.getOrDefault(b, List.of())) { lo = Math.min(lo, f.lo()); hi = Math.max(hi, f.hi()); }
        return lo == Integer.MAX_VALUE ? null : new int[]{lo, hi};
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

    /**
     * 0.39.z77 natural 2 / 3 (manager 2026-10-26): StructureTable's per-block rule with the two world-gen sources. A crafted
     * block is never a structure block when one holds: (1) the jars' world-gen names it ({@code gen}: {@link #read});
     * (2) it is in a tag a carver of the jars names ({@code carverTags}, null: the read is not done). Drops are not a
     * terrain test (natural 3: redstone_wire drops redstone_ore's item and is exactly what rule 16 protects). A tag, a
     * block entity or a station stays a structure block either way (vanilla's carver tag holds suspicious_gravel /
     * suspicious_sand, block entities).
     */
    public static <T> BreakPolicy.Structure classify(java.util.List<T> structureTags, java.util.List<T> neverTags, java.util.function.Predicate<T> inTag,
                                                     java.util.List<T> carverTags, boolean entity, boolean station, boolean crafted, boolean unread,
                                                     Set<String> gen, String id) {
        if (unread) return BreakPolicy.Structure.NOT_LOADED;
        boolean tagged = false, never = false;
        for (T t : structureTags) if (inTag.test(t)) { tagged = true; break; }
        for (T t : neverTags) if (inTag.test(t)) { never = true; break; }
        Boolean carver = null;
        if (carverTags != null) {
            carver = false;
            for (T t : carverTags) if (inTag.test(t)) { carver = true; break; }
        }
        return BreakPolicy.structureOf(true, tagged, never, entity, station, crafted, natural(gen, carver, id));
    }

    /** The two sources' answer (rule 15): one says yes = natural; else one not loaded = not loaded (null); else no. */
    public static Boolean natural(Set<String> gen, Boolean inCarverTag, String id) {
        Boolean named = gen == null ? null : gen.contains(id);
        if (Boolean.TRUE.equals(named) || Boolean.TRUE.equals(inCarverTag)) return Boolean.TRUE;
        if (named == null || inCarverTag == null) return null;
        return Boolean.FALSE;
    }

    /** Why a block is a structure block (the rule line, the dropped list): its tag, a block entity, a station, or {@link #NOT_PLACED}. */
    public static String structureWhy(String tag, boolean entity, boolean station) {
        if (tag != null) return "tag #" + tag;
        if (entity) return "a block entity";
        if (station) return "a recipe type's station";
        return NOT_PLACED;
    }

    /** 0.39.z77 natural 2: every block tag a JSON names ("#ns:path" strings, the "#" dropped), codec type ids skipped. */
    public static void tags(JsonElement e, Set<String> out) {
        if (e == null) return;
        if (e.isJsonArray()) { for (JsonElement c : e.getAsJsonArray()) tags(c, out); return; }
        if (e.isJsonPrimitive()) {
            if (e.getAsJsonPrimitive().isString() && e.getAsString().startsWith("#")) out.add(e.getAsString().substring(1));
            return;
        }
        if (!e.isJsonObject()) return;
        for (var en : e.getAsJsonObject().entrySet()) if (!en.getKey().endsWith("type")) tags(en.getValue(), out);
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

    /**
     * 0.39.z77 natural (run CJ 17:25: the strip's next cell, a dripstone_block, refused as "structure block outside my
     * areas"; dripstone_block has a crafting recipe, so only the world-gen leg keeps it terrain): the block ids a configured
     * feature names in plain form, beside the block-state {@code Name}s {@link #collect} takes. The game's feature configs
     * write some blocks by id, not as a state ({@code BuiltInRegistries.BLOCK.byNameCodec()} or a block holder set:
     * MultifaceGrowthConfiguration's {@code block} and {@code can_be_placed_on}, SpringConfiguration's {@code valid_blocks},
     * a matching_blocks predicate's {@code blocks}), and some feature types place their blocks in code (DripstoneClusterFeature
     * and DripstoneUtils set dripstone_block and pointed_dripstone; their configs hold numbers only), so the data names
     * dripstone_block only as ground the glow_lichen and sculk_vein features grow on. A block world gen's own data names as
     * part of the generated world is terrain, never a "crafted and not world-gen" structure block. Every string value
     * that is a block id ({@code isBlock}: the game's block registry) is taken, except a codec type id (a key ending in
     * "type": the feature, provider and predicate types). {@code skipStates}: a state's {@code Name} is not taken (a
     * carver: its debug states are not terrain).
     */
    public static void blockIds(JsonElement e, java.util.function.Predicate<String> isBlock, Set<String> names, boolean skipStates) {
        if (e == null) return;
        if (e.isJsonArray()) { for (JsonElement c : e.getAsJsonArray()) blockIds(c, isBlock, names, skipStates); return; }
        if (e.isJsonPrimitive()) {
            if (e.getAsJsonPrimitive().isString() && isBlock.test(e.getAsString())) names.add(e.getAsString());
            return;
        }
        if (!e.isJsonObject()) return;
        for (var en : e.getAsJsonObject().entrySet()) {
            if (en.getKey().endsWith("type") || (skipStates && en.getKey().equals("Name"))) continue;
            blockIds(en.getValue(), isBlock, names, skipStates);
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
        JsonArray ct = new JsonArray();                       // 0.39.z77 natural 2 (version 8)
        for (String t : new TreeSet<>(r.carverTags())) ct.add(t);
        o.add("carverTags", ct);
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
            p.addProperty("discard", x.discard());           // 0.39.z69 (version 5)
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
        JsonArray ws = new JsonArray();                       // 0.39.z68: the "where" placers
        for (var w : ores.placers()) {
            JsonObject wo = new JsonObject();
            wo.addProperty("id", w.id());
            wo.add("blocks", array(w.blocks()));
            wo.add("placement", w.placement());
            wo.addProperty("size", w.size());                // 0.39.z69 (version 5)
            wo.addProperty("discard", w.discard());
            ws.add(wo);
        }
        oj.add("placers", ws);
        JsonObject bs = new JsonObject();                     // 0.39.z74 item 4 (version 6): the base blocks
        for (var e : new java.util.TreeMap<>(ores.bases()).entrySet()) {
            JsonObject bo = new JsonObject();
            bo.addProperty("block", e.getValue().block());
            bo.addProperty("sureFrom", e.getValue().sureFrom());
            if (e.getValue().deep() != null) bo.addProperty("deep", e.getValue().deep());
            bo.addProperty("why", e.getValue().why());
            bs.add(e.getKey(), bo);
        }
        oj.add("bases", bs);
        JsonObject ds = new JsonObject();
        for (var e : new java.util.TreeMap<>(ores.dims()).entrySet()) ds.addProperty(e.getKey(), e.getValue());
        oj.add("dims", ds);
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
            placed.put(p.get("id").getAsString(), new Ore(p.get("id").getAsString(), list(p.get("blocks")), p.get("size").getAsInt(), p.getAsJsonArray("placement"),
                    p.get("discard").getAsDouble()));      // 0.39.z69 (version 5): missing = not whole, the jars are read again
        }
        Map<String, List<String>> biomes = new java.util.TreeMap<>();
        for (var e : oj.getAsJsonObject("biomes").entrySet()) biomes.put(e.getKey(), list(e.getValue()));
        List<Modifier> ms = new java.util.ArrayList<>();
        for (JsonElement e : oj.getAsJsonArray("modifiers")) ms.add(new Modifier(list(e.getAsJsonObject().get("biomes")), list(e.getAsJsonObject().get("features"))));
        List<GenWhere.Placer> ws = new java.util.ArrayList<>();
        for (JsonElement e : oj.getAsJsonArray("placers")) {          // 0.39.z68 (version 4): missing = not whole, the jars are read again
            JsonObject w = e.getAsJsonObject();
            ws.add(new GenWhere.Placer(w.get("id").getAsString(), list(w.get("blocks")), w.getAsJsonArray("placement"), w.get("size").getAsInt(), w.get("discard").getAsDouble()));
        }
        Map<String, GenWhere.Base> bases = new java.util.TreeMap<>();      // 0.39.z74 (version 6): missing = not whole, the jars are read again
        for (var e : oj.getAsJsonObject("bases").entrySet()) {
            JsonObject b = e.getValue().getAsJsonObject();
            bases.put(e.getKey(), new GenWhere.Base(b.get("block").getAsString(), b.get("sureFrom").getAsInt(), b.has("deep") ? b.get("deep").getAsString() : null, b.get("why").getAsString()));
        }
        Map<String, String> dims = new java.util.TreeMap<>();
        for (var e : oj.getAsJsonObject("dims").entrySet()) dims.put(e.getKey(), e.getValue().getAsString());
        return new Ores(Map.copyOf(placed), Map.copyOf(biomes), List.copyOf(ms), oj.has("unread") ? oj.get("unread").getAsInt() : 0,
                oj.has("firstUnread") ? oj.get("firstUnread").getAsString() : null, ws, bases, dims);
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
        if (!o.has("carverTags") || !o.get("carverTags").isJsonArray()) throw new IllegalArgumentException("cache has no carver tags (version 8)");
        Set<String> ct = new HashSet<>();
        for (JsonElement e : o.getAsJsonArray("carverTags")) ct.add(e.getAsString());
        return new Read(Set.copyOf(s), o.has("files") ? o.get("files").getAsInt() : 0, o.has("bad") ? o.get("bad").getAsInt() : 0, null, ores, ct);
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
