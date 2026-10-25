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
 * <ul><li>a {@code heightmap} modifier on the land surface: SURFACE (trees, flowers, sugar cane, pumpkins: put on the ground's top;
 * review 4: OCEAN_FLOOR only with the no-water filter, else the sea floor: kelp, seagrass are not the surface);</li>
 * <li>else a {@code height_range} modifier: a BAND y1..y2 under ground (ores, geodes, underground lakes);</li>
 * <li>else none (a nested feature placed by its parent, a nether {@code count_on_every_layer}): it says nothing.</li></ul>
 * A target's {@link Fact}: SURFACE when its blocks are only heightmap-placed, BAND (the union, each placer's own band kept: review 2) when only height-range-placed,
 * UNKNOWN when no feature places them or they are placed both ways (today's explore), NOT_LOADED while the read is not done
 * (rule 15: never "none"). The biome share and the dimension are not modelled (rule 14: rough): every biome's features
 * count. The planner prices what it does with the fact (rule 1): the walk up to the surface is a priced walk (rule 18).
 * 0.39.z69 item 2 (owner: "we can't just accept a range like -64..384"): each band carries its placer's density curve
 * ({@link OreBand.Feature}: the height provider's shape, count, vein size, air-exposure discard); the fact says its peaks
 * ({@link Fact#peakText}) and gives the density at a Y ({@link Fact#curve}) - the explore ranks caves by it.
 * Pure (JUnit: Z68ExploreWhereTest). Loader notes: the vanilla data format (placed_feature / configured_feature JSON, the
 * PlacementModifier and HeightProvider codecs' JSON); NeoForge / Fabric alike.
 */
public final class GenWhere {
    private GenWhere() {}

    public enum Kind { SURFACE, BAND, UNKNOWN, NOT_LOADED }

    /**
     * One placed feature with a placement that says where: its id, the blocks its feature tree places, its placement list;
     * 0.39.z69 item 2: an ore-shaped configured feature's vein {@code size} and {@code discard_chance_on_air_exposure}
     * (0 / 0: not an ore shape - the curve then counts its placements, one a vein).
     */
    public record Placer(String id, List<String> blocks, JsonArray placement, int size, double discard) {
        public Placer { blocks = List.copyOf(blocks); }

        public Placer(String id, List<String> blocks, JsonArray placement) { this(id, blocks, placement, 0, 0); }
    }

    /**
     * 0.39.z68 review 2: one placer's own band: Y lo..hi, its veins per chunk (the {@code count} modifier's mean, a
     * {@code rarity_filter}'s 1/chance; none or not read: 1) and its placed feature. 0.39.z69 item 2: {@code curve}: its
     * density by height ({@link OreBand.Feature}: the height provider's shape, the vein size, the air-exposure discard);
     * null: the placement's count is not read (the flat veins per layer then).
     */
    public record Band(int lo, int hi, double veins, String feature, OreBand.Feature curve) {
        public Band(int lo, int hi, double veins, String feature) { this(lo, hi, veins, feature, null); }

        /** The density at the placer's peak (its curve's), else veins per layer over its height (rough, rule 14). */
        public double density() { return curve != null ? curve.perY(curve.peak()) : veins / Math.max(1, hi - lo + 1); }

        /** 0.39.z69 item 2: expected blocks per chunk on layer y; {@code cave}: what a cave wall shows (the discard taken off). */
        public double at(int y, boolean cave) {
            if (curve != null) return cave ? curve.exposedPerY(y) : curve.perY(y);
            return y < lo || y > hi ? 0 : veins / Math.max(1, hi - lo + 1);
        }

        /** Blocks from y to the band (0 inside it). */
        public int dist(int y) { return y < lo ? lo - y : y > hi ? y - hi : 0; }
    }

    /**
     * Where a target generates. {@code block} / {@code feature}: the block and placed feature that say it (null for UNKNOWN /
     * NOT_LOADED); {@code lo..hi}: the band, the union of {@code bands} (BAND only); {@code why}: the reason for UNKNOWN /
     * NOT_LOADED. 0.39.z69 fix 3: {@code feet}: the bot's feet Y when the fact was asked (null: not given) - the peaks are
     * said nearest the feet first.
     */
    public record Fact(Kind kind, int lo, int hi, String block, String feature, String why, List<Band> bands, Integer feet) {
        public Fact { bands = bands == null ? List.of() : List.copyOf(bands); }

        public Fact(Kind kind, int lo, int hi, String block, String feature, String why, List<Band> bands) { this(kind, lo, hi, block, feature, why, bands, null); }

        public Fact(Kind kind, int lo, int hi, String block, String feature, String why) { this(kind, lo, hi, block, feature, why, List.of()); }

        /** 0.39.z69 fix 3: the same fact seen from feet Y {@code y} (the peaks then said nearest first). */
        public Fact withFeet(int y) { return new Fact(kind, lo, hi, block, feature, why, bands, y); }

        public boolean surface() { return kind == Kind.SURFACE; }
        public boolean band() { return kind == Kind.BAND; }

        /**
         * 0.39.z68 review 2 (iron's union -64..384 is the whole world): the band to look in from feet Y {@code y}: of the
         * placers' own bands the nearest to y (0: y inside it), ties to the densest (veins per layer). Null: no band.
         */
        public Band preferred(int y) {
            Band best = null;
            for (Band b : bands)
                if (best == null || b.dist(y) < best.dist(y) || (b.dist(y) == best.dist(y) && b.density() > best.density())) best = b;
            return best;
        }

        /**
         * 0.39.z69 item 2 (owner 2026-10-25: "does the data tell where the ore is denser?"): the target's density at y -
         * expected blocks per chunk on that layer, every placer's curve summed (OreBand, the one curve); {@code cave}: what a
         * cave wall shows (each placer's air-exposure discard taken off). 0 outside every band, and for a fact with no bands.
         */
        public double curve(int y, boolean cave) {
            double sum = 0;
            for (Band b : bands) sum += b.at(y, cave);
            return sum;
        }

        /** 0.39.z69 item 2: the placers' curves (the bands whose count was read). */
        public List<OreBand.Feature> curves() {
            List<OreBand.Feature> out = new ArrayList<>();
            for (Band b : bands) if (b.curve() != null) out.add(b.curve());
            return out;
        }

        /** 0.39.z69 item 2: one peak of the curve: its Y, the density there, and the placer densest at it. */
        public record Peak(int y, double density, Band by) {}

        /** 0.39.z69 item 2: the curve's peaks ({@link OreBand#peaks}), densest first; {@code cave}: as a cave wall shows them. */
        public List<Peak> peaks(boolean cave) {
            List<Peak> out = new ArrayList<>();
            for (int y : OreBand.peaks(curves(), cave)) {
                Band by = null;
                for (Band b : bands) if (b.curve() != null && (by == null || b.at(y, cave) > by.at(y, cave))) by = b;
                out.add(new Peak(y, curve(y, cave), by));
            }
            out.sort(java.util.Comparator.comparingDouble(Peak::density).reversed().thenComparingInt(Peak::y));
            return out;
        }

        /**
         * 0.39.z69 item 2: "densest at y 16 (ore_iron_middle trapezoid -24..56, 2.5 a chunk layer) and y 232 (...)"; "" none.
         * Fix 3 (Opus Design): with the feet known the peaks are said nearest the feet first, else densest first. The
         * biomes are not modelled (rule 14): ore_iron_upper is placed by the mountain biomes only, yet its 232 peak counts
         * as everywhere - the bot's own dimension's biomes choose the placers, not the biome at a spot.
         */
        public String peakText() {
            List<String> parts = new ArrayList<>();
            List<Peak> ps = new ArrayList<>(peaks(false));
            if (feet != null) ps.sort(java.util.Comparator.comparingInt((Peak p) -> Math.abs(p.y() - feet)).thenComparing(java.util.Comparator.comparingDouble(Peak::density).reversed()));
            for (Peak p : ps)
                parts.add("y " + p.y() + " (" + (p.by() == null ? "?" : sh(p.by().feature()) + " " + p.by().curve().shapeText())
                        + ", " + String.format(java.util.Locale.ROOT, "%.1f", p.density()) + " a chunk layer)");
            return parts.isEmpty() ? "" : "densest at " + String.join(" and ", parts);
        }

        /** The fact as the explore's step text says it ("oak_log grows on the surface: trees_plains is heightmap-placed"). */
        public String text() {
            return switch (kind) {
                case SURFACE -> sh(block) + " grows on the surface: " + sh(feature) + " is heightmap-placed";
                case BAND -> sh(block) + " generates under ground at Y " + lo + ".." + hi + ": " + sh(feature) + " is height-range-placed"
                        + (peakText().isEmpty() ? "" : "; " + peakText());
                case UNKNOWN -> "where it generates is not known: " + why;
                case NOT_LOADED -> "where it generates is not loaded yet: " + why;
            };
        }
    }

    public static Fact notLoaded(String why) { return new Fact(Kind.NOT_LOADED, 0, 0, null, null, why); }

    public static Fact unknown(String why) { return new Fact(Kind.UNKNOWN, 0, 0, null, null, why); }

    /** A placement list's band {lo, hi} (its height_range), null: none it reads. */
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

    /** 0.39.z68 review 2: veins per chunk: the count modifiers' means times the rarity filters' 1/chance (a count not read: 1). */
    static double veins(JsonArray placement) {
        double v = 1;
        if (placement == null) return v;
        for (JsonElement e : placement) {
            if (!e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            String t = type(m);
            if ("count".equals(t)) {
                JsonElement c = m.get("count");
                if (c != null && c.isJsonPrimitive()) v *= c.getAsDouble();
                else if (c != null && c.isJsonObject() && c.getAsJsonObject().has("value")) v *= c.getAsJsonObject().get("value").getAsDouble();
                else if (c != null && c.isJsonObject() && c.getAsJsonObject().has("min_inclusive") && c.getAsJsonObject().has("max_inclusive"))
                    v *= (c.getAsJsonObject().get("min_inclusive").getAsDouble() + c.getAsJsonObject().get("max_inclusive").getAsDouble()) / 2;
            } else if ("rarity_filter".equals(t) && m.has("chance") && m.get("chance").getAsInt() > 0) v /= m.get("chance").getAsInt();
        }
        return v;
    }

    /** How a heightmap placement lays the feature: on the land surface, on the sea floor, or none (no heightmap modifier). */
    enum Lay { LAND, FLOOR, NONE }

    /**
     * 0.39.z68 review 4: the heightmap modifier's map. WORLD_SURFACE(_WG) / MOTION_BLOCKING(_NO_LEAVES): the land surface.
     * OCEAN_FLOOR(_WG): the top solid block, under water or not - the land surface only when the same placement also has a
     * {@code surface_water_depth_filter} with {@code max_water_depth} 0 (no water over it: the game's tree placements), else
     * the sea floor (kelp, seagrass). Any other map: the sea floor's side (not called the surface).
     */
    static Lay lay(JsonArray placement) {
        if (placement == null) return Lay.NONE;
        String map = null;
        boolean dry = false;
        for (JsonElement e : placement) {
            if (!e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            String t = type(m);
            if ("heightmap".equals(t) && m.has("heightmap") && m.get("heightmap").isJsonPrimitive()) map = m.get("heightmap").getAsString();
            else if ("heightmap".equals(t)) map = "";
            if ("surface_water_depth_filter".equals(t) && m.has("max_water_depth") && m.get("max_water_depth").getAsInt() <= 0) dry = true;
        }
        if (map == null) return Lay.NONE;
        if (map.startsWith("WORLD_SURFACE") || map.startsWith("MOTION_BLOCKING")) return Lay.LAND;
        if (map.startsWith("OCEAN_FLOOR") && dry) return Lay.LAND;
        return Lay.FLOOR;
    }

    /**
     * The fact for the blocks that give a target ({@code blocks}: the loot data's blocks and the item's own block), from the
     * placers ({@code placers} null: not loaded). {@code bottom}/{@code top}: the dimension's build height for the anchors.
     * 0.39.z68 review 4: a sea-floor placer (OCEAN_FLOOR without the dry filter: kelp, seagrass) is not the surface: alone
     * it is UNKNOWN (said: "placed on the sea floor"), beside land or band placers it is left out.
     */
    public static Fact of(Collection<String> blocks, Collection<Placer> placers, int bottom, int top) {
        if (placers == null) return notLoaded("the world-gen read is not done");
        if (blocks == null || blocks.isEmpty()) return unknown("no block gives it");
        Set<String> want = new TreeSet<>(blocks);
        String sBlock = null, sFeat = null, bBlock = null, bFeat = null, fBlock = null, fFeat = null;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        List<Band> bands = new ArrayList<>();
        List<Placer> sorted = new ArrayList<>(placers);
        sorted.sort(java.util.Comparator.comparing(Placer::id));
        for (Placer p : sorted) {
            String hit = null;
            for (String b : p.blocks()) if (want.contains(b) && (hit == null || b.compareTo(hit) < 0)) hit = b;
            if (hit == null) continue;
            Lay lay = lay(p.placement());
            if (lay == Lay.LAND) {
                if (sBlock == null) { sBlock = hit; sFeat = p.id(); }
                continue;
            }
            if (lay == Lay.FLOOR) {
                if (fBlock == null) { fBlock = hit; fFeat = p.id(); }
                continue;
            }
            int[] r = band(p.placement(), bottom, top);
            if (r == null) continue;
            if (bBlock == null) { bBlock = hit; bFeat = p.id(); }
            lo = Math.min(lo, r[0]);
            hi = Math.max(hi, r[1]);
            // 0.39.z69 item 2: the placer's density curve (the same OreBand the strip way reads; not an ore shape: one a vein)
            bands.add(new Band(r[0], r[1], veins(p.placement()), p.id(), OreBand.of(p.placement(), Math.max(1, p.size()), p.discard(), bottom, top)));   // structural: not ore-shaped = one block a placement
        }
        if (sBlock != null && bBlock != null)
            return unknown(sh(sBlock) + " is placed on the ground's top (" + sh(sFeat) + ") and " + sh(bBlock) + " height-range-placed (" + sh(bFeat) + "): both ways");
        if (sBlock != null) return new Fact(Kind.SURFACE, 0, 0, sBlock, sFeat, null);
        if (bBlock != null) return new Fact(Kind.BAND, lo, hi, bBlock, bFeat, null, bands);
        if (fBlock != null) return unknown(sh(fBlock) + " is placed on the sea floor (" + sh(fFeat) + ": OCEAN_FLOOR, water allowed over it), not the land surface");
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
