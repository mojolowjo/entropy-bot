package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.39.z78 J6 (owner's ruling 2026-10-27 late, docs/CAVE_DETECTION.md; to-do/j6-caves-by-rays.md): caves are known by
 * rays, the way ore is. This is the cave INDEX of one dimension: the connected air volumes below the WORLD_SURFACE
 * heightmap of the chunks read, merged across chunks (union-find). It is what the rays aim at, never a fact by itself:
 * a volume becomes a known cave only when {@link #KNOWN_RAYS} sight rays ended in it or within one block of it
 * ({@link #credit}); the known fact is kept in Sightings (the one knowledge store, per world; rule 23).
 * <p>Per chunk the index keeps each column's air runs below its surface (y from, y to, a run id; a few per column), so a
 * chunk costs a few kilobytes. Runs in neighbouring columns (this chunk or a neighbour already read) that share a Y are
 * one volume. A volume keeps its cells, its depth range and the distinct cells the rays credited. Its entry is found when
 * it becomes known: of its cells that touch a sky-lit column sideways (a neighbour column whose surface is at or below
 * that cell), the one nearest the credited rays' hit cells. A chunk is indexed once per session (the natural caves; the
 * bot's own tunnels after that do not join them); {@link #MAX_CHUNKS} bounds the memory. No knob. Pure (JUnit:
 * J6CaveIndexTest). Loader notes: none (the chunk pass reads the blocks and gives the runs, ChunkPass#readOne).
 */
public final class CaveIndex {
    /** The distinct sight rays (their hit cells) that make a volume a known cave. */
    public static final int KNOWN_RAYS = 3; // structural: the owner's ruling, 2026-10-27
    /** structural: a memory bound - chunks indexed per dimension (past it the index starts again; known caves stay in Sightings). */
    static final int MAX_CHUNKS = 8192;

    /** One chunk's columns (x + 16 z): the air runs below the surface, each {from, to} (inclusive), lowest first. */
    public record ChunkRuns(int cx, int cz, int[] surface, int[][][] runs) {}

    /** A volume as the rays and the planner see it. */
    public record Volume(int root, long cells, int minY, int maxY, Set<Long> credits) {}

    /** What a credit did: no volume there (an unindexed cell, a chunk edge), credited, or made the volume a known cave. */
    public enum Credit { NO_VOLUME, CREDITED, ALREADY_KNOWN, KNOWN }

    /** The known cave a credit made: its entry cell, cells, depth range, the credited hit cells. */
    public record Known(int x, int y, int z, long cells, int minY, int maxY, List<Long> hits) {}

    /** A credit's answer: what it did, the volume's root (-1 none), the known cave when this credit made it. */
    public record Result(Credit what, int root, Known known) {}

    private static final class Chunk {
        final int cx, cz;
        final int[] surface;          // 256
        final int[][] lo, hi, id;     // per column
        Chunk(int cx, int cz, int[] surface, int[][] lo, int[][] hi, int[][] id) { this.cx = cx; this.cz = cz; this.surface = surface; this.lo = lo; this.hi = hi; this.id = id; }
    }

    private final Map<Long, Chunk> chunks = new HashMap<>();
    private int[] parent = new int[1024];
    private long[] cells = new long[1024];
    private int[] minY = new int[1024], maxY = new int[1024];
    private final Map<Integer, Set<Long>> credits = new HashMap<>();
    private final Set<Integer> known = new java.util.HashSet<>();
    private int next;

    public static long chunkKey(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    public static long cell(int x, int y, int z) { return Sightings.cell(x, y, z); }

    public synchronized int chunks() { return chunks.size(); }

    public synchronized boolean has(int cx, int cz) { return chunks.containsKey(chunkKey(cx, cz)); }

    /** A new world or dimension: nothing indexed. */
    public synchronized void clear() {
        chunks.clear();
        credits.clear();
        known.clear();
        next = 0;
    }

    /** A chunk's runs (read once per session: a chunk already indexed is kept as it was). */
    public synchronized void put(ChunkRuns r) {
        long k = chunkKey(r.cx(), r.cz());
        if (chunks.containsKey(k)) return;
        if (chunks.size() >= MAX_CHUNKS) clear();
        int[][] lo = new int[256][], hi = new int[256][], id = new int[256][];
        for (int c = 0; c < 256; c++) {
            int[][] col = r.runs()[c] == null ? new int[0][] : r.runs()[c];
            lo[c] = new int[col.length];
            hi[c] = new int[col.length];
            id[c] = new int[col.length];
            for (int i = 0; i < col.length; i++) {
                lo[c][i] = col[i][0];
                hi[c][i] = col[i][1];
                id[c][i] = node(col[i][0], col[i][1]);
            }
        }
        Chunk ch = new Chunk(r.cx(), r.cz(), r.surface().clone(), lo, hi, id);
        chunks.put(k, ch);
        // inside the chunk: each column with its +x and +z neighbour
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                if (lx < 15) join(ch, lx, lz, ch, lx + 1, lz);
                if (lz < 15) join(ch, lx, lz, ch, lx, lz + 1);
            }
        // across: the edges shared with the neighbours already read
        Chunk w = chunks.get(chunkKey(r.cx() - 1, r.cz())), e = chunks.get(chunkKey(r.cx() + 1, r.cz()));
        Chunk n = chunks.get(chunkKey(r.cx(), r.cz() - 1)), s = chunks.get(chunkKey(r.cx(), r.cz() + 1));
        for (int i = 0; i < 16; i++) {
            if (w != null) join(ch, 0, i, w, 15, i);
            if (e != null) join(ch, 15, i, e, 0, i);
            if (n != null) join(ch, i, 0, n, i, 15);
            if (s != null) join(ch, i, 15, s, i, 0);
        }
    }

    private int node(int from, int to) {
        if (next == parent.length) {
            int len = parent.length * 2;
            parent = java.util.Arrays.copyOf(parent, len);
            cells = java.util.Arrays.copyOf(cells, len);
            minY = java.util.Arrays.copyOf(minY, len);
            maxY = java.util.Arrays.copyOf(maxY, len);
        }
        int i = next++;
        parent[i] = i;
        cells[i] = to - from + 1L;
        minY[i] = from;
        maxY[i] = to;
        return i;
    }

    private int find(int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        if (cells[ra] < cells[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        cells[ra] += cells[rb];
        minY[ra] = Math.min(minY[ra], minY[rb]);
        maxY[ra] = Math.max(maxY[ra], maxY[rb]);
        Set<Long> cb = credits.remove(rb);
        if (cb != null) credits.computeIfAbsent(ra, k -> new LinkedHashSet<>()).addAll(cb);
        if (known.remove(rb)) known.add(ra);
    }

    /** The runs of two side-by-side columns that share a Y are one volume. */
    private void join(Chunk a, int ax, int az, Chunk b, int bx, int bz) {
        int ca = ax + 16 * az, cb = bx + 16 * bz;
        int[] alo = a.lo[ca], ahi = a.hi[ca], blo = b.lo[cb], bhi = b.hi[cb];
        for (int i = 0, j = 0; i < alo.length && j < blo.length; ) {
            if (alo[i] <= bhi[j] && blo[j] <= ahi[i]) union(a.id[ca][i], b.id[cb][j]);
            if (ahi[i] < bhi[j]) i++;
            else j++;
        }
    }

    /** The run id holding x y z, -1 none (not indexed, not a cave cell). */
    private int runAt(int x, int y, int z) {
        Chunk ch = chunks.get(chunkKey(x >> 4, z >> 4));
        if (ch == null) return -1;
        int c = (x & 15) + 16 * (z & 15);
        for (int i = 0; i < ch.lo[c].length; i++) if (ch.lo[c][i] <= y && y <= ch.hi[c][i]) return ch.id[c][i];
        return -1;
    }

    /** The volume x y z is in (its root), -1 none. */
    public synchronized int volumeAt(int x, int y, int z) {
        int r = runAt(x, y, z);
        return r < 0 ? -1 : find(r);
    }

    /** The volume of a root as it stands now (null: not a root of this index). */
    public synchronized Volume volume(int root) {
        if (root < 0 || root >= next || find(root) != root) return null;
        return new Volume(root, cells[root], minY[root], maxY[root], java.util.Collections.unmodifiableSet(new LinkedHashSet<>(credits.getOrDefault(root, Set.of()))));
    }

    /** The volume is a known cave already (this session's credits, or a known cave of Sightings matched to it). */
    public synchronized boolean isKnown(int root) { return root >= 0 && root < next && known.contains(find(root)); }

    /** A known cave of Sightings lies in the volume of x y z: that volume is known (a reload keeps it known). */
    public synchronized void markKnown(int x, int y, int z) {
        int r = near(x, y, z);
        if (r >= 0) known.add(find(r));
    }

    /** The run at x y z or within one block of it (the cell itself first), -1 none: where a ray's end credits. */
    private int near(int x, int y, int z) {
        int r = runAt(x, y, z);
        for (int dx = -1; r < 0 && dx <= 1; dx++)
            for (int dy = -1; r < 0 && dy <= 1; dy++)
                for (int dz = -1; r < 0 && dz <= 1; dz++) r = runAt(x + dx, y + dy, z + dz);
        return r;
    }

    /**
     * A sight ray ended at x y z: the volume it ends in, or within one block of (a face or an edge away; the cell itself
     * first), gets the cell as a credit. At {@link #KNOWN_RAYS} distinct credited cells the volume becomes a known cave
     * (its entry, cells, depth and hits in the answer); a known one stays known (ALREADY_KNOWN).
     */
    public synchronized Result credit(int x, int y, int z) {
        int r = near(x, y, z);
        if (r < 0) return new Result(Credit.NO_VOLUME, -1, null);
        int root = find(r);
        if (known.contains(root)) return new Result(Credit.ALREADY_KNOWN, root, null);
        Set<Long> c = credits.computeIfAbsent(root, k -> new LinkedHashSet<>());
        c.add(cell(x, y, z));
        if (c.size() < KNOWN_RAYS) return new Result(Credit.CREDITED, root, null);
        known.add(root);
        int[] entry = entry(root, c);
        return new Result(Credit.KNOWN, root, new Known(entry[0], entry[1], entry[2], cells[root], minY[root], maxY[root], List.copyOf(c)));
    }

    /**
     * The volume's entry: of its cells that touch a sky-lit column sideways (a neighbour column's surface at or below the
     * cell's Y: open air with the sky over it), the one nearest the hit cells (summed squared distance); none touches the
     * sky (a closed volume, or its opening in a chunk not read): the hit cell nearest the volume's top.
     */
    private int[] entry(int root, Set<Long> hits) {
        List<int[]> hs = new ArrayList<>();
        for (long h : hits) hs.add(Sightings.cellOf(h));
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (Chunk ch : chunks.values())
            for (int c = 0; c < 256; c++)
                for (int i = 0; i < ch.lo[c].length; i++) {
                    if (find(ch.id[c][i]) != root) continue;
                    int x = (ch.cx << 4) + (c & 15), z = (ch.cz << 4) + (c >> 4);
                    int skyFrom = Integer.MAX_VALUE;
                    for (int[] d : SIDES) skyFrom = Math.min(skyFrom, surfaceAt(x + d[0], z + d[1]));
                    if (skyFrom == Integer.MAX_VALUE || skyFrom > ch.hi[c][i]) continue;
                    int y = Math.max(ch.lo[c][i], skyFrom);
                    double s = 0;
                    for (int[] h : hs) s += (double) (x - h[0]) * (x - h[0]) + (double) (y - h[1]) * (y - h[1]) + (double) (z - h[2]) * (z - h[2]);
                    if (s < bestD) { bestD = s; best = new int[]{x, y, z}; }
                }
        if (best != null) return best;
        int[] top = hs.get(0);
        for (int[] h : hs) if (h[1] > top[1]) top = h;
        return top.clone();
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** The surface (WORLD_SURFACE: the first air over the top block) of column x z, MAX_VALUE: its chunk not read. */
    private int surfaceAt(int x, int z) {
        Chunk ch = chunks.get(chunkKey(x >> 4, z >> 4));
        return ch == null ? Integer.MAX_VALUE : ch.surface[(x & 15) + 16 * (z & 15)];
    }

    /**
     * The rays' targets near the eyes: per volume run within {@code r} (horizontal) of x z, not known, the run's cell
     * nearest the eye's Y; nearest first, at most {@code n}. {@code ok}: a cell's further test (the view cone, a recent
     * blocked ray).
     */
    public synchronized List<int[]> targets(int x, int y, int z, int r, int n, CellTest ok) {
        List<int[]> out = new ArrayList<>();
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++)
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
                Chunk ch = chunks.get(chunkKey(cx, cz));
                if (ch == null) continue;
                for (int c = 0; c < 256; c++)
                    for (int i = 0; i < ch.lo[c].length; i++) {
                        if (known.contains(find(ch.id[c][i]))) continue;
                        int tx = (cx << 4) + (c & 15), tz = (cz << 4) + (c >> 4);
                        if (Math.abs(tx - x) > r || Math.abs(tz - z) > r) continue;
                        int ty = Math.max(ch.lo[c][i], Math.min(ch.hi[c][i], y));
                        if (ok != null && !ok.test(tx, ty, tz)) continue;
                        out.add(new int[]{tx, ty, tz});
                    }
            }
        out.sort((a, b) -> Long.compare(d2(a, x, y, z), d2(b, x, y, z)));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /**
     * The openings near x y z: per volume not known yet, its cell nearest x y z that touches a sky-lit column sideways (where
     * a player sees a hole in a slope or a cliff side), within {@code r} (horizontal); nearest first. The explore for ore
     * walks toward these - the rays there decide what is a cave (they replaced the heightmap's candidates).
     */
    public synchronized List<int[]> openings(int x, int y, int z, int r) {
        Map<Integer, int[]> best = new HashMap<>();
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++)
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
                Chunk ch = chunks.get(chunkKey(cx, cz));
                if (ch == null) continue;
                for (int c = 0; c < 256; c++) {
                    int tx = (cx << 4) + (c & 15), tz = (cz << 4) + (c >> 4);
                    if (Math.abs(tx - x) > r || Math.abs(tz - z) > r) continue;
                    int skyFrom = Integer.MAX_VALUE;
                    for (int[] d : SIDES) skyFrom = Math.min(skyFrom, surfaceAt(tx + d[0], tz + d[1]));
                    for (int i = 0; i < ch.lo[c].length; i++) {
                        if (skyFrom == Integer.MAX_VALUE || skyFrom > ch.hi[c][i]) continue;
                        int root = find(ch.id[c][i]);
                        if (known.contains(root)) continue;
                        int[] cell = {tx, Math.max(ch.lo[c][i], skyFrom), tz};
                        int[] was = best.get(root);
                        if (was == null || d2(cell, x, y, z) < d2(was, x, y, z)) best.put(root, cell);
                    }
                }
            }
        List<int[]> out = new ArrayList<>(best.values());
        out.sort((a, b) -> Long.compare(d2(a, x, y, z), d2(b, x, y, z)));
        return out;
    }

    @FunctionalInterface
    public interface CellTest { boolean test(int x, int y, int z); }

    private static long d2(int[] a, int x, int y, int z) {
        long dx = a[0] - x, dy = a[1] - y, dz = a[2] - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** The volumes credited and not known yet (aa cave list): root -> its credited cells ({@link #cell} packed). */
    public synchronized Map<Integer, List<Long>> credited() {
        Map<Integer, List<Long>> out = new java.util.TreeMap<>();
        for (var e : credits.entrySet()) if (find(e.getKey()) == e.getKey() && !known.contains(e.getKey())) out.put(e.getKey(), List.copyOf(e.getValue()));
        return out;
    }

    /**
     * One chunk's runs from its blocks (the adapter reads them): {@code air} answers a cell, {@code surface} the 256
     * columns' WORLD_SURFACE heights, {@code minY} the world's bottom. Pure.
     */
    public static ChunkRuns runs(int cx, int cz, int[] surface, int minY, CellTest air) {
        int[][][] out = new int[256][][];
        for (int c = 0; c < 256; c++) {
            int x = (cx << 4) + (c & 15), z = (cz << 4) + (c >> 4);
            List<int[]> col = new ArrayList<>();
            int from = Integer.MIN_VALUE;
            for (int y = minY; y < surface[c]; y++) {
                boolean a = air.test(x, y, z);
                if (a && from == Integer.MIN_VALUE) from = y;
                if (!a && from != Integer.MIN_VALUE) { col.add(new int[]{from, y - 1}); from = Integer.MIN_VALUE; }
            }
            if (from != Integer.MIN_VALUE) col.add(new int[]{from, surface[c] - 1});
            out[c] = col.toArray(new int[0][]);
        }
        return new ChunkRuns(cx, cz, surface.clone(), out);
    }
}
