package io.github.mojolowjo.entropybot.aa.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.39.z43 (owner, Run D 2026-10-23: "if it is looking for ore then it should just mine at the recommended level"): an ore's
 * world-gen placement as numbers the chain prices the strip way with (STRIP_MINING "third version", rule 14: rough). One
 * {@link Feature} per placed feature that places the ore: its height range (the placed_feature's {@code height_range}
 * modifier: a trapezoid with its plateau, or uniform = a trapezoid whose plateau is the whole range), its veins per chunk
 * (the {@code count} modifier, a {@code rarity_filter}'s 1/chance; none: one) and the configured feature's vein
 * {@code size}. The density at a Y is the expected ore blocks per chunk on that layer: veins x size x the height
 * distribution's share at Y (a trapezoid of base h and plateau p peaks at 2/(h+p)).
 * <p>0.39.z69 item 2 (owner 2026-10-25: "we can't just accept a range like -64..384; does the data tell where the ore is
 * denser?"): the ONE density curve by height for a target - the strip way's best Y ({@link #bestY}) and the explore's
 * "where" fact (GenWhere: the cave ranking, the peak said in the explore line) both read it. The height provider's kind is
 * read: uniform, trapezoid with its plateau, and the game's {@code biased_to_bottom} / {@code very_biased_to_bottom} (their
 * {@code inner}, the vanilla samplers' distributions summed exactly); the configured feature's
 * {@code discard_chance_on_air_exposure} is kept: an ore block beside air at generation is skipped with that chance, so
 * the ore a cave wall shows is the density x (1 - discard) ({@link Feature#exposedPerY}) - caves only, a strip's tunnel
 * opens ore that was buried. Approximations said here, not hidden: the biome share (a mountains-only feature counts as
 * everywhere) and the vein's real fill are not modelled; another height provider kind is read as uniform over its anchors.
 * Pure (JUnit: OreBandTest, Z69OreCurveTest). Loader notes: the vanilla data format (placed_feature JSON, the
 * PlacementModifier and HeightProvider codecs' JSON, OreConfiguration's fields); NeoForge / Fabric alike.
 */
public final class OreBand {
    private OreBand() {}

    /** The chunk's column area in blocks (16 x 16, a game fact). */
    private static final int CHUNK_AREA = 256; // structural: 16 x 16 columns

    /** 0.39.z69 item 2: the height provider's distribution kind (the game's HeightProvider types). */
    public enum Shape { UNIFORM, TRAPEZOID, BIASED_TO_BOTTOM, VERY_BIASED_TO_BOTTOM }

    /**
     * One placed feature: Y lo..hi (inclusive), plateau (hi - lo for uniform), veins per chunk, vein size; 0.39.z69: the
     * configured feature's air-exposure discard chance, the height provider's kind and its {@code inner} (biased kinds).
     */
    public record Feature(int lo, int hi, int plateau, double veins, int size, double discard, Shape shape, int inner) {
        public Feature {
            discard = Math.max(0, Math.min(1, discard));
            if (shape == null) shape = plateau >= hi - lo ? Shape.UNIFORM : Shape.TRAPEZOID;
        }

        /** A uniform / trapezoid feature with no air-exposure discard (the kind from the plateau: the whole range = uniform). */
        public Feature(int lo, int hi, int plateau, double veins, int size) { this(lo, hi, plateau, veins, size, 0, null, 0); }

        /** The Y the distribution peaks at (the middle: a trapezoid's plateau is centred, a uniform range's middle; a biased kind's bottom). */
        public int peak() {
            return shape == Shape.BIASED_TO_BOTTOM || shape == Shape.VERY_BIASED_TO_BOTTOM ? lo : Math.floorDiv(lo + hi, 2);
        }

        /** Expected ore blocks per chunk on layer y. */
        public double perY(int y) {
            if (y < lo || y > hi) return 0;
            int h = hi - lo;
            if (h <= 0) return veins * size;
            if (shape == Shape.BIASED_TO_BOTTOM || shape == Shape.VERY_BIASED_TO_BOTTOM) return veins * size * biased(shape, h, inner, y - lo);
            double top = 2.0 / (h + Math.min(plateau, h));
            double ramp = (h - Math.min(plateau, h)) / 2.0;
            double t = Math.min(y - lo, hi - y);
            double share = ramp <= 0 || t >= ramp ? top : top * t / ramp;
            return veins * size * share;
        }

        /**
         * 0.39.z69 item 2: expected ore blocks per chunk on layer y that a cave wall shows: an ore block beside air at
         * generation is skipped with the discard chance (OreFeature's air check), so (1 - discard) of the density remains.
         */
        public double exposedPerY(int y) { return perY(y) * (1 - discard); }

        /** "trapezoid -24..56" (the explore line's words). */
        public String shapeText() { return shape.name().toLowerCase(java.util.Locale.ROOT) + " " + lo + ".." + hi; }
    }

    /** 0.39.z69: the biased kinds' shares by (kind, range, inner), computed once each. */
    private static final Map<String, double[]> BIASED = new ConcurrentHashMap<>();

    /**
     * 0.39.z69 item 2: the share of a biased height provider at offset t (0..h) of its range h, the vanilla samplers summed:
     * {@code biased_to_bottom}: k = nextInt(h - inner + 1), then t = nextInt(k + inner); {@code very_biased_to_bottom}:
     * k in [inner, h], l in [0, k - 1], t in [0, l - 1 + inner] (Mth.nextInt inclusive). A range too short for the inner:
     * everything at the bottom (the samplers' own fallback). Rough (rule 14), said: with {@code inner} over 2 the
     * very-biased sampler can return a Y above the range's top (l - 1 + inner past h); that share is cut off here, not
     * moved, so the layers 0..h sum to a little under one then.
     */
    static double biased(Shape kind, int h, int inner, int t) {
        if (t < 0 || t > h) return 0;
        int in = Math.max(1, inner);
        double[] share = BIASED.computeIfAbsent(kind + " " + h + " " + in, k -> biasedShares(kind, h, in));
        return share[t];
    }

    private static double[] biasedShares(Shape kind, int h, int inner) {
        double[] out = new double[h + 2];
        int r = h - inner + 1;
        if (r <= 0) { out[0] = 1; return out; }
        double[] diff = new double[h + 3];   // structural: offsets 0..h plus the end marks
        if (kind == Shape.BIASED_TO_BOTTOM) {
            for (int k = 0; k < r; k++) {                     // t uniform on [0, k + inner - 1]
                double w = 1.0 / r / (k + inner);
                diff[0] += w;
                diff[Math.min(h + 1, k + inner)] -= w;
            }
        } else {
            double[] pl = new double[h + 2], dl = new double[h + 3];   // structural: offsets 0..h plus the end marks
            for (int k = inner; k <= h; k++) {                // l uniform on [0, k - 1]
                double w = 1.0 / r / k;
                dl[0] += w;
                dl[k] -= w;
            }
            double run = 0;
            for (int l = 0; l <= h; l++) { run += dl[l]; pl[l] = run; }
            for (int l = 0; l <= h; l++) {                    // t uniform on [0, l - 1 + inner]
                if (pl[l] <= 0) continue;
                int n = l + inner;
                double w = pl[l] / n;
                diff[0] += w;
                diff[Math.min(h + 1, n)] -= w;
            }
        }
        double run = 0;
        for (int t = 0; t <= h; t++) { run += diff[t]; out[t] = Math.max(0, run); }
        return out;
    }

    /**
     * A placed feature's {@code placement} list (the JSON array as in data/&lt;ns&gt;/worldgen/placed_feature, or the codec's
     * encoding) and its configured feature's size. Null: no height_range in it (not a height-placed ore) or a count the
     * reader does not know (said by the caller).
     */
    public static Feature of(JsonArray placement, int size, int bottom, int top) { return of(placement, size, 0, bottom, top); }

    /** 0.39.z69 item 2: as {@link #of(JsonArray, int, int, int)} with the configured feature's air-exposure discard chance. */
    public static Feature of(JsonArray placement, int size, double discard, int bottom, int top) {
        if (placement == null || size <= 0) return null;
        double veins = 1;
        int[] range = null;
        int plateau = -1, inner = 0;
        Shape shape = null;
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
                        String kind = type(ho);
                        plateau = "trapezoid".equals(kind) ? (ho.has("plateau") ? ho.get("plateau").getAsInt() : 0) : range[1] - range[0];
                        // 0.39.z69 item 2: the biased kinds keep their shape (their mass sits at the bottom; inner: the codec's default 1)
                        if ("biased_to_bottom".equals(kind)) shape = Shape.BIASED_TO_BOTTOM;
                        else if ("very_biased_to_bottom".equals(kind)) shape = Shape.VERY_BIASED_TO_BOTTOM;
                        if (shape != null) inner = ho.has("inner") && ho.get("inner").isJsonPrimitive() ? ho.get("inner").getAsInt() : 1;
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
        return new Feature(range[0], range[1], plateau, veins, size, discard, shape, inner);
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
     * 0.39.z69 item 2: the curve at y - expected ore blocks per chunk on that layer, every feature summed; {@code cave}: what
     * a cave wall shows (each feature's air-exposure discard taken off), else what is buried (a strip opens it).
     */
    public static double at(List<Feature> fs, int y, boolean cave) {
        double sum = 0;
        for (Feature f : fs) sum += cave ? f.exposedPerY(y) : f.perY(y);
        return sum;
    }

    /**
     * 0.39.z69 item 2: the curve's peaks - the layers where the summed density is higher than on both sides (a flat top:
     * its middle), lowest Y first. Empty: no feature.
     */
    public static List<Integer> peaks(List<Feature> fs, boolean cave) {
        List<Integer> out = new ArrayList<>();
        if (fs == null || fs.isEmpty()) return out;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Feature f : fs) { lo = Math.min(lo, f.lo()); hi = Math.max(hi, f.hi()); }
        double prev = 0;                                       // below the lowest layer: nothing
        int y = lo;
        while (y <= hi) {
            double v = at(fs, y, cave);
            int end = y;
            while (end + 1 <= hi && at(fs, end + 1, cave) == v) end++;     // a flat run
            double next = end + 1 <= hi ? at(fs, end + 1, cave) : 0;
            if (v > 0 && v > prev && v > next) out.add(Math.floorDiv(y + end, 2));
            prev = v;
            y = end + 1;
        }
        return out;
    }

    /**
     * The best strip Y: a PEAK of the ore's curve (0.39.z46, owner: "the band's peak Y"; runs E-G: z43 counted a peak above
     * the feet at the feet, so coal's 0..192 band struck at y 70 on the surface and Baritone said "no path"). 0.39.z69 item
     * 2: the peaks of the one curve ({@link #peaks}, buried ore: a strip opens it), the same the explore line says. Among
     * the peaks at or below {@code ceiling} (the feet: the strip way's first leg is the priced path down to it), the one
     * whose slab holds the most; when every peak lies above the feet, the lowest of them (the priced path tells whether it
     * can be reached). Null: no feature.
     */
    public static Integer bestY(List<Feature> fs, int ceiling) { return bestY(fs, ceiling, false); }

    /**
     * 0.39.z75 item 2c (run AF 18:22-18:42: under ground at y 13-16 inside coal's band 0..192, no coal way offered - its one
     * peak, y 96, lies above the feet, so the strip asked for a climb into the air): {@code inRock} - the feet are in the
     * rock (under ground): the feet's own Y is a strip Y too when the band holds ore there (a tunnel from where the bot
     * stands, no walk), compared with the peaks below it by the slab's ore. On the surface (false): as before, the peaks
     * only (a tunnel at the surface's height runs into the open air).
     */
    public static Integer bestY(List<Feature> fs, int ceiling, boolean inRock) {
        Integer best = null, above = null;
        double bestN = 0;
        if (inRock && fs != null) {
            double here = perSlab(fs, ceiling);
            if (here > 0) { best = ceiling; bestN = here; }
        }
        for (int y : peaks(fs, false)) {
            if (y > ceiling) {
                if (above == null || y < above) above = y;
                continue;
            }
            double n = perSlab(fs, y);
            if (n > bestN) { bestN = n; best = y; }
        }
        return best != null ? best : above;
    }

    /** Blocks dug per ore block found (the slab's blocks over its expected ore), capped by {@code strip.maxBlocksPerOre}. */
    public static double blocksPerOre(double perSlab) {
        double cap = Tuning.get("strip.maxBlocksPerOre");
        if (perSlab <= 0) return cap;
        return Math.min(cap, (double) CHUNK_AREA * slab() / perSlab);
    }
}
