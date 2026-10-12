package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.memory.Limits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * 0.28.0: the summaries the pass keeps, per dimension, and the ore queries over them: the nearest positions of some ore
 * ids (inside the areas and outside a protect margin: the caller's {@link Allow}), totals per family within r, the chunks
 * seen. Past {@link #max} summaries in a dimension the oldest seen leave memory (a stale one first; files stay). Summaries
 * loaded from files are stale until the chunk is read again ({@link #loadStale}).
 *
 * <p>Plain Java, JUnit, game thread only. Loader notes: none.
 */
public final class OreIndex {
    /** May the bot go for ore at x y z? (areas, protect margin, the guard: the game side's rules.) */
    public interface Allow {
        boolean test(int x, int y, int z);
    }

    public record Hit(int x, int y, int z, String id, String family, long d2, boolean stale) {}

    /**
     * A pure fence for tests and simple callers: inside one of the areas (x1 z1 x2 z2, any order; none = everywhere) and
     * more than margin blocks from every protect box (x1 y1 z1 x2 y2 z2).
     */
    public record Fence(List<int[]> areas, List<int[]> protect, int margin) implements Allow {
        @Override public boolean test(int x, int y, int z) {
            boolean in = areas.isEmpty();
            for (int[] a : areas) {
                if (x >= Math.min(a[0], a[2]) && x <= Math.max(a[0], a[2]) && z >= Math.min(a[1], a[3]) && z <= Math.max(a[1], a[3])) {
                    in = true;
                    break;
                }
            }
            if (!in) return false;
            for (int[] p : protect) {
                if (x >= Math.min(p[0], p[3]) - margin && x <= Math.max(p[0], p[3]) + margin && y >= Math.min(p[1], p[4]) - margin
                        && y <= Math.max(p[1], p[4]) + margin && z >= Math.min(p[2], p[5]) - margin && z <= Math.max(p[2], p[5]) + margin) return false;
            }
            return true;
        }
    }

    private final int max;
    private final Map<String, LinkedHashMap<Long, ChunkSummary>> dims = new HashMap<>();
    public long evicted;

    public OreIndex() { this(Limits.SENSE_SUMMARIES); }

    public OreIndex(int max) { this.max = max; }

    private LinkedHashMap<Long, ChunkSummary> dim(String d) { return dims.computeIfAbsent(d, k -> new LinkedHashMap<>()); }

    public ChunkSummary get(String d, int cx, int cz) {
        Map<Long, ChunkSummary> m = dims.get(d);
        return m == null ? null : m.get(ChunkSummary.key(cx, cz));
    }

    /** Stores (replaces) a summary as the newest; drops the oldest past the cap (a stale one first). Returns how many went. */
    public int put(ChunkSummary s) {
        LinkedHashMap<Long, ChunkSummary> m = dim(s.dim);
        m.remove(s.key());
        m.put(s.key(), s);
        return trim(m, s.key());
    }

    private int trim(LinkedHashMap<Long, ChunkSummary> m, long keep) {
        int gone = 0;
        while (m.size() > max) {
            Long victim = null;
            for (Map.Entry<Long, ChunkSummary> e : m.entrySet()) {
                if (e.getValue().stale && e.getKey() != keep) {
                    victim = e.getKey();
                    break;
                }
            }
            if (victim == null) {
                Iterator<Long> it = m.keySet().iterator();
                victim = it.next();
                if (victim == keep && it.hasNext()) victim = it.next();
            }
            m.remove(victim);
            gone++;
            evicted++;
        }
        return gone;
    }

    public void remove(String d, int cx, int cz) {
        Map<Long, ChunkSummary> m = dims.get(d);
        if (m != null) m.remove(ChunkSummary.key(cx, cz));
    }

    /**
     * Summaries from the files: each comes in stale, oldest seen first (so the cap drops the oldest); a summary already
     * in memory that is fresher (a chunk read since) is kept. Returns how many were taken.
     */
    public int loadStale(List<ChunkSummary> list) {
        List<ChunkSummary> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingLong(s -> s.seenMs));
        Map<String, LinkedHashMap<Long, ChunkSummary>> in = new HashMap<>();
        int n = 0;
        for (ChunkSummary s : sorted) {
            ChunkSummary old = get(s.dim, s.cx, s.cz);
            if (old != null && (!old.stale || old.seenMs >= s.seenMs)) continue;
            s.stale = true;
            in.computeIfAbsent(s.dim, k -> new LinkedHashMap<>()).put(s.key(), s);
            n++;
        }
        // older than what memory holds: in at the old end, so the cap drops them first
        for (Map.Entry<String, LinkedHashMap<Long, ChunkSummary>> e : in.entrySet()) {
            LinkedHashMap<Long, ChunkSummary> m = dim(e.getKey()), again = new LinkedHashMap<>(e.getValue());
            for (Map.Entry<Long, ChunkSummary> o : m.entrySet()) if (!again.containsKey(o.getKey())) again.put(o.getKey(), o.getValue());
            m.clear();
            m.putAll(again);
            trim(m, Long.MIN_VALUE);
        }
        return n;
    }

    /** Every summary of a dimension stale (the world was left, or the chunks went). */
    public void markAllStale() {
        for (Map<Long, ChunkSummary> m : dims.values()) for (ChunkSummary s : m.values()) s.stale = true;
    }

    public int size(String d) {
        Map<Long, ChunkSummary> m = dims.get(d);
        return m == null ? 0 : m.size();
    }

    public int size() {
        int n = 0;
        for (Map<Long, ChunkSummary> m : dims.values()) n += m.size();
        return n;
    }

    public int staleCount() {
        int n = 0;
        for (Map<Long, ChunkSummary> m : dims.values()) for (ChunkSummary s : m.values()) if (s.stale) n++;
        return n;
    }

    /** Families known in all dimensions, and the listed positions in all. */
    public int[] familiesAndPositions() {
        java.util.Set<String> fam = new java.util.HashSet<>();
        int pos = 0;
        for (Map<Long, ChunkSummary> m : dims.values())
            for (ChunkSummary s : m.values()) {
                fam.addAll(s.ores.keySet());
                pos += s.orePositions();
            }
        return new int[]{fam.size(), pos};
    }

    public Iterable<ChunkSummary> all(String d) {
        Map<Long, ChunkSummary> m = dims.get(d);
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    private static boolean chunkNear(ChunkSummary s, int x, int z, int r) {
        int bx = s.cx << 4, bz = s.cz << 4;
        int dx = x < bx ? bx - x : x > bx + 15 ? x - bx - 15 : 0;
        int dz = z < bz ? bz - z : z > bz + 15 ? z - bz - 15 : 0;
        return (long) dx * dx + (long) dz * dz <= (long) r * r;
    }

    /** The nearest n listed positions whose id idOk takes, within r (3D) of x y z, that allow takes (null: all). */
    public List<Hit> nearest(String d, Predicate<String> idOk, int x, int y, int z, int r, int n, Allow allow) {
        List<Hit> hits = new ArrayList<>();
        Map<Long, ChunkSummary> m = dims.get(d);
        if (m == null || n <= 0) return hits;
        long r2 = (long) r * r;
        for (ChunkSummary s : m.values()) {
            if (!chunkNear(s, x, z, r)) continue;
            for (Map.Entry<String, ChunkSummary.Group> e : s.ores.entrySet()) {
                for (ChunkSummary.Spot sp : e.getValue().spots) {
                    if (!idOk.test(sp.id())) continue;
                    long dx = sp.x() - x, dy = sp.y() - y, dz = sp.z() - z, d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > r2) continue;
                    if (allow != null && !allow.test(sp.x(), sp.y(), sp.z())) continue;
                    hits.add(new Hit(sp.x(), sp.y(), sp.z(), sp.id(), e.getKey(), d2, s.stale));
                }
            }
        }
        hits.sort(Comparator.comparingLong(Hit::d2).thenComparingInt(Hit::y).thenComparingInt(Hit::x).thenComparingInt(Hit::z));
        return hits.size() > n ? new ArrayList<>(hits.subList(0, n)) : hits;
    }

    /** Family -> exact count in the chunks within r (flat) of x z; chunks: those chunks whose (x z) allow takes, by centre. */
    public Map<String, Integer> totals(String d, int x, int z, int r) {
        Map<String, Integer> out = new TreeMap<>();
        Map<Long, ChunkSummary> m = dims.get(d);
        if (m == null) return out;
        for (ChunkSummary s : m.values()) {
            if (!chunkNear(s, x, z, r)) continue;
            for (Map.Entry<String, ChunkSummary.Group> e : s.ores.entrySet()) out.merge(e.getKey(), e.getValue().n, Integer::sum);
        }
        return out;
    }

    /** A count within r: the listed positions inside r; capped = some chunk's list is cut at the cap, so the true number may be higher. */
    public record Count(int n, boolean capped) {}

    /**
     * 0.28.0 fix: family -> positions within r (3D) of x y z, counted from the listed positions (never whole-chunk sums);
     * capped when a chunk in reach holds more of that family than it lists.
     */
    public Map<String, Count> within(String d, int x, int y, int z, int r) {
        Map<String, int[]> acc = new TreeMap<>();
        Map<Long, ChunkSummary> m = dims.get(d);
        if (m != null) {
            long r2 = (long) r * r;
            for (ChunkSummary s : m.values()) {
                if (!chunkNear(s, x, z, r)) continue;
                for (Map.Entry<String, ChunkSummary.Group> e : s.ores.entrySet()) {
                    int in = 0;
                    for (ChunkSummary.Spot sp : e.getValue().spots) {
                        long dx = sp.x() - x, dy = sp.y() - y, dz = sp.z() - z;
                        if (dx * dx + dy * dy + dz * dz <= r2) in++;
                    }
                    boolean cut = e.getValue().n > e.getValue().spots.size();
                    if (in == 0 && !cut) continue;
                    int[] a = acc.computeIfAbsent(e.getKey(), k -> new int[2]);
                    a[0] += in;
                    if (cut) a[1] = 1;
                }
            }
        }
        Map<String, Count> out = new TreeMap<>();
        for (Map.Entry<String, int[]> e : acc.entrySet()) out.put(e.getKey(), new Count(e.getValue()[0], e.getValue()[1] == 1));
        return out;
    }

    /** Chunks known within r (flat) of x z whose centre column allow takes (null: all). */
    public int chunksSeen(String d, int x, int z, int r, Allow allow) {
        Map<Long, ChunkSummary> m = dims.get(d);
        if (m == null) return 0;
        int n = 0;
        for (ChunkSummary s : m.values()) {
            if (!chunkNear(s, x, z, r)) continue;
            if (allow != null && !allow.test((s.cx << 4) + 8, 64, (s.cz << 4) + 8)) continue;
            n++;
        }
        return n;
    }

    /** POI kind positions within r (3D) of x y z (any chunk summary), for "is that point of interest still there?". */
    public boolean poiNear(String d, String kind, int x, int y, int z, int r) {
        Map<Long, ChunkSummary> m = dims.get(d);
        if (m == null) return false;
        long r2 = (long) r * r;
        for (ChunkSummary s : m.values()) {
            if (!chunkNear(s, x, z, r)) continue;
            List<ChunkSummary.Spot> spots = kind.equals(ChunkSummary.LAVA_LAKE_KIND) ? s.lavaLakes()
                    : s.pois.containsKey(kind) ? s.pois.get(kind).spots : List.of();
            for (ChunkSummary.Spot sp : spots) {
                long dx = sp.x() - x, dy = sp.y() - y, dz = sp.z() - z;
                if (dx * dx + dy * dy + dz * dz <= r2) return true;
            }
        }
        return false;
    }
}
