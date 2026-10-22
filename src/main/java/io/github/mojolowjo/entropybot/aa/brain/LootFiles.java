package io.github.mojolowjo.entropybot.aa.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * loot-book-bg (0.39.z13): the pure half of the loot book read. Loot-table files by id (a data pack's layout:
 * {@code data/<ns>/loot_table/<path>.json}, the plural {@code loot_tables} of older packs too), the book's sources from them
 * ({@link LootBook#parse}: the files are the same JSON the game's LootTable codec writes), the cache key and the cache file,
 * and the owner's override file. The adapter (commands/LootBookReader) finds the files and runs this off the game thread.
 * Nothing here is a name list: which tables are blocks and mobs comes from the game's registries (the adapter's maps), or
 * from the table's own path ({@code blocks/<name>}, {@code entities/<name>}) when no registry is given.
 */
public final class LootFiles {
    private LootFiles() {}

    /** The folders a data pack keeps its loot tables in: 1.21's singular first, then the plural of older packs. */
    public static final List<String> DIRS = List.of("loot_table", "loot_tables");
    /** The owner's manual correction, in the entropybot folder (docs/COMMANDS.md "loot book"). */
    public static final String OVERRIDE_FILE = "lootbook-override.json";
    static final int CACHE_VERSION = 1;  // structural: the cache file's format version

    /** A loot table's id from its namespace and its path under data/<ns>/ ("loot_table/blocks/stone.json" -> "ns:blocks/stone"); null: not a loot table file. */
    public static String tableId(String ns, String path) {
        if (ns == null || path == null || !path.endsWith(".json")) return null;
        for (String d : DIRS) if (path.startsWith(d + "/")) return ns + ":" + path.substring(d.length() + 1, path.length() - ".json".length());
        return null;
    }

    /** A jar entry "data/<ns>/loot_table/<path>.json" -> "<ns>:<path>"; null: not a loot table. */
    public static String entryId(String entry) {
        if (entry == null || !entry.startsWith("data/")) return null;
        int slash = entry.indexOf('/', "data/".length());
        return slash < 0 ? null : tableId(entry.substring("data/".length(), slash), entry.substring(slash + 1));
    }

    /** What one read made: the sources, the tables read into the book, the tables skipped (bad JSON, a parse error) and the first such error. */
    public record Read(List<LootBook.Source> sources, int tables, int bad, String firstBad) {}

    /**
     * The book's sources from every table in {@code ids}: a table the registries name for blocks gives each such block's
     * drops (the block itself left out: gather and mine know it), one for mobs each mob's, a {@code chests/} table its chest
     * loot. {@code lookup}: a table's JSON by id (nested {@code loot_table} entries resolve through it, null: none).
     * {@code blocksByTable} / {@code mobsByTable}: table id -> the block / mob ids whose loot table it is (the game's
     * registries); null: by the table's path ({@code blocks/<name>} -> {@code <ns>:<name>}, {@code entities/<name>}).
     * A table that throws is skipped and counted (the caller logs the count and the first error). progress: one per table.
     */
    public static Read build(Collection<String> ids, Function<String, JsonElement> lookup, Map<String, List<String>> blocksByTable,
                             Map<String, List<String>> mobsByTable, LootBook.Progress progress) {
        List<LootBook.Source> out = new ArrayList<>();
        int read = 0, bad = 0;
        String firstBad = null;
        Function<String, JsonElement> look = id -> lookup.apply(TechTree.id(id));
        for (String id : new TreeSet<>(ids)) {
            if (progress != null) progress.done.incrementAndGet();
            String path = id.substring(id.indexOf(':') + 1);
            List<String> blocks = owners(id, path, "blocks/", blocksByTable), mobs = owners(id, path, "entities/", mobsByTable);
            boolean chest = path.startsWith("chests/");
            if (blocks.isEmpty() && mobs.isEmpty() && !chest) continue;     // gameplay/, archaeology/...: read only through a reference
            try {
                JsonElement t = look.apply(id);
                if (t == null) continue;                                     // no such table (an empty one): nothing drops
                Map<String, double[]> parsed = LootBook.parse(t, look);
                read++;
                for (String m : mobs) out.addAll(LootBook.sources(LootBook.Kind.MOB, m, parsed));
                for (String b : blocks) {
                    Map<String, double[]> p = new HashMap<>(parsed);
                    p.remove(b);
                    out.addAll(LootBook.sources(LootBook.Kind.BLOCK, b, p));
                }
                if (chest) out.addAll(LootBook.sources(LootBook.Kind.CHEST, id, parsed));
            } catch (RuntimeException e) {
                bad++;
                if (firstBad == null) firstBad = id + ": " + e;
            }
        }
        return new Read(out, read, bad, firstBad);
    }

    private static List<String> owners(String id, String path, String prefix, Map<String, List<String>> byTable) {
        if (byTable != null) return byTable.getOrDefault(id, List.of());
        if (!path.startsWith(prefix) || path.length() == prefix.length()) return List.of();
        return List.of(id.substring(0, id.indexOf(':') + 1) + path.substring(prefix.length()));
    }

    // ---- the cache: entropybot/lootbook-<hash>.json ----

    /** The cache key: the sorted "mod id + version" list (every data pack the read used) and the game version, SHA-256, 16 hex digits. */
    public static String hash(Collection<String> modIdVersions, String gameVersion) {
        List<String> l = new ArrayList<>(modIdVersions);
        l.sort(null);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this Java has no SHA-256", e);    // every Java SE has it: a broken runtime, said
        }
        byte[] d = md.digest((gameVersion + "\n" + String.join("\n", l)).getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) b.append(String.format(Locale.ROOT, "%02x", d[i]));  // structural: 8 bytes = 16 hex digits
        return b.toString();
    }

    public static String cacheName(String hash) { return "lootbook-" + hash + ".json"; }

    /** The cache file's text: the loot-table sources of one read (trades are not cached: the game's own list is read each time). */
    public static String toCache(String hash, Read r) {
        JsonObject o = new JsonObject();
        o.addProperty("version", CACHE_VERSION);
        o.addProperty("hash", hash);
        o.addProperty("tables", r.tables());
        o.addProperty("bad", r.bad());
        JsonArray a = new JsonArray();
        for (LootBook.Source s : r.sources()) a.add(source(s));
        o.add("sources", a);
        return o.toString();
    }

    /** The read back from a cache file; a file that is not this hash's or not whole throws IllegalArgumentException with the reason. */
    public static Read fromCache(String text, String hash) {
        JsonObject o = object(text, "cache");
        if (!o.has("version") || o.get("version").getAsInt() != CACHE_VERSION) throw new IllegalArgumentException("cache version is not " + CACHE_VERSION);
        if (!o.has("hash") || !hash.equals(o.get("hash").getAsString())) throw new IllegalArgumentException("cache is for another mod list");
        if (!o.has("sources") || !o.get("sources").isJsonArray()) throw new IllegalArgumentException("cache has no sources list");
        List<LootBook.Source> out = new ArrayList<>();
        int i = 0;
        for (JsonElement e : o.getAsJsonArray("sources")) {
            try {
                JsonObject s = e.getAsJsonObject();
                out.add(new LootBook.Source(LootBook.Kind.valueOf(s.get("kind").getAsString()), s.get("from").getAsString(), s.get("item").getAsString(),
                        s.get("per").getAsDouble(), s.get("chance").getAsDouble(), s.get("player").getAsBoolean(), s.has("cost") ? s.get("cost").getAsString() : null));
            } catch (RuntimeException x) {
                throw new IllegalArgumentException("cache source " + i + " is broken (" + x + ")");
            }
            i++;
        }
        return new Read(out, o.has("tables") ? o.get("tables").getAsInt() : 0, o.has("bad") ? o.get("bad").getAsInt() : 0, null);
    }

    private static JsonObject source(LootBook.Source s) {
        JsonObject j = new JsonObject();
        j.addProperty("kind", s.kind().name());
        j.addProperty("from", s.from());
        j.addProperty("item", s.item());
        j.addProperty("per", s.per());
        j.addProperty("chance", s.chance());
        j.addProperty("player", s.player());
        if (s.cost() != null) j.addProperty("cost", s.cost());
        return j;
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

    // ---- the owner's override: entropybot/lootbook-override.json ----

    /**
     * The override file: item id -> a list of {@code {"kind": "block"|"mob"|"chest"|"trade", "from": "<id>", "per": <expected
     * count>}} ({@code "chance"} 0..1 optional, default 1; {@code "player"} optional, default false). An empty list removes the
     * item's sources. Any broken entry throws IllegalArgumentException naming it (the whole file is then not used, and said).
     */
    public static Map<String, List<LootBook.Source>> override(String text) {
        JsonObject o = object(text, OVERRIDE_FILE);
        Map<String, List<LootBook.Source>> out = new LinkedHashMap<>();
        for (var e : o.entrySet()) {
            String item = TechTree.id(e.getKey().trim().toLowerCase(Locale.ROOT));
            if (!e.getValue().isJsonArray()) throw new IllegalArgumentException(e.getKey() + ": not a list");
            List<LootBook.Source> l = new ArrayList<>();
            int i = 0;
            for (JsonElement se : e.getValue().getAsJsonArray()) {
                String where = e.getKey() + " entry " + i++;
                if (!se.isJsonObject()) throw new IllegalArgumentException(where + ": not an object");
                JsonObject s = se.getAsJsonObject();
                LootBook.Kind kind;
                try {
                    kind = LootBook.Kind.valueOf(s.get("kind").getAsString().trim().toUpperCase(Locale.ROOT));
                } catch (RuntimeException x) {
                    throw new IllegalArgumentException(where + ": kind must be block, mob, chest or trade");
                }
                if (!s.has("from") || !s.get("from").isJsonPrimitive() || s.get("from").getAsString().isBlank()) throw new IllegalArgumentException(where + ": no from");
                double per = num(s, "per", -1, where), chance = num(s, "chance", 1, where);
                if (per < 0) throw new IllegalArgumentException(where + ": per must be a number of 0 or more");
                if (chance < 0 || chance > 1) throw new IllegalArgumentException(where + ": chance must be 0..1");
                boolean player = s.has("player") && s.get("player").isJsonPrimitive() && s.get("player").getAsBoolean();
                String from = s.get("from").getAsString().trim().toLowerCase(Locale.ROOT);
                l.add(new LootBook.Source(kind, kind == LootBook.Kind.CHEST || kind == LootBook.Kind.TRADE ? from : TechTree.id(from), item, per, chance, player,
                        s.has("cost") && s.get("cost").isJsonPrimitive() ? s.get("cost").getAsString() : null));
            }
            out.put(item, l);
        }
        return out;
    }

    private static double num(JsonObject s, String k, double def, String where) {
        if (!s.has(k)) return def;
        JsonElement v = s.get(k);
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(where + ": " + k + " is not a number");
        return v.getAsDouble();
    }
}
