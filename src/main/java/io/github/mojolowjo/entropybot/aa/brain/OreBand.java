package io.github.mojolowjo.entropybot.aa.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 0.39.z43 (owner, Run D 2026-10-23: "if it is looking for ore then it should just mine at the recommended level"): an ore's
 * world-gen placement as numbers the chain prices the strip way with (STRIP_MINING "third version", rule 14: rough). One
 * {@link Feature} per placed feature that places the ore: its height range (the placed_feature's {@code height_range}
 * modifier: a trapezoid with its plateau, or uniform = a trapezoid whose plateau is the whole range), its veins per chunk
 * (the {@code count} modifier, a {@code rarity_filter}'s 1/chance; none: one) and the configured feature's vein
 * {@code size}. The density at a Y is the expected ore blocks per chunk on that layer: veins x size x the height
 * distribution's share at Y (a trapezoid of base h and plateau p peaks at 2/(h+p)). Approximations said here, not hidden:
 * the air-exposure discard, the biome share (a mountains-only feature counts as everywhere) and the vein's real fill are
 * not modelled; a height provider other than trapezoid / uniform is read as uniform over its anchors. Pure (JUnit: OreBandTest).
 * Loader notes: the vanilla data format (placed_feature JSON, the PlacementModifier codec's JSON); NeoForge / Fabric alike.
 */
public final class OreBand {
    private OreBand() {}

    /** The chunk's column area in blocks (16 x 16, a game fact). */
    private static final int CHUNK_AREA = 256; // structural: 16 x 16 columns

    /** One placed feature: Y lo..hi (inclusive), plateau (hi - lo for uniform), veins per chunk, vein size. */
    public record Feature(int lo, int hi, int plateau, double veins, int size) {
        /** The Y the distribution peaks at (the middle: a trapezoid's plateau is centred, a uniform range's middle). */
        public int peak() { return Math.floorDiv(lo + hi, 2); }

        /** Expected ore blocks per chunk on layer y. */
        public double perY(int y) {
            if (y < lo || y > hi) return 0;
            int h = hi - lo;
            if (h <= 0) return veins * size;
            double top = 2.0 / (h + Math.min(plateau, h));
            double ramp = (h - Math.min(plateau, h)) / 2.0;
            double t = Math.min(y - lo, hi - y);
            double share = ramp <= 0 || t >= ramp ? top : top * t / ramp;
            return veins * size * share;
        }
    }

    /**
     * A placed feature's {@code placement} list (the JSON array as in data/&lt;ns&gt;/worldgen/placed_feature, or the codec's
     * encoding) and its configured feature's size. Null: no height_range in it (not a height-placed ore) or a count the
     * reader does not know (said by the caller).
     */
    public static Feature of(JsonArray placement, int size, int bottom, int top) {
        if (placement == null || size <= 0) return null;
        double veins = 1;
        int[] range = null;
        int plateau = -1;
        for (JsonElement e : placement) {
            if (!e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            String type = type(m);
            switch (type) {
                case "count" -> {
                    Double n = intProvider(m.get("count"));
                    if (n == null) return null;
                    veins *= n;
                }
                case "rarity_filter" -> {
                    if (!m.has("chance") || m.get("chance").getAsInt() <= 0) return null;
                    veins /= m.get("chance").getAsInt();
                }
                case "height_range" -> {
                    JsonElement h = m.get("height");
                    if (h == null) return null;
                    if (h.isJsonObject() && h.getAsJsonObject().has("type")) {
                        JsonObject ho = h.getAsJsonObject();
                        int a = anchor(ho.get("min_inclusive"), bottom, top), b = anchor(ho.get("max_inclusive"), bottom, top);
                        if (a == Integer.MIN_VALUE || b == Integer.MIN_VALUE) return null;
                        range = new int[]{Math.min(a, b), Math.max(a, b)};
                        plateau = "trapezoid".equals(type(ho)) ? (ho.has("plateau") ? ho.get("plateau").getAsInt() : 0) : range[1] - range[0];
                    } else {                                     // a constant anchor
                        int a = anchor(h, bottom, top);
                        if (a == Integer.MIN_VALUE) return null;
                        range = new int[]{a, a};
                        plateau = 0;
                    }
                }
                default -> { }
            }
        }
        if (range == null) return null;
        return new Feature(range[0], range[1], plateau, veins, size);
    }

    private static String type(JsonObject o) {
        String t = o.has("type") ? o.get("type").getAsString() : "";
        return t.startsWith("minecraft:") ? t.substring("minecraft:".length()) : t;
    }

    /** An int provider's mean: a number, or {type: uniform/biased..., min_inclusive, max_inclusive}, or {type: constant, value}. Null: unknown. */
    private static Double intProvider(JsonElement e) {
        if (e == null) return null;
        if (e.isJsonPrimitive()) return e.getAsDouble();
        if (!e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        if (o.has("value") && o.get("value").isJsonPrimitive()) return o.get("value").getAsDouble();
        if (o.has("min_inclusive") && o.has("max_inclusive")) return (o.get("min_inclusive").getAsDouble() + o.get("max_inclusive").getAsDouble()) / 2;
        return null;
    }

    /** {absolute: v} / {above_bottom: v} / {below_top: v} -> Y; MIN_VALUE: not an anchor. */
    private static int anchor(JsonElement e, int bottom, int top) {
        if (e == null || !e.isJsonObject()) return Integer.MIN_VALUE;
        for (String k : List.of("absolute", "above_bottom", "below_top"))
            if (e.getAsJsonObject().has(k)) return Thing.anchorY(k, e.getAsJsonObject().get(k).getAsInt(), bottom, top);
        return Integer.MIN_VALUE;
    }

    /** The layers a strip at Y sees around it ({@code strip.slabLayers}). */
    public static int slab() { return Tuning.i("strip.slabLayers"); }

    /** Expected ore blocks per chunk in the slab centred on y (every feature of the ore summed). */
    public static double perSlab(List<Feature> fs, int y) {
        int n = slab();
        double sum = 0;
        for (Feature f : fs) for (int dy = 0; dy < n; dy++) sum += f.perY(y - n / 2 + dy);
        return sum;
    }

    /**
     * The best strip Y: among the features' peaks (a peak above {@code ceiling} - the bot's feet: a strip digs into the
     * ground, it does not climb - counts at the ceiling when its range reaches it), the one whose slab holds the most.
     * Null: no feature reaches the ceiling or below.
     */
    public static Integer bestY(List<Feature> fs, int ceiling) {
        Integer best = null;
        double bestN = 0;
        for (Feature f : fs) {
            if (f.lo() > ceiling) continue;
            int y = Math.min(f.peak(), ceiling);
            double n = perSlab(fs, y);
            if (n > bestN) { bestN = n; best = y; }
        }
        return best;
    }

    /** Blocks dug per ore block found (the slab's blocks over its expected ore), capped by {@code strip.maxBlocksPerOre}. */
    public static double blocksPerOre(double perSlab) {
        double cap = Tuning.get("strip.maxBlocksPerOre");
        if (perSlab <= 0) return cap;
        return Math.min(cap, (double) CHUNK_AREA * slab() / perSlab);
    }
}
