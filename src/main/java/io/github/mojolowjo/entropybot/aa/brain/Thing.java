package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.strip.OreSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * BRAIN_PLAN 2.5 (B4): the resolver behind {@code aa want <thing>}: which ids a word means (registry id, ore family,
 * display name), what pickaxe it takes (block tags) and where it is (singleplayer worldgen, else the vanilla table, else
 * a guess from the name). Pure: the adapter hands in a {@link Catalog} read from the registries.
 *
 * <p>Order (2.5 step 1): an ore id ({@code iron_ore}) takes its whole family (Q5: say so); a family word or a raw drop
 * ({@code iron}, {@code raw_osmium}, {@code osmiums}) the family's ores; then a plain item/block id; then a display name.
 * Pickaxe tiers as {@code Inputs.pickTier}: 0 hand, 1 wood, 2 stone, 3 iron, 4 diamond.
 */
public final class Thing {
    private Thing() {}

    public static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether", END = "minecraft:the_end";
    public static final int UNKNOWN_Y = Integer.MIN_VALUE;

    /** Where an ore generates (from the worldgen features): dimensions and the Y range over all its placements. */
    public record Gen(Set<String> dims, int minY, int maxY) {}

    /** The registries as the resolver needs them (ids "ns:path"; names id -> lower-case display name; tiers 0..4). */
    public record Catalog(Collection<String> items, Collection<String> blocks, Map<String, String> names,
                          Map<String, Integer> tiers, Map<String, Gen> gen) {}

    /** Where to look: a dimension, a Y range (UNKNOWN_Y when unknown), and the source of that answer. */
    public record Where(String dim, int minY, int maxY, String source) {
        public boolean yKnown() { return minY != UNKNOWN_Y; }

        public String text() {
            return dimName(dim) + (yKnown() ? " Y " + minY + ".." + maxY : ", Y unknown") + " (" + source + ")";
        }
    }

    /**
     * What a word means. {@code ore}: the family's ore blocks ({@code ids}), counted with their drops ({@code counts}).
     * Otherwise one item/block id. {@code err} set = nothing found (the rest null).
     */
    public record Result(String word, String key, boolean ore, List<String> ids, List<String> counts, int tier,
                         Where where, String note, String err) {
        public String text() {
            if (err != null) return err;
            List<String> s = new ArrayList<>();
            for (String id : ids) s.add(shortId(id));
            return key + " (" + String.join(", ", s) + "): " + tierName(tier) + (where == null ? "" : "; " + where.text())
                    + (note == null ? "" : "; " + note);
        }
    }

    public static Result resolve(String word, Catalog c) {
        String w = norm(word);
        if (w.isEmpty()) return err(word, "want what? e.g. aa want iron_ore 8");
        List<String> ores = new ArrayList<>();
        for (String b : c.blocks()) if (OreSpec.oreId(b)) ores.add(b);
        String id = w.contains(":") ? w : "minecraft:" + w;
        // 1. an ore id: its whole family (Q5)
        if (ores.contains(id)) return ore(word, OreSpec.family(id), familyIds(ores, OreSpec.family(id)), c, null);
        // 2. a family word, its plural or a raw drop ("iron", "diamonds", "raw_osmium", "mekanism:osmium")
        Result f = family(word, w, ores, c);
        if (f != null) return f;
        // 3. a plain item or block id
        if (c.items().contains(id) || c.blocks().contains(id)) return item(word, id, c, null);
        // 4. a display name ("Raw Osmium", "osmium ore", "oak log")
        String spaced = w.replace('_', ' ');
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : c.names().entrySet()) if (e.getValue().equals(spaced)) hits.add(e.getKey());
        if (!hits.isEmpty()) {
            hits.sort(null);
            for (String h : hits) if (ores.contains(h)) return ore(word, OreSpec.family(h), familyIds(ores, OreSpec.family(h)), c, "from the name \"" + spaced + "\"");
            String note = hits.size() > 1 ? "\"" + spaced + "\" is " + String.join(" or ", hits) + "; I take " + hits.get(0) : null;
            return item(word, hits.get(0), c, note);
        }
        return err(word, "I don't know a thing called " + w + " (no item, block, ore family or name matches)");
    }

    private static Result family(String word, String w, List<String> ores, Catalog c) {
        String base = w.replaceFirst("^raw_", "").replaceFirst(":raw_", ":");
        for (String cand : new String[]{base, base.endsWith("s") ? base.substring(0, base.length() - 1) : null}) {
            if (cand == null || cand.isEmpty()) continue;
            List<String> ids = new ArrayList<>();
            if (cand.contains(":")) {                      // "mekanism:osmium": that namespace's family only
                for (String o : ores) if (OreSpec.family(o).equals(cand.replaceFirst("^minecraft:", ""))) ids.add(o);
            } else {
                Pattern re = Pattern.compile("(^|[:_])" + Pattern.quote(cand) + "_ore$");
                for (String o : ores) if (re.matcher(o).find()) ids.add(o);
                if (ids.isEmpty() && (cand.equals("ancient_debris") || cand.equals("netherite_scrap")))
                    for (String o : ores) if (o.endsWith(":ancient_debris")) ids.add(o);
            }
            if (ids.isEmpty()) continue;
            ids.sort(null);
            Set<String> fams = new TreeSet<>();
            for (String o : ids) fams.add(OreSpec.family(o));
            String note = fams.size() > 1 ? "\"" + cand + "\" is " + String.join(" and ", fams) + "; I take them all" : null;
            return ore(word, fams.size() == 1 ? fams.iterator().next() : cand.replaceFirst("^.*:", ""), ids, c, note);
        }
        return null;
    }

    private static List<String> familyIds(List<String> ores, String fam) {
        List<String> ids = new ArrayList<>();
        for (String o : ores) if (OreSpec.family(o).equals(fam)) ids.add(o);
        ids.sort(null);
        return ids;
    }

    private static Result ore(String word, String fam, List<String> ids, Catalog c, String note) {
        int tier = 0;
        for (String id : ids) tier = Math.max(tier, c.tiers().getOrDefault(id, 1));
        if (ids.size() > 1 && note == null) {
            List<String> s = new ArrayList<>();
            for (String id : ids) s.add(shortId(id));
            note = String.join(" or ", s) + "? I take " + (ids.size() == 2 ? "both" : "all " + ids.size());
        }
        return new Result(word, fam, true, ids, drops(fam, ids), tier, where(fam, ids, c), note, null);
    }

    private static Result item(String word, String id, Catalog c, String note) {
        int tier = c.tiers().getOrDefault(id, 0);
        return new Result(word, shortId(id), false, List.of(id), List.of(path(id)), tier, null, note, null);
    }

    private static Result err(String word, String why) {
        return new Result(word, null, false, List.of(), List.of(), 0, null, null, why);
    }

    /** The bag names (paths) that count for an ore family: the ore blocks, raw_x, x and the vanilla drops. */
    static List<String> drops(String fam, List<String> ids) {
        Set<String> s = new LinkedHashSet<>();
        for (String id : ids) s.add(path(id));
        String w = fam.replaceFirst("^.*:", "");
        switch (w) {
            case "lapis" -> s.add("lapis_lazuli");
            case "nether_quartz" -> s.add("quartz");
            case "nether_gold" -> s.add("gold_nugget");
            case "ancient_debris" -> {}
            default -> {
                s.add("raw_" + w);
                s.add(w);
            }
        }
        return new ArrayList<>(s);
    }

    // ---- where (2.5 step 3) ----

    /** Worldgen first (singleplayer), else the vanilla table, else a guess from the ids. */
    static Where where(String fam, List<String> ids, Catalog c) {
        Set<String> dims = new TreeSet<>();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (String id : ids) {
            Gen g = c.gen() == null ? null : c.gen().get(id);
            if (g == null) continue;
            dims.addAll(g.dims());
            if (g.minY() != UNKNOWN_Y) {
                lo = Math.min(lo, g.minY());
                hi = Math.max(hi, g.maxY());
            }
        }
        if (!dims.isEmpty()) {
            String d = dims.contains(OVERWORLD) ? OVERWORLD : dims.iterator().next();
            return new Where(d, lo == Integer.MAX_VALUE ? UNKNOWN_Y : lo, lo == Integer.MAX_VALUE ? UNKNOWN_Y : hi, "from worldgen");
        }
        Where v = vanilla(fam);
        return v != null ? v : guess(ids);
    }

    /** Vanilla 1.21 ore heights (the placed features' ranges; ores outside them are rare). */
    static Where vanilla(String fam) {
        int[] r = switch (fam) {
            case "coal" -> new int[]{0, 320};
            case "iron" -> new int[]{-64, 320};
            case "copper" -> new int[]{-16, 112};
            case "gold" -> new int[]{-64, 32};
            case "redstone" -> new int[]{-64, 15};
            case "lapis" -> new int[]{-64, 64};
            case "diamond" -> new int[]{-64, 16};
            case "emerald" -> new int[]{-16, 320};
            case "nether_gold", "nether_quartz" -> new int[]{10, 117};
            case "ancient_debris" -> new int[]{8, 119};
            default -> null;
        };
        if (r == null) return null;
        String d = fam.startsWith("nether") || fam.equals("ancient_debris") ? NETHER : OVERWORLD;
        return new Where(d, r[0], r[1], "the vanilla table");
    }

    /** An unknown (modded) ore with no worldgen data: nether/end from the name, deepslate-only = deep, else unknown Y. */
    static Where guess(List<String> ids) {
        boolean nether = false, end = false, allDeep = !ids.isEmpty();
        for (String id : ids) {
            String p = path(id), ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "";
            if (p.contains("nether")) nether = true;
            if (p.startsWith("end_") || p.contains("_end_") || p.startsWith("endstone") || p.contains("end_stone") || ns.equals("end")) end = true;
            if (!p.startsWith("deepslate_")) allDeep = false;
        }
        if (nether) return new Where(NETHER, 10, 117, "a guess from the name; exploring tells");
        if (end) return new Where(END, UNKNOWN_Y, UNKNOWN_Y, "a guess from the name");
        if (allDeep) return new Where(OVERWORLD, -64, 0, "a guess: deepslate only");
        return new Where(OVERWORLD, UNKNOWN_Y, UNKNOWN_Y, "unknown, exploring");
    }

    /** A vertical anchor (worldgen JSON) to a Y: "absolute", "above_bottom" or "below_top". */
    public static int anchorY(String kind, int v, int bottom, int top) {
        return switch (kind) {
            case "above_bottom" -> bottom + v;
            case "below_top" -> top - 1 - v;
            default -> v;
        };
    }

    /** Pickaxe tier from the block tags: needs_diamond 4, needs_iron 3, needs_stone 2, mineable/pickaxe 1, else 0. */
    public static int tier(boolean pickaxe, boolean stone, boolean iron, boolean diamond) {
        return diamond ? 4 : iron ? 3 : stone ? 2 : pickaxe ? 1 : 0;
    }

    public static String tierName(int t) {
        return switch (t) {
            case 0 -> "any tool";
            case 1 -> "a wooden pickaxe";
            case 2 -> "a stone pickaxe";
            case 3 -> "an iron pickaxe";
            default -> "a diamond pickaxe";
        };
    }

    public static String dimName(String d) {
        return d == null ? "?" : d.equals(OVERWORLD) ? "overworld" : d.equals(NETHER) ? "the Nether" : d.equals(END) ? "the End" : d;
    }

    static String norm(String w) {
        return w == null ? "" : w.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_").replaceFirst("^minecraft:", "");
    }

    static String path(String id) { return id.contains(":") ? id.substring(id.indexOf(':') + 1) : id; }

    static String shortId(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }
}
