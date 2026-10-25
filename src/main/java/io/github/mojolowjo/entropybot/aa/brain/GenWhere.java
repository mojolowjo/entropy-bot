package io.github.mojolowjo.entropybot.aa.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 0.39.z68 (owner 2026-10-25: "Depending on the explore goal, we should have some logic: if it's wood - go to surface to
 * explore, if it's ore, explore for caves"): WHERE an explore's target blocks generate, from the world-gen data of the
 * client's jars (rule 19), never from a name list (rule 4). A {@link Placer} is one placed feature whose placement says
 * where (its own {@code placement} list) and the blocks its feature tree places (every block state {@code Name} of its
 * configured feature, following the placed / configured features it names). The placement's kind:
 * <ul><li>a {@code heightmap} modifier: SURFACE (trees, flowers, sugar cane, pumpkins: put on the ground's top);</li>
 * <li>else a {@code height_range} modifier: a BAND y1..y2 under ground (ores, geodes, underground lakes);</li>
 * <li>else none (a nested feature placed by its parent, a nether {@code count_on_every_layer}): it says nothing.</li></ul>
 * A target's {@link Fact}: SURFACE when its blocks are only heightmap-placed, BAND (the union) when only height-range-placed,
 * UNKNOWN when no feature places them or they are placed both ways (today's explore), NOT_LOADED while the read is not done
 * (rule 15: never "none"). The biome share and the dimension are not modelled (rule 14: rough): every biome's features
 * count. The planner prices what it does with the fact (rule 1): the walk up to the surface is a priced walk (rule 18).
 * Pure (JUnit: Z68ExploreWhereTest). Loader notes: the vanilla data format (placed_feature / configured_feature JSON, the
 * PlacementModifier and HeightProvider codecs' JSON); NeoForge / Fabric alike.
 */
public final class GenWhere {
    private GenWhere() {}

    public enum Kind { SURFACE, BAND, UNKNOWN, NOT_LOADED }

    /** One placed feature with a placement that says where: its id, the blocks its feature tree places, its placement list. */
    public record Placer(String id, List<String> blocks, JsonArray placement) {
        public Placer { blocks = List.copyOf(blocks); }
    }

    /**
     * Where a target generates. {@code block} / {@code feature}: the block and placed feature that say it (null for UNKNOWN /
     * NOT_LOADED); {@code lo..hi}: the band (BAND only); {@code why}: the reason for UNKNOWN / NOT_LOADED.
     */
    public record Fact(Kind kind, int lo, int hi, String block, String feature, String why) {
        public boolean surface() { return kind == Kind.SURFACE; }
        public boolean band() { return kind == Kind.BAND; }

        /** The fact as the explore's step text says it ("oak_log grows on the surface: trees_plains is heightmap-placed"). */
        public String text() {
            return switch (kind) {
                case SURFACE -> sh(block) + " grows on the surface: " + sh(feature) + " is heightmap-placed";
                case BAND -> sh(block) + " generates under ground at Y " + lo + ".." + hi + ": " + sh(feature) + " is height-range-placed";
                case UNKNOWN -> "where it generates is not known: " + why;
                case NOT_LOADED -> "where it generates is not loaded yet: " + why;
            };
        }
    }

    public static Fact notLoaded(String why) { return new Fact(Kind.NOT_LOADED, 0, 0, null, null, why); }

    public static Fact unknown(String why) { return new Fact(Kind.UNKNOWN, 0, 0, null, null, why); }

    /** A placement list's kind: SURFACE (a heightmap modifier), BAND {lo, hi} (a height_range it reads), null: says nothing. */
    static int[] band(JsonArray placement, int bottom, int top) {
        if (placement == null) return null;
        for (JsonElement e : placement) {
            if (!e.isJsonObject() || !"height_range".equals(type(e.getAsJsonObject()))) continue;
            JsonElement h = e.getAsJsonObject().get("height");
            if (h == null || !h.isJsonObject()) return null;
            JsonObject ho = h.getAsJsonObject();
            if (ho.has("min_inclusive") && ho.has("max_inclusive")) {        // uniform, trapezoid, the biased kinds
                int a = anchor(ho.get("min_inclusive"), bottom, top), b = anchor(ho.get("max_inclusive"), bottom, top);
                if (a == Integer.MIN_VALUE || b == Integer.MIN_VALUE) return null;
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
            int a = anchor(ho, bottom, top);                                  // a constant anchor
            return a == Integer.MIN_VALUE ? null : new int[]{a, a};
        }
        return null;
    }

    /** True when the placement puts the feature on a heightmap's top (the ground's surface, the ocean floor). */
    static boolean heightmap(JsonArray placement) {
        if (placement == null) return false;
        for (JsonElement e : placement)
            if (e.isJsonObject() && "heightmap".equals(type(e.getAsJsonObject()))) return true;
        return false;
    }

    /**
     * The fact for the blocks that give a target ({@code blocks}: the loot data's blocks and the item's own block), from the
     * placers ({@code placers} null: not loaded). {@code bottom}/{@code top}: the dimension's build height for the anchors.
     */
    public static Fact of(Collection<String> blocks, Collection<Placer> placers, int bottom, int top) {
        if (placers == null) return notLoaded("the world-gen read is not done");
        if (blocks == null || blocks.isEmpty()) return unknown("no block gives it");
        Set<String> want = new TreeSet<>(blocks);
        String sBlock = null, sFeat = null, bBlock = null, bFeat = null;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        List<Placer> sorted = new ArrayList<>(placers);
        sorted.sort(java.util.Comparator.comparing(Placer::id));
        for (Placer p : sorted) {
            String hit = null;
            for (String b : p.blocks()) if (want.contains(b) && (hit == null || b.compareTo(hit) < 0)) hit = b;
            if (hit == null) continue;
            if (heightmap(p.placement())) {
                if (sBlock == null) { sBlock = hit; sFeat = p.id(); }
                continue;
            }
            int[] r = band(p.placement(), bottom, top);
            if (r == null) continue;
            if (bBlock == null) { bBlock = hit; bFeat = p.id(); }
            lo = Math.min(lo, r[0]);
            hi = Math.max(hi, r[1]);
        }
        if (sBlock != null && bBlock != null)
            return unknown(sh(sBlock) + " is heightmap-placed (" + sh(sFeat) + ") and " + sh(bBlock) + " height-range-placed (" + sh(bFeat) + "): both ways");
        if (sBlock != null) return new Fact(Kind.SURFACE, 0, 0, sBlock, sFeat, null);
        if (bBlock != null) return new Fact(Kind.BAND, lo, hi, bBlock, bFeat, null);
        List<String> names = new ArrayList<>();
        for (String b : want) { if (names.size() == 3) { names.add("..."); break; } names.add(sh(b)); }   // structural: display: how many items a text lists
        return unknown("no world-gen feature places " + String.join(", ", names));
    }

    private static String type(JsonObject o) {
        String t = o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : "";
        int c = t.indexOf(':');
        return c >= 0 ? t.substring(c + 1) : t;
    }

    /** {absolute: v} / {above_bottom: v} / {below_top: v} -> Y; MIN_VALUE: not an anchor. */
    private static int anchor(JsonElement e, int bottom, int top) {
        if (e == null || !e.isJsonObject()) return Integer.MIN_VALUE;
        for (String k : List.of("absolute", "above_bottom", "below_top"))
            if (e.getAsJsonObject().has(k) && e.getAsJsonObject().get(k).isJsonPrimitive()) return Thing.anchorY(k, e.getAsJsonObject().get(k).getAsInt(), bottom, top);
        return Integer.MIN_VALUE;
    }

    private static String sh(String id) { return id == null ? "?" : id.replaceFirst("^minecraft:", ""); }
}
