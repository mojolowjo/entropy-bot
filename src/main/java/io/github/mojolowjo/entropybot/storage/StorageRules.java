package io.github.mojolowjo.entropybot.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.gui.GuiCore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of the storage verbs that need no game (B7b part 2, ported from the KubeJS bridge with the same wording):
 * what "deposit" puts away and where, "where", place names, the scan's arguments. Plain Java: JUnit tests it.
 */
public final class StorageRules {
    private StorageRules() {}

    /** What the bot keeps when it puts things away (tools, armor, pickaxe materials...). */
    public static final Pattern KEEP = Pattern.compile("_(pickaxe|axe|shovel|hoe|sword|helmet|chestplate|leggings|boots|planks|log)$|(^|:)(bow|crossbow|shield|torch|stick|crafting_table|chest|copper_ingot|iron_ingot)$");
    /** Kept up to about a stack: cobblestone for stone pickaxes, coal for torches. */
    public static final Map<String, Integer> KEEP_COUNT = Map.of("minecraft:cobblestone", 64, "minecraft:coal", 16, "minecraft:charcoal", 16);
    /** Ore drops and other valuables. */
    public static final Pattern VALUABLE = Pattern.compile("(^|:)(raw_[a-z_]+|[a-z_]+_raw|coal|diamond|emerald|redstone|lapis_lazuli|quartz)$|^mysticalagriculture:[a-z_]*(essence|shard)$");
    /** Blocks that count as storage: craft trips only take from these, "open" only remembers these. */
    public static final Pattern STORAGE = Pattern.compile("chest|barrel|shulker|drawer|crate|backpack|storage");
    /** Refined Storage's blocks say "storage" but are no chest. */
    public static final Pattern NOT_STORAGE = Pattern.compile("refinedstorage|extrastorage");

    public static boolean isStorageId(String id) {
        return STORAGE.matcher(id).find() && !NOT_STORAGE.matcher(id).find();
    }

    /** One inventory slot: its item, how many, and whether it is food. */
    public record Held(String id, int n, boolean food) {}

    /**
     * What to put away: {id: keep} (keep = how many of it stay with the bot), in inventory order. text names items (or
     * "all", or nothing); keepValuables leaves VALUABLE items out; only (when not null) puts just those ids away.
     */
    public static Map<String, Integer> depositables(List<Held> inv, String text, boolean keepValuables, Pattern only) {
        Map<String, Integer> out = new LinkedHashMap<>(), counts = new LinkedHashMap<>();
        java.util.Set<String> want = new java.util.HashSet<>();
        for (String n : (text == null ? "" : text.trim().toLowerCase()).split("[\\s,]+")) {
            if (!n.isEmpty() && !n.equals("all")) want.add(GuiCore.normId(n));
        }
        boolean named = !want.isEmpty();
        for (Held h : inv) {
            if (h.id() == null) continue;
            counts.merge(h.id(), h.n(), Integer::sum);
            if (named ? !want.contains(h.id()) : (KEEP.matcher(h.id()).find() || h.food())) continue;
            if ((keepValuables && VALUABLE.matcher(h.id()).find()) || (only != null && !only.matcher(h.id()).find())) continue;
            out.put(h.id(), named ? 0 : KEEP_COUNT.getOrDefault(h.id(), 0));
        }
        // nothing to do for items it only has the "keep" amount of
        out.entrySet().removeIf(e -> counts.getOrDefault(e.getKey(), 0) <= e.getValue());
        return out;
    }

    /** A chest the deposit may use: its "x y z", position and last-seen items. */
    public record Chest(String key, int[] pos, Map<String, Integer> items) {}

    /** One stop of a deposit: the chest, what goes in ({id: keep}), where the rest goes when it is full (or null). */
    public record Stop(Chest chest, Map<String, Integer> items, int[] fallback) {}

    public record Plan(List<Stop> stops, String label, String err) {}

    static long distSq(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Where each item goes: the chest that already holds the most of it, else the junk chest (the most cobblestone and
     * dirt, the nearest on a tie). Stops nearest first; the junk chest takes what doesn't fit elsewhere.
     */
    public static Plan depositPlan(Map<String, Integer> items, List<Chest> chests, int[] me, String text) {
        List<String> labels = new ArrayList<>();
        for (String id : items.keySet()) labels.add(GuiCore.shortId(id));
        if (labels.isEmpty()) return new Plan(null, null, "error: nothing to deposit" + (text != null && !text.isEmpty() ? " matching " + text : ""));
        String label = "putting away " + String.join(", ", labels.subList(0, Math.min(5, labels.size()))) + (labels.size() > 5 ? " and " + (labels.size() - 5) + " more" : "");
        if (chests.isEmpty()) return new Plan(null, null, "error: I don't know any chests near the base - PM \"scan\" there first");
        Chest junk = null;
        int junkN = -1;
        for (Chest c : chests) {
            int n = c.items().getOrDefault("minecraft:cobblestone", 0) + c.items().getOrDefault("minecraft:dirt", 0);
            if (n > junkN || (n == junkN && distSq(c.pos(), me) < distSq(junk.pos(), me))) {
                junkN = n;
                junk = c;
            }
        }
        Map<String, Stop> plan = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            Chest best = junk;
            int bestN = 0;
            for (Chest c : chests) {
                int n = c.items().getOrDefault(e.getKey(), 0);
                if (n > bestN) {
                    bestN = n;
                    best = c;
                }
            }
            Chest b = best;
            Chest j = junk;
            plan.computeIfAbsent(b.key(), k -> new Stop(b, new LinkedHashMap<>(), b == j ? null : j.pos())).items().put(e.getKey(), e.getValue());
        }
        List<Stop> stops = new ArrayList<>(plan.values());
        stops.sort((a, b) -> Long.compare(distSq(a.chest().pos(), me), distSq(b.chest().pos(), me)));
        return new Plan(stops, label + " (" + stops.size() + " chest" + (stops.size() > 1 ? "s" : "") + ")", null);
    }

    /** A spot: "x y z" or a place name; err is what to whisper. */
    public record Spot(int x, int y, int z, String dim, String name, String err) {
        public int[] pos() { return new int[]{x, y, z}; }

        public String fmt() { return x + " " + y + " " + z; }

        public String label() { return name != null ? name : fmt(); }
    }

    private static final Pattern XYZ = Pattern.compile("^(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)$");

    /** "x y z" or a place name -> the spot, or {err} ("usage: open ..." texts; callers swap the verb). */
    public static Spot resolveSpot(String arg, Map<String, JsonObject> places, String dim) {
        String a = arg == null ? "" : arg.trim().toLowerCase();
        Matcher m = XYZ.matcher(a);
        if (m.find()) return new Spot(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), dim, null, null);
        if (!a.matches("^[a-z0-9_-]{1,24}$")) return err("usage: open x y z | open <place name>");
        JsonObject p = places.get(a);
        if (p == null) return err("error: I have no place called " + a + " (see \"places\")");
        String pd = p.has("dim") && !p.get("dim").isJsonNull() ? p.get("dim").getAsString() : null;
        if (pd != null && !pd.equals(dim)) return err("error: " + a + " is in " + pd);
        return new Spot(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt(), dim, a, null);
    }

    static Spot err(String e) { return new Spot(0, 0, 0, null, null, e); }

    /** "scan 12" (a radius), "scan -26 53 187 [12]", "scan base [12]" -> {where, radius 1..24}. */
    public static String[] scanArgs(String text) {
        List<String> p = new ArrayList<>();
        for (String w : (text == null ? "" : text.trim().toLowerCase()).split("\\s+")) if (!w.isEmpty()) p.add(w);
        String where;
        int radius = 16;
        if (p.size() >= 3 && p.get(0).matches("^-?\\d+$") && p.get(1).matches("^-?\\d+$") && p.get(2).matches("^-?\\d+$")) {
            where = String.join(" ", p.subList(0, 3));
            if (p.size() > 3) radius = parseOr(p.get(3), 16);
        } else {
            if (!p.isEmpty() && p.get(p.size() - 1).matches("^\\d+$")) radius = Integer.parseInt(p.remove(p.size() - 1));
            where = String.join(" ", p);
        }
        radius = Math.min(Math.max(radius, 1), 24);
        return new String[]{where, String.valueOf(radius)};
    }

    static int parseOr(String s, int d) {
        try {
            int v = Integer.parseInt(s.replaceAll("[^0-9-].*$", ""));
            return v == 0 ? d : v;
        } catch (NumberFormatException e) {
            return d;
        }
    }

    /** The name a "mark" gives the container at "x y z" (null if none). */
    public static String chestLabel(String key, Map<String, JsonObject> places, JsonObject chest) {
        String[] p = key.split(" ");
        String cd = chest != null && chest.has("dim") ? chest.get("dim").getAsString() : null;
        for (Map.Entry<String, JsonObject> e : places.entrySet()) {
            JsonObject pl = e.getValue();
            if (pl.get("x").getAsInt() == Integer.parseInt(p[0]) && pl.get("y").getAsInt() == Integer.parseInt(p[1]) && pl.get("z").getAsInt() == Integer.parseInt(p[2])
                    && (cd == null || !pl.has("dim") || pl.get("dim").isJsonNull() || pl.get("dim").getAsString().equals(cd))) return e.getKey();
        }
        return null;
    }

    static Map<String, Integer> items(JsonObject note) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (note != null && note.has("items") && note.get("items").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : note.getAsJsonObject("items").entrySet()) out.put(e.getKey(), e.getValue().getAsInt());
        }
        return out;
    }

    static long seen(JsonObject note) {
        try { return note.has("seen") ? note.get("seen").getAsLong() : 0; } catch (RuntimeException e) { return 0; }
    }

    /** "where <item>": what the bot carries, what the RS network held at its last reading, then the chests (6 at most). */
    public static String where(String text, Map<String, Integer> carried, Map<String, JsonObject> rs, Map<String, JsonObject> chests,
                               Map<String, JsonObject> places, long now) {
        String q = (text == null ? "" : text).toLowerCase().replaceFirst("^minecraft:", "");
        if (q.isEmpty()) return "usage: where <item>";
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : carried.entrySet()) {
            if (GuiCore.shortId(e.getKey()).contains(q)) list.add("I carry " + e.getValue() + " " + GuiCore.shortId(e.getKey()));
        }
        for (Map.Entry<String, JsonObject> r : rs.entrySet()) {
            for (Map.Entry<String, Integer> e : items(r.getValue()).entrySet()) {
                if (GuiCore.shortId(e.getKey()).contains(q)) {
                    list.add(e.getValue() + " " + GuiCore.shortId(e.getKey()) + " in the RS network at " + r.getKey() + " (" + io.github.mojolowjo.entropybot.commands.Texts.ago(seen(r.getValue()), now) + ")");
                }
            }
        }
        for (Map.Entry<String, JsonObject> c : chests.entrySet()) {
            String name = chestLabel(c.getKey(), places, c.getValue());
            for (Map.Entry<String, Integer> e : items(c.getValue()).entrySet()) {
                if (GuiCore.shortId(e.getKey()).contains(q)) {
                    list.add(e.getValue() + " " + GuiCore.shortId(e.getKey()) + " in chest " + c.getKey() + " (" + (name != null ? name + ", " : "")
                            + io.github.mojolowjo.entropybot.commands.Texts.ago(seen(c.getValue()), now) + ")");
                }
            }
        }
        return list.isEmpty() ? "no " + q + " in my inventory or any chest I've looked in" : String.join(" | ", list.subList(0, Math.min(6, list.size())));
    }

    /** "the RS network at x y z holds N items of K kinds: ...". */
    public static String rsSummary(String key, Map<String, Integer> items) {
        int total = 0;
        for (int v : items.values()) total += v;
        return "the RS network at " + key + " holds " + total + " items of " + items.size() + " kinds" + (items.isEmpty() ? "" : ": " + GuiCore.top(items, 5));
    }
}
