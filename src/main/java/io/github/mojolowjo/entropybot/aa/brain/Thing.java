package io.github.mojolowjo.entropybot.aa.brain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * BRAIN_PLAN 2.5 (B4), one-planner: the resolver behind {@code aa want <thing>}: which item a word means and where the
 * blocks that give it generate. An item or block id, a tool group ({@link ToolGroup}), else a display name. A block
 * whose loot gives other items (an ore) means what it drops ("iron_ore" -> raw_iron: an ore is any block whose loot drops
 * the item; no family words). The pickaxe tier and the height band come from the blocks that drop the item (the loot data,
 * the block tags, the worldgen read at load). Pure: the adapter hands in a {@link Catalog} read from the registries.
 * Pickaxe tiers as {@code Inputs.pickTier}: 0 hand, 1 wood, 2 stone, 3 iron, 4 diamond.
 */
public final class Thing {
    private Thing() {}

    public static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether", END = "minecraft:the_end";
    public static final int UNKNOWN_Y = Integer.MIN_VALUE; // structural

    /** Where a block generates (from the worldgen features): dimensions and the Y range over all its placements. */
    public record Gen(Set<String> dims, int minY, int maxY) {}

    /**
     * The registries as the resolver needs them (ids "ns:path"; names id -> lower-case display name; tiers 0..4; gen block ->
     * where it generates); {@code dropsOf}: block -> the items its loot gives; {@code droppedBy}: item -> the blocks whose loot
     * gives it (both the loot data; null: none read).
     */
    public record Catalog(Collection<String> items, Collection<String> blocks, Map<String, String> names,
                          Map<String, Integer> tiers, Map<String, Gen> gen, Function<String, List<String>> dropsOf,
                          Function<String, List<String>> droppedBy) {
        public Catalog(Collection<String> items, Collection<String> blocks, Map<String, String> names,
                       Map<String, Integer> tiers, Map<String, Gen> gen) {
            this(items, blocks, names, tiers, gen, null, null);
        }
    }

    /** Where to look: a dimension, a Y range (UNKNOWN_Y when unknown), and the source of that answer. */
    public record Where(String dim, int minY, int maxY, String source) {
        public boolean yKnown() { return minY != UNKNOWN_Y; }

        public String text() {
            return dimName(dim) + (yKnown() ? " Y " + minY + ".." + maxY : ", Y unknown") + " (" + source + ")";
        }
    }

    /**
     * What a word means: one item ({@code ids}), counted by its bag name ({@code counts}), or a group. {@code ore}: blocks
     * that generate in the world give it (its {@code where} is their band). {@code err} set = nothing found (the rest null).
     */
    public record Result(String word, String key, boolean ore, List<String> ids, List<String> counts, int tier,
                         Where where, String note, String err, boolean group) {
        public Result(String word, String key, boolean ore, List<String> ids, List<String> counts, int tier, Where where, String note, String err) {
            this(word, key, ore, ids, counts, tier, where, note, err, false);
        }

        public String text() {
            if (err != null) return err;
            if (group) return key + ": " + note;
            List<String> s = new ArrayList<>();
            for (String id : ids) s.add(shortId(id));
            return key + " (" + String.join(", ", s) + "): " + tierName(tier) + (where == null ? "" : "; " + where.text())
                    + (note == null ? "" : "; " + note);
        }
    }

    public static Result resolve(String word, Catalog c) {
        String w = norm(word);
        if (w.isEmpty()) return err(word, "want what? e.g. aa want diamond 3");
        List<String> grouped = ToolGroup.items(w);          // ruling (b): "stone tools" is every tool of that material, one job
        if (!grouped.isEmpty()) return group(word, w, grouped);
        String id = w.contains(":") ? w : "minecraft:" + w;
        if (c.items().contains(id) || c.blocks().contains(id)) return thing(word, id, c, null);
        // a display name ("Raw Iron", "oak log")
        String spaced = w.replace('_', ' ');
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : c.names().entrySet()) if (e.getValue().equals(spaced)) hits.add(e.getKey());
        if (!hits.isEmpty()) {
            hits.sort(null);
            String note = hits.size() > 1 ? "\"" + spaced + "\" is " + String.join(" or ", hits) + "; I take " + hits.get(0) : "from the name \"" + spaced + "\"";
            return thing(word, hits.get(0), c, note);
        }
        return err(word, "I don't know a thing called " + w + " (no item, block or name matches)");
    }

    /** An id: a block whose loot gives other items means its first drop (the loot data); else the item itself. */
    private static Result thing(String word, String id, Catalog c, String note) {
        List<String> drops = c.dropsOf() == null ? List.of() : c.dropsOf().apply(id);
        if (c.blocks().contains(id) && drops != null && !drops.isEmpty() && !drops.contains(id)) {
            String d = drops.get(0);
            String n = shortId(id) + " drops " + shortId(d) + " (the game's loot data): I want " + shortId(d);
            return item(word, d, c, note == null ? n : note + "; " + n);
        }
        return item(word, id, c, note);
    }

    private static Result item(String word, String id, Catalog c, String note) {
        List<String> from = c.droppedBy() == null ? List.of() : c.droppedBy().apply(id);
        if (from == null) from = List.of();
        int tier = c.tiers().getOrDefault(id, 0);
        List<String> gens = new ArrayList<>();
        for (String b : from) {
            if (c.gen() != null && c.gen().containsKey(b)) gens.add(b);
        }
        if (!gens.isEmpty()) {                               // the lowest tier among the generated blocks that drop it
            tier = Integer.MAX_VALUE;
            for (String b : gens) tier = Math.min(tier, c.tiers().getOrDefault(b, 0));
        }
        Where where = where(gens, c);
        return new Result(word, shortId(id), where != null, List.of(id), List.of(path(id)), tier, where, note, null);
    }

    private static Result group(String word, String key, List<String> ids) {
        List<String> counts = new ArrayList<>();
        for (String id : ids) counts.add(path(id));
        List<String> s = new ArrayList<>();
        for (String id : ids) s.add(shortId(id));
        return new Result(word, key, false, ids, counts, 0, null, ids.size() + " items, one job: " + String.join(", ", s), null, true);
    }

    private static Result err(String word, String why) {
        return new Result(word, null, false, List.of(), List.of(), 0, null, null, why);
    }

    // ---- where (2.5 step 3) ----

    /** The worldgen band of the blocks (singleplayer: read at load); null when none of them generates (or no worldgen read). */
    static Where where(List<String> blocks, Catalog c) {
        Set<String> dims = new TreeSet<>();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (String id : blocks) {
            Gen g = c.gen() == null ? null : c.gen().get(id);
            if (g == null) continue;
            dims.addAll(g.dims());
            if (g.minY() != UNKNOWN_Y) {
                lo = Math.min(lo, g.minY());
                hi = Math.max(hi, g.maxY());
            }
        }
        if (dims.isEmpty()) return null;
        String d = dims.contains(OVERWORLD) ? OVERWORLD : dims.iterator().next();
        return new Where(d, lo == Integer.MAX_VALUE ? UNKNOWN_Y : lo, lo == Integer.MAX_VALUE ? UNKNOWN_Y : hi, "from worldgen");
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
