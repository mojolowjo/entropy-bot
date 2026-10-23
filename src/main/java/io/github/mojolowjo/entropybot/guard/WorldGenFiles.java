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
    /** The owner's correction, in the entropybot folder: {"<block id>": true (world gen places it) | false (it does not)}. */
    public static final String OVERRIDE_FILE = "worldgen-override.json";
    static final int CACHE_VERSION = 2;  // structural: the cache file's format version (2: no template blocks)

    /** One file of the read: its id ("ns:path" under data/ns) and how to open it. */
    public interface Entry {
        String id();
        InputStream open() throws IOException;
    }

    /** What one read made: the block ids, the files read, the files skipped and the first such error. */
    public record Read(Set<String> blocks, int files, int bad, String firstBad) {}

    /** Reads the JSON entries' block-state names into one set; a broken file is counted, never thrown. */
    public static Read read(Collection<? extends Entry> json) {
        Set<String> names = new HashSet<>();
        int files = 0, bad = 0;
        String firstBad = null;
        for (Entry e : json) {
            try (InputStream in = e.open(); Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                collect(JsonParser.parseReader(r), names);
                files++;
            } catch (IOException | RuntimeException x) {
                bad++;
                if (firstBad == null) firstBad = e.id() + ": " + x;
            }
        }
        return new Read(Set.copyOf(names), files, bad, firstBad);
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
        return o.toString();
    }

    /** The read back from a cache file; one that is not this hash's or not whole throws IllegalArgumentException with the reason. */
    public static Read fromCache(String text, String hash) {
        JsonObject o = object(text, "cache");
        if (!o.has("version") || o.get("version").getAsInt() != CACHE_VERSION) throw new IllegalArgumentException("cache version is not " + CACHE_VERSION);
        if (!o.has("hash") || !hash.equals(o.get("hash").getAsString())) throw new IllegalArgumentException("cache is for another mod list");
        if (!o.has("blocks") || !o.get("blocks").isJsonArray()) throw new IllegalArgumentException("cache has no blocks list");
        Set<String> s = new HashSet<>();
        for (JsonElement e : o.getAsJsonArray("blocks")) s.add(e.getAsString());
        return new Read(Set.copyOf(s), o.has("files") ? o.get("files").getAsInt() : 0, o.has("bad") ? o.get("bad").getAsInt() : 0, null);
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
