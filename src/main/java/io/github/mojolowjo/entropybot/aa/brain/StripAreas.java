package io.github.mojolowjo.entropybot.aa.brain;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 0.39.z64 item 4 (runs S-U: every strip heading restarted from the same origin, so run U's 251 cells covered one ~26x27
 * area; its save: 969 faces, no iron on them, the iron one block behind): the strip's yield as a FACT of the area. Per area
 * - a square of {@code strip.areaSize} blocks at one Y - the faces the strip exposed and saw (rule 17: the fork's cell
 * reads, each face counted once) and the ore blocks among them by id. The strip way's price asks {@link #along} for the
 * areas its main tunnel would run through and scales the band's blocks-per-ore by {@link #factor}: the band's expected ore
 * per face blended with what those faces held (a prior of {@code strip.areaPriorFaces} faces; rough, rule 14). No facts:
 * factor 1, the band's estimate as before. Nothing here moves the bot: only the price changes (rule 1).
 * Pure (JUnit: Z64RunUTest). Loader notes: none (plain values; the mod's StripStep feeds it from the fork's
 * IStripProcess.drainCells and keeps it in the world's strip-areas.json).
 */
public final class StripAreas {
    private StripAreas() {}

    /** One area's facts: faces seen, ore blocks among them by id. */
    public record Seen(int faces, Map<String, Integer> ores) {
        public Seen {
            ores = Map.copyOf(ores);
        }
    }

    /** What the areas along a tunnel held: faces, the wanted ores among them, how many areas had any face. */
    public record Yield(int faces, int ores, int areas) {
        public static final Yield NONE = new Yield(0, 0, 0);
    }

    private static final Map<String, Seen> AREAS = new TreeMap<>();

    public static int size() { return Math.max(1, Tuning.i("strip.areaSize")); }

    /** The area key of a cell: "y ax az" (ax, az: the square's index along x and z). */
    public static String key(int x, int y, int z) {
        int n = size();
        return y + " " + Math.floorDiv(x, n) + " " + Math.floorDiv(z, n);
    }

    /** One cell read: its new faces and the ores among them. */
    public static synchronized void add(int x, int y, int z, int faces, Map<String, Integer> ores) {
        if (faces <= 0 && (ores == null || ores.isEmpty())) return;
        String k = key(x, y, z);
        Seen was = AREAS.get(k);
        Map<String, Integer> o = new TreeMap<>(was == null ? Map.of() : was.ores());
        if (ores != null) for (var e : ores.entrySet()) o.merge(e.getKey(), e.getValue(), Integer::sum);
        AREAS.put(k, new Seen((was == null ? 0 : was.faces()) + Math.max(0, faces), o));
    }

    public static synchronized Map<String, Seen> all() { return Map.copyOf(AREAS); }

    /** Replaces every fact (a world's saved file read). */
    public static synchronized void load(Map<String, Seen> m) {
        AREAS.clear();
        if (m != null) AREAS.putAll(m);
    }

    public static synchronized void clear() { AREAS.clear(); }

    /**
     * The areas the main tunnel from (x, y, z) in {@code heading} touches in its first {@code strip.areaLookCells} cells,
     * each counted once: their faces and the ore blocks of {@code oreIds} among them.
     */
    public static synchronized Yield along(int x, int y, int z, String heading, Collection<String> oreIds) {
        int[] d = io.github.mojolowjo.entropybot.aa.WayUp.dir(heading);
        Set<String> keys = new LinkedHashSet<>();
        int look = Math.max(0, Tuning.i("strip.areaLookCells"));
        for (int i = 0; i <= look; i++) keys.add(key(x + i * d[0], y, z + i * d[1]));
        int faces = 0, ores = 0, areas = 0;
        for (String k : keys) {
            Seen s = AREAS.get(k);
            if (s == null || s.faces() <= 0) continue;
            areas++;
            faces += s.faces();
            for (String id : oreIds) ores += s.ores().getOrDefault(id, 0);
        }
        return new Yield(faces, ores, areas);
    }

    /** The band's expected ore per block of the slab: perSlab over the slab's blocks (a chunk's 256 columns x the layers). */
    public static double perFace(double perSlab) {
        return perSlab / (256.0 * OreBand.slab());   // game fact: a chunk is 16 x 16 = 256 columns
    }

    /**
     * The scale of the band's blocks-per-ore for these faces: expected / observed rate, where observed = (expected x K +
     * ores) / (K + faces), K = {@code strip.areaPriorFaces}. No faces seen: 1. Run U: 37.1 ore a chunk in 16 layers ->
     * 0.00906 a face; 969 faces, 0 iron, K 200 -> observed 0.00155 -> x 5.85.
     */
    public static double factor(double perFace, Yield y) {
        if (y == null || y.faces() <= 0 || !(perFace > 0)) return 1.0;
        double k = Math.max(0, Tuning.get("strip.areaPriorFaces"));
        double observed = (perFace * k + y.ores()) / (k + y.faces());
        if (!(observed > 0)) return Double.POSITIVE_INFINITY;    // the caller's cap (strip.maxBlocksPerOre) holds
        return perFace / observed;
    }

    /** The areas along that heading are seen enough to judge the start by them ({@code strip.areaMinFaces}). */
    public static boolean judged(Yield y) {
        return y != null && y.faces() >= Math.max(1, Tuning.i("strip.areaMinFaces"));
    }
}
