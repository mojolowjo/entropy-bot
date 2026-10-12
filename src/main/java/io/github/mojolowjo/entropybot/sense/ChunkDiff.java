package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 0.28.0: what changed between two summaries of one chunk (an old one and a fresh read), as the readers want it: ores
 * and POI blocks added or removed (a move is a remove and an add), column tops moved. Also the self-audit's compare
 * ({@link #disagreements}): counts always, positions only where the list is whole (n at most the cap), lava by count.
 * Plain Java, JUnit. Loader notes: none.
 */
public final class ChunkDiff {
    private ChunkDiff() {}

    public enum Type { ORE_ADD, ORE_REMOVE, POI_ADD, POI_REMOVE, TOP }

    /** key: the ore family or the POI kind (null for TOP); id: the block id (null for TOP); y: the new top for TOP. */
    public record Change(Type type, String key, String id, int x, int y, int z) {}

    /** old may be null (nothing known): everything in fresh is added. */
    public static List<Change> between(ChunkSummary old, ChunkSummary fresh) {
        List<Change> out = new ArrayList<>();
        groups(old == null ? Map.of() : old.ores, fresh.ores, Type.ORE_ADD, Type.ORE_REMOVE, out);
        Map<String, ChunkSummary.Group> op = withLakes(old), np = withLakes(fresh);
        groups(op, np, Type.POI_ADD, Type.POI_REMOVE, out);
        int bx = fresh.cx << 4, bz = fresh.cz << 4;
        for (int i = 0; i < 256; i++) {
            int o = old == null ? ChunkSummary.NO_TOP : old.tops[i];
            if (o != fresh.tops[i]) out.add(new Change(Type.TOP, null, null, bx + (i & 15), fresh.tops[i], bz + (i >> 4)));
        }
        return out;
    }

    /** The POI groups plus a "lava lake" group made of the lake sections. */
    static Map<String, ChunkSummary.Group> withLakes(ChunkSummary s) {
        Map<String, ChunkSummary.Group> m = new java.util.TreeMap<>();
        if (s == null) return m;
        m.putAll(s.pois);
        List<ChunkSummary.Spot> lakes = s.lavaLakes();
        if (!lakes.isEmpty()) {
            ChunkSummary.Group g = new ChunkSummary.Group();
            for (ChunkSummary.Spot sp : lakes) g.add(sp);
            m.put(ChunkSummary.LAVA_LAKE_KIND, g);
        }
        return m;
    }

    private static void groups(Map<String, ChunkSummary.Group> a, Map<String, ChunkSummary.Group> b, Type add, Type rem, List<Change> out) {
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            ChunkSummary.Group ga = a.get(k), gb = b.get(k);
            Set<ChunkSummary.Spot> sa = ga == null ? Set.of() : new HashSet<>(ga.spots);
            Set<ChunkSummary.Spot> sb = gb == null ? Set.of() : new HashSet<>(gb.spots);
            if (ga != null) for (ChunkSummary.Spot s : ga.spots) if (!sb.contains(s)) out.add(new Change(rem, k, s.id(), s.x(), s.y(), s.z()));
            if (gb != null) for (ChunkSummary.Spot s : gb.spots) if (!sa.contains(s)) out.add(new Change(add, k, s.id(), s.x(), s.y(), s.z()));
        }
    }

    /**
     * The self-audit: where a patched summary and a fresh read disagree, as short field names ("ores.iron count 3 vs 2",
     * "tops 5 columns", "lava section 3"). Empty when they agree.
     */
    public static List<String> disagreements(ChunkSummary patched, ChunkSummary read) {
        List<String> out = new ArrayList<>();
        compare("ores", patched.ores, read.ores, out);
        compare("pois", patched.pois, read.pois, out);
        Set<Integer> secs = new TreeSet<>(patched.lava.keySet());
        secs.addAll(read.lava.keySet());
        for (Integer sy : secs) {
            int a = patched.lava.containsKey(sy) ? patched.lava.get(sy)[0] : 0, b = read.lava.containsKey(sy) ? read.lava.get(sy)[0] : 0;
            if (a != b) out.add("lava section " + sy + " " + a + " vs " + b);
        }
        int cols = 0;
        for (int i = 0; i < 256; i++) if (patched.tops[i] != read.tops[i]) cols++;
        if (cols > 0) out.add("tops " + cols + " column" + (cols == 1 ? "" : "s"));
        return out;
    }

    private static void compare(String what, Map<String, ChunkSummary.Group> a, Map<String, ChunkSummary.Group> b, List<String> out) {
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            ChunkSummary.Group ga = a.get(k), gb = b.get(k);
            int na = ga == null ? 0 : ga.n, nb = gb == null ? 0 : gb.n;
            if (na != nb) {
                out.add(what + "." + k + " count " + na + " vs " + nb);
                continue;
            }
            if (ga == null || gb == null || nb > io.github.mojolowjo.entropybot.memory.Limits.SENSE_SPOTS) continue;
            if (!new HashSet<>(ga.spots).equals(new HashSet<>(gb.spots))) out.add(what + "." + k + " positions");
        }
    }
}
