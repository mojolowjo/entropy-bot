package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.39.z78 J6 (owner's ruling 2026-10-27 late, docs/CAVE_DETECTION.md; to-do/j6-caves-by-rays.md): caves are known by
 * rays, the way ore is. This is the cave INDEX of one dimension: the connected air volumes below the WORLD_SURFACE
 * heightmap of the LOADED chunks, merged across chunks (union-find). It decides where the rays look, never a fact by
 * itself (rule 17):
 * <ul>
 * <li>A ray credits a volume only when it passes THROUGH that volume's air before it stops ({@link #creditRay}: the last
 * index cell on its path, the hole's air and its rim seen through the opening); a ray stopped by rock in front of air it
 * never reached credits nothing (review of 3317a342: no credit "within a block" of hidden air).</li>
 * <li>At {@link #KNOWN_RAYS} distinct credited cells the volume is a known cave ({@link Known}): its ENTRY is a cell the
 * rays saw (the credited cell nearest its column's surface), its SIZE a {@link Size} bucket and its DEPTH a {@link Depth}
 * band from the index (the owner's ruling: "its entry, its size and its depth") - never the index's cells or ore.</li>
 * <li>An opening becomes an explore target only with {@link #TARGET_RAYS} credits ({@link #openings}: a hole the bot has
 * seen; the manager's ruling on J6 (a)). Run EE (2236 rays, 0 credits, no "toward cave opening" line: a ray from afar to
 * under-ground air ends on the ground first - chicken and egg) - the manager's ruling: a RIM credit ({@link #rimCredit})
 * also makes it a target: a ray that ends on a solid block beside an opening cell (an air cell of a cave beside a
 * sky-lit column) saw the lip of the hole, as a player sees it across a valley; the rays aim at those lips too (the roof
 * over each opening column, {@link #targets}). Only the AIR credits make it known.</li>
 * <li>Run ED (21:38-21:56, 189 of 209 known caves "SMALL, HIGH" at y 64-79: air under tree crowns and ledges): a volume
 * is a cave only by the owner's definition ("most caves have a few air blocks at the opening which continue downwards";
 * {@link #isCave}): one of its openings ({@link Opening}: a connected set of its cells beside a sky-lit column, run EF:
 * each measured on its own) holds {@link #MIN_RIM} cells, and its lowest cell lies {@link #MIN_REACH} blocks below that
 * opening's lowest cell; that opening is its entry (the largest, when several pass). A volume with no opening is no cave. The rest are dips:
 * never credited, never a ray's target, never an opening. The surface the runs lie under is the ground ({@link #ground}: leaves are no roof), so the air under a
 * crown is not indexed at all.</li>
 * </ul>
 * <p>Memory: per chunk the columns' air runs below the surface, flattened (one start offset a column, then from / to / run
 * id a run). Measured ({@link #approxBytes}, J6CaveIndexTest): 1072 bytes a chunk with no cave air (its surface heights;
 * the run arrays shared), 2820 bytes a chunk of 40 runs; 16 bytes a run more, plus 28 bytes a run in the union-find - a
 * chunk of 300 runs about 15 kB, 625 chunks (render distance 12) about 9.5 MB. The index keeps the loaded
 * chunks only ({@link #remove} on unload drops them and their unknown volumes; the known ones stay in Sightings), and
 * {@link #MAX_CHUNKS} bounds it at the largest render distance. The union-find is rebuilt from the chunks held after a
 * removal (lazily, on the next ask); the credits and the known marks are kept by cell, so they follow the rebuild. A chunk
 * is indexed once while it stays loaded (the bot's own tunnels dug later do not join it). No knob. Pure (JUnit:
 * J6CaveIndexTest). Loader notes: none (ChunkPass reads the blocks and gives the runs).
 */
public final class CaveIndex {
    /** The distinct sight rays (their credited cells) that make a volume a known cave. */
    public static final int KNOWN_RAYS = 3; // structural: the owner's ruling, 2026-10-27
    /**
     * The sight rays (credited cells) that make a volume's opening an explore target (the manager's ruling on J6 (a),
     * 2026-10-27: a hole the bot has seen, as a player sees a hole in a hillside; none: not a target - walking toward an
     * opening no ray reached would be x-ray by proxy, rule 17).
     */
    public static final int TARGET_RAYS = 1; // structural: the manager's ruling, 2026-10-27
    /** structural: the chunks a client can hold at the largest render distance, 32: (2 x 32 + 1)^2 - a guard, never reached with unloads. */
    static final int MAX_CHUNKS = 65 * 65;
    /**
     * Run ED, the owner's definition (a), the DEFAULT until the owner's labels derive the floor ({@link CaveLabels#derive}):
     * the air cells a cave's opening holds at least (its cells beside a sky-lit
     * column, one opening's - a connected set; run EF: never summed over the volume). The cave carver's tunnel is 2+ cells wide (configured_carver/cave.json and
     * CaveWorldCarver, 1.21: horizontal radius (1.5 + sin x thickness) x 0.7..1.4, so at least 1.5 x 0.7 = 1.05 a side;
     * vertical x 0.8..1.3) - where it breaks the surface its face shows more than two cells; a one-block hole or a
     * two-cell dip shows fewer.
     */
    public static final int MIN_RIM = 3; // structural: a carver tunnel's face, 2+ wide (the manager's ruling)
    /**
     * Run ED, the owner's definition (b), the DEFAULT until the labels derive it: the blocks a cave's air goes down below its opening's lowest cell. A carver
     * tunnel's radius is 1.5 x 0.7..1.4 (cave.json; the tunnel about 3 across), so the air that continues downward past
     * the mouth reaches at least 5 below it (the manager's ruling). An overhang or the air under a ledge goes no lower than
     * the ground at its mouth (0).
     */
    public static final int MIN_REACH = 5; // structural: a carver tunnel of ~3 past its mouth (the manager's ruling)

    /**
     * 0.39.z78 J6: a known cave's size, a bucket from the index's cells by the minutes the caving step walks them (cells /
     * {@link #CROSS_SECTION} a block walked / the walk's speed): SMALL under one minute, LARGE at least cave.exploreMinutes
     * (the caving way's own cap), MEDIUM between. {@link #holdsMinutes} is what the planner reads: SMALL 1 (its upper edge),
     * MEDIUM the middle of its range, LARGE the cap. Edges from the walk's speed and the cap (facts, no knob).
     */
    public enum Size {
        SMALL, MEDIUM, LARGE;

        public static Size of(long cells, double walkBps, double capMinutes) {
            double minutes = cells / (double) CROSS_SECTION / walkBps / 60.0;     // structural: seconds a minute
            return minutes < 1 ? SMALL : minutes >= capMinutes ? LARGE : MEDIUM;   // structural: one minute, the first edge
        }

        public double holdsMinutes(double capMinutes) {
            return switch (this) {
                case SMALL -> 1;                                                 // structural: its upper edge, one minute
                case MEDIUM -> (1 + capMinutes) / 2;                             // structural: the middle of its range
                case LARGE -> Double.POSITIVE_INFINITY;                          // run EP: at least the cap - no bound known (the cap is the caving step's leg)
            };
        }
    }

    /** structural (rough, rule 14): the cells of a cave passed a block walked through it - a tunnel two high and two wide. */
    public static final int CROSS_SECTION = 4;

    /**
     * 0.39.z78 J6: a known cave's depth, a band from the index's lowest cell by the overworld's layers (game facts, the
     * noise settings): DEEPSLATE below y 0 (deepslate replaces stone there), STONE from 0 up to sea level, HIGH from sea
     * level (63) up (a hillside cave).
     */
    public enum Depth {
        DEEPSLATE, STONE, HIGH;

        public static Depth of(int minY) {
            return minY < 0 ? DEEPSLATE : minY < 63 ? STONE : HIGH;           // game fact: deepslate below y 0, sea level 63
        }

        /** J6b: the band holds some y of lo..hi (DEEPSLATE: under 0; STONE: 0..62; HIGH: 63 up - the edges of {@link #of}). */
        public boolean overlaps(int lo, int hi) {
            return switch (this) {
                case DEEPSLATE -> lo < 0;
                case STONE -> lo <= 62 && hi >= 0;                            // game fact: 0 up to under sea level 63
                case HIGH -> hi >= 63;                                         // game fact: sea level 63
            };
        }
    }

    /** One chunk's columns (x + 16 z): the air runs below the surface, each {from, to} (inclusive), lowest first. */
    public record ChunkRuns(int cx, int cz, int[] surface, int[][][] runs) {}

    /** A volume as the rays see it (the index's own view: never a planner fact). */
    public record Volume(int root, long cells, int minY, int maxY, Set<Long> credits) {}

    /** What a credit did: no volume there, credited, the volume known already, or made it a known cave now. */
    public enum Credit { NO_VOLUME, DIP, CREDITED, ALREADY_KNOWN, KNOWN, RIM }

    /** The known cave a credit made: its entry (a seen cell), its size bucket and depth band, the credited (seen) cells. */
    public record Known(int x, int y, int z, Size size, Depth depth, List<Long> hits) {}

    /** A credit's answer: what it did, the volume's root (-1 none), the known cave when this credit made it. */
    public record Result(Credit what, int root, Known known) {}

    private static final int[] NONE = new int[0];
    private static final int[] NO_COLUMNS = new int[257];

    private static final class Chunk {
        final int cx, cz;
        final int[] surface;        // 256
        final int[] start;          // 257: the runs of column c are [start[c], start[c+1])
        final int[] lo, hi;         // per run
        int[] id;                   // per run: its union-find node (rebuilt)
        final int[] rim;            // per run: its cells beside a sky-lit column, counted so far (run ED)
        Chunk(int cx, int cz, int[] surface, int[] start, int[] lo, int[] hi) { this.cx = cx; this.cz = cz; this.surface = surface; this.start = start; this.lo = lo; this.hi = hi; this.id = lo.length == 0 ? NONE : new int[lo.length]; this.rim = lo.length == 0 ? NONE : new int[lo.length]; }
    }

    private final Map<Long, Chunk> chunks = new HashMap<>();
    /** The floor in force (the owner's labels derive it, CaveLabels.derive; the defaults MIN_RIM / MIN_REACH until then). */
    private int floorRim = MIN_RIM, floorReach = MIN_REACH;

    /** The floor derived from the owner's labels ({@link CaveLabels#derive}); a cave is what passes it. */
    public synchronized void setFloor(int rim, int reach) {
        floorRim = rim;
        floorReach = reach;
    }

    public synchronized int[] floor() { return new int[]{floorRim, floorReach}; }
    private int[] parent = new int[1024];
    private long[] cells = new long[1024];
    private int[] minY = new int[1024], maxY = new int[1024];
    /** Per root (run ED): the lowest opening cell (beside a sky-lit column; MAX_VALUE: none) and the opening's cells. */
    private int[] rimLow = new int[1024], rimN = new int[1024];
    private int next;
    private boolean dirty;
    /** The credited cells (packed) and the cells of known caves: kept by cell, mapped to volumes at each ask. */
    private final Set<Long> credited = new LinkedHashSet<>(), knownCells = new HashSet<>();
    /** Run EE: the opening cells a ray's end block lay beside (rim credits, by cell; mapped to volumes at each ask). */
    private final Set<Long> rimSeen = new LinkedHashSet<>();
    /** Volume root -> its credited cells, and the known roots: derived (rebuilt with the union-find). */
    private final Map<Integer, Set<Long>> credits = new HashMap<>();
    private final Set<Integer> known = new HashSet<>();

    public static long chunkKey(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    public static long cell(int x, int y, int z) { return Sightings.cell(x, y, z); }

    public synchronized int chunks() { return chunks.size(); }

    public synchronized boolean has(int cx, int cz) { return chunks.containsKey(chunkKey(cx, cz)); }

    /** A new world or dimension: nothing indexed, no credit. */
    public synchronized void clear() {
        chunks.clear();
        credited.clear();
        rimSeen.clear();
        mergedKnown.clear();
        knownCells.clear();
        credits.clear();
        known.clear();
        next = 0;
        dirty = false;
        openingsAll = List.of();
        byRoot = new HashMap<>();
        openingOf = new HashMap<>();
        shapeDirty = rootsDirty = true;
    }

    /** The bytes the index holds (approx: the arrays' payloads and headers, the union-find's arrays), for the memory statement. */
    public synchronized long approxBytes() {
        long b = 0;
        for (Chunk c : chunks.values()) {
            b += 16 + 4L * 256;                                                   // structural: the surface array
            b += c.start == NO_COLUMNS ? 0 : 16 + 4L * 257;                      // structural: the column starts
            b += c.lo.length == 0 ? 0 : 4 * (16 + 4L * c.lo.length);            // structural: from, to, id, rim
            b += 32;                                                              // structural: the chunk object
        }
        return b + 28L * parent.length;                                           // structural: the union-find's six arrays, 28 bytes a node
    }

    /** A chunk's runs (indexed once while it stays loaded: a chunk held is kept as it was). */
    public synchronized void put(ChunkRuns r) {
        long k = chunkKey(r.cx(), r.cz());
        if (chunks.containsKey(k)) return;
        if (chunks.size() >= MAX_CHUNKS) { chunks.clear(); dirty = true; }    // the guard: start over (the known marks stay)
        int n = 0;
        for (int c = 0; c < 256; c++) n += r.runs()[c] == null ? 0 : r.runs()[c].length;
        int[] start = n == 0 ? NO_COLUMNS : new int[257], lo = n == 0 ? NONE : new int[n], hi = n == 0 ? NONE : new int[n];
        int at = 0;
        for (int c = 0; c < 256; c++) {
            if (n > 0) start[c] = at;
            int[][] col = r.runs()[c] == null ? new int[0][] : r.runs()[c];
            for (int[] run : col) { lo[at] = run[0]; hi[at] = run[1]; at++; }
        }
        if (n > 0) start[256] = at;
        Chunk ch = new Chunk(r.cx(), r.cz(), r.surface().clone(), start, lo, hi);
        chunks.put(k, ch);
        shapeDirty = rootsDirty = true;
        version++;
        if (dirty) return;                                                        // the rebuild joins it
        link(ch);
    }

    /** The chunk unloaded: its runs leave the index (its unknown volumes are gone with it; a known cave stays in Sightings). */
    public synchronized void remove(int cx, int cz) {
        if (chunks.remove(chunkKey(cx, cz)) != null) { dirty = true; shapeDirty = rootsDirty = true; version++; }
    }

    private void link(Chunk ch) {
        for (int i = 0; i < ch.lo.length; i++) ch.id[i] = node(ch.lo[i], ch.hi[i]);
        if (ch.lo.length == 0) return;
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                if (lx < 15) join(ch, lx, lz, ch, lx + 1, lz);
                if (lz < 15) join(ch, lx, lz, ch, lx, lz + 1);
            }
        Chunk w = chunks.get(chunkKey(ch.cx - 1, ch.cz)), e = chunks.get(chunkKey(ch.cx + 1, ch.cz));
        Chunk n = chunks.get(chunkKey(ch.cx, ch.cz - 1)), s = chunks.get(chunkKey(ch.cx, ch.cz + 1));
        for (int i = 0; i < 16; i++) {
            if (w != null) join(ch, 0, i, w, 15, i);
            if (e != null) join(ch, 15, i, e, 0, i);
            if (n != null) join(ch, i, 0, n, i, 15);
            if (s != null) join(ch, i, 15, s, i, 0);
        }
        for (int c = 0; c < 256; c++) opened(ch, c);
        for (int i = 0; i < 16; i++) {                                           // the neighbours' edge columns see this chunk's sky now
            if (w != null) opened(w, 15 + 16 * i);
            if (e != null) opened(e, 16 * i);
            if (n != null) opened(n, i + 16 * 15);
            if (s != null) opened(s, i);
        }
        for (long c : knownCells) { int r = runAt(c); if (r >= 0) known.add(find(r)); }     // a known cave marked before its chunk came
        for (long c : credited) {                                                // run EG: the credits of cells in this chunk (a reload) find their volume again
            int[] p = Sightings.cellOf(c);
            if (p[0] >> 4 != ch.cx || p[2] >> 4 != ch.cz) continue;
            int r = runAt(c);
            if (r >= 0) credits.computeIfAbsent(find(r), x -> new LinkedHashSet<>()).add(c);
        }
        mergedCheck();
    }

    /** After a removal: the union-find again from the chunks held; credits and known marks by their cells (lazily). */
    private void fresh() {
        if (!dirty) return;
        dirty = false;
        next = 0;
        credits.clear();
        known.clear();
        List<Chunk> all = new ArrayList<>(chunks.values());
        for (Chunk ch : all) for (int i = 0; i < ch.lo.length; i++) ch.id[i] = node(ch.lo[i], ch.hi[i]);
        for (Chunk ch : all) {
            if (ch.lo.length == 0) continue;
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++) {
                    if (lx < 15) join(ch, lx, lz, ch, lx + 1, lz);
                    if (lz < 15) join(ch, lx, lz, ch, lx, lz + 1);
                }
            Chunk e = chunks.get(chunkKey(ch.cx + 1, ch.cz)), s = chunks.get(chunkKey(ch.cx, ch.cz + 1));
            for (int i = 0; i < 16; i++) {
                if (e != null) join(ch, 15, i, e, 0, i);
                if (s != null) join(ch, i, 15, s, i, 0);
            }
        }
        for (Chunk ch : all) java.util.Arrays.fill(ch.rim, 0);
        for (Chunk ch : all) for (int c = 0; c < 256; c++) opened(ch, c);
        // run EG (22:52-22:54: known caves showed "rim 0, air 0" after the owner's teleports): an unloaded UNKNOWN volume's
        // credits go with it; a known cave's credited cells (knownCells) stay and find their volume when its chunk returns
        credited.removeIf(c -> runAt(c) < 0 && !knownCells.contains(c));
        rimSeen.removeIf(c -> runAt(c) < 0 && !knownCells.contains(c));
        for (long c : credited) { int r = runAt(c); if (r >= 0) credits.computeIfAbsent(find(r), x -> new LinkedHashSet<>()).add(c); }
        mergedCheck();
        for (long c : knownCells) { int r = runAt(c); if (r >= 0) known.add(find(r)); }
    }

    private int node(int from, int to) {
        if (next == parent.length) {
            int len = parent.length * 2;
            parent = java.util.Arrays.copyOf(parent, len);
            cells = java.util.Arrays.copyOf(cells, len);
            minY = java.util.Arrays.copyOf(minY, len);
            maxY = java.util.Arrays.copyOf(maxY, len);
            rimLow = java.util.Arrays.copyOf(rimLow, len);
            rimN = java.util.Arrays.copyOf(rimN, len);
        }
        int i = next++;
        parent[i] = i;
        cells[i] = to - from + 1L;
        minY[i] = from;
        maxY[i] = to;
        rimLow[i] = Integer.MAX_VALUE;
        rimN[i] = 0;
        return i;
    }

    private int find(int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Two runs of one air body: one volume. A known part makes the whole known (the same connected air), its credits joined. */
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        if (cells[ra] < cells[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        cells[ra] += cells[rb];
        minY[ra] = Math.min(minY[ra], minY[rb]);
        maxY[ra] = Math.max(maxY[ra], maxY[rb]);
        rimLow[ra] = Math.min(rimLow[ra], rimLow[rb]);
        rimN[ra] += rimN[rb];
        Set<Long> cb = credits.remove(rb);
        if (cb != null) credits.computeIfAbsent(ra, k -> new LinkedHashSet<>()).addAll(cb);
        if (known.remove(rb)) known.add(ra);
        // run EG: two credited volumes joined - their credits are one set now; at KNOWN_RAYS the volume is known
        // ({@link #drainKnown}: the caller stores the fact)
        Set<Long> cr = credits.get(ra);
        if (cr != null && cr.size() >= KNOWN_RAYS && !known.contains(ra)) mergedKnown.add(cr.iterator().next());
    }

    /** The runs of two side-by-side columns that share a Y are one volume. */
    private void join(Chunk a, int ax, int az, Chunk b, int bx, int bz) {
        int ca = ax + 16 * az, cb = bx + 16 * bz;
        int i = a.start[ca], ie = a.start[ca + 1], j = b.start[cb], je = b.start[cb + 1];
        while (i < ie && j < je) {
            if (a.lo[i] <= b.hi[j] && b.lo[j] <= a.hi[i]) union(a.id[i], b.id[j]);
            if (a.hi[i] < b.hi[j]) i++;
            else j++;
        }
    }

    /** The runs of column c of the chunk beside a sky-lit column: their opening cells join their volume's rim (once each). */
    private void opened(Chunk ch, int c) {
        if (ch.lo.length == 0 || ch.start[c] == ch.start[c + 1]) return;
        int tx = (ch.cx << 4) + (c & 15), tz = (ch.cz << 4) + (c >> 4);
        int sky = Integer.MAX_VALUE;
        for (int[] d : SIDES) sky = Math.min(sky, surfaceAt(tx + d[0], tz + d[1]));
        for (int i = ch.start[c]; i < ch.start[c + 1]; i++)
            if (sky <= ch.hi[i]) {
                int from = Math.max(ch.lo[i], sky), n = ch.hi[i] - from + 1;
                int r = find(ch.id[i]);
                rimLow[r] = Math.min(rimLow[r], from);
                if (n > ch.rim[i]) { rimN[r] += n - ch.rim[i]; ch.rim[i] = n; }
            }
    }

    /**
     * Run ED, the owner's definition (run EF: per opening): the volume of {@code root} is a cave - one of its openings holds
     * the floor's rim cells and its air goes the floor's reach below that opening's lowest cell. Not: a dip (never credited, never a target,
     * never an opening). A dip may become a cave when more of it loads (its opening in a chunk not read yet).
     */
    public synchronized boolean isCave(int root) {
        fresh();
        if (root < 0 || root >= next) return false;
        return cave(find(root));
    }

    private boolean cave(int r) {
        measure();
        List<Opening> l = byRoot.get(r);
        if (l == null) return false;
        for (Opening o : l) if (passes(o, r)) return true;
        return false;
    }

    // ---- run EF (the manager's rim fix): each opening measured on its own ----

    /**
     * One opening of a volume: a connected set (six sides) of its air cells beside a sky-lit column ({@link #cell}
     * packed), its lowest cell's y. Its rim is its cell count; its reach how far the volume's air goes below that lowest
     * cell. Was (73ee3a9c..2f215a09): one rim summed over every sky-adjacent cell of the volume (the owner's first label:
     * "rim 4719") and the reach from the lowest of them - three 1-cell holes passed rim 3.
     */
    public record Opening(List<Long> cells, int lowY) {
        public int rim() { return cells.size(); }
    }

    private List<Opening> openingsAll = List.of();
    private Map<Integer, List<Opening>> byRoot = new HashMap<>();
    /** Each opening cell's opening (the last measure). */
    private Map<Long, Opening> openingOf = new HashMap<>();
    private boolean shapeDirty = true, rootsDirty = true;
    private long measuredAt, measureEveryNs;

    /** The openings are measured again at most this often while chunks load (ChunkPass: a second); 0 (tests): at every change. */
    public synchronized void measureEvery(long ns) { measureEveryNs = Math.max(0, ns); }

    private boolean passes(Opening o, int r) { return o.rim() >= floorRim && (long) o.lowY() - minY[r] >= floorReach; }

    /** The volume's opening that decides it: the largest passing one, else the largest (null: no opening). */
    private Opening best(int r) {
        measure();
        List<Opening> l = byRoot.get(r);
        if (l == null) return null;
        for (Opening o : l) if (passes(o, r)) return o;
        return l.get(0);
    }

    private void measure() {
        fresh();
        if (shapeDirty && (measuredAt == 0 || measureEveryNs <= 0 || System.nanoTime() - measuredAt >= measureEveryNs)) remeasure();
        else if (rootsDirty) rekey();
    }

    /** Every opening of the loaded index: the opening cells, then their connected sets. */
    private void remeasure() {
        Set<Long> open = new HashSet<>();
        for (Chunk ch : chunks.values()) {
            if (ch.lo.length == 0) continue;
            for (int c = 0; c < 256; c++) {
                if (ch.start[c] == ch.start[c + 1]) continue;
                int tx = (ch.cx << 4) + (c & 15), tz = (ch.cz << 4) + (c >> 4), s = sky(tx, tz);
                for (int i = ch.start[c]; i < ch.start[c + 1]; i++)
                    for (int y = Math.max(ch.lo[i], s); y <= ch.hi[i]; y++) open.add(cell(tx, y, tz));
            }
        }
        List<Opening> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>();
        for (long start : open) {
            if (!seen.add(start)) continue;
            List<Long> comp = new ArrayList<>();
            int low = Integer.MAX_VALUE;
            q.add(start);
            while (!q.isEmpty()) {
                long c = q.poll();
                comp.add(c);
                int[] p = Sightings.cellOf(c);
                low = Math.min(low, p[1]);
                for (int[] d : SIX) {
                    long n = cell(p[0] + d[0], p[1] + d[1], p[2] + d[2]);
                    if (open.contains(n) && seen.add(n)) q.add(n);
                }
            }
            out.add(new Opening(List.copyOf(comp), low));
        }
        Map<Long, Opening> of = new HashMap<>();
        for (Opening o : out) for (long c : o.cells()) of.put(c, o);
        openingsAll = out;
        openingOf = of;
        measuredAt = Math.max(1, System.nanoTime());
        shapeDirty = false;
        rekey();
    }

    /** The openings by their volume's root now (an opening whose cells left the index is dropped). */
    private void rekey() {
        Map<Integer, List<Opening>> m = new HashMap<>();
        for (Opening o : openingsAll) {
            int r = runAt(o.cells().get(0));
            if (r >= 0) m.computeIfAbsent(find(r), k -> new ArrayList<>()).add(o);
        }
        for (List<Opening> l : m.values()) l.sort((a, b) -> Integer.compare(b.rim(), a.rim()));
        byRoot = m;
        rootsDirty = false;
    }

    /** The cell of {@code o} nearest x y z. */
    private static int[] nearest(Opening o, double x, double y, double z) {
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (long c : o.cells()) {
            int[] p = Sightings.cellOf(c);
            double dd = sq(p[0] + 0.5 - x) + sq(p[1] + 0.5 - y) + sq(p[2] + 0.5 - z);
            if (dd < bd) { bd = dd; best = p; }
        }
        return best;
    }

    /** The old measure, for a test: every sky-adjacent cell of the volume summed (no opening: 0). */
    synchronized int summedRim(int root) { fresh(); return root < 0 ? 0 : rimN[find(root)]; }

    private int runAt(long c) { int[] p = Sightings.cellOf(c); return runAt(p[0], p[1], p[2]); }

    /** The run holding x y z exactly, -1 none (not indexed, not a cave cell). */
    private int runAt(int x, int y, int z) {
        Chunk ch = chunks.get(chunkKey(x >> 4, z >> 4));
        if (ch == null || ch.lo.length == 0) return -1;
        int c = (x & 15) + 16 * (z & 15);
        for (int i = ch.start[c]; i < ch.start[c + 1]; i++) if (ch.lo[i] <= y && y <= ch.hi[i]) return ch.id[i];
        return -1;
    }

    private long version;

    /** J6b: bumped at every chunk put or removed (a cave record's cells are refreshed when it moves). */
    public synchronized long version() { return version; }

    /**
     * J6b (D12): the air cells of the volume x y z is in, over the chunks held ({@link #cell} packed); empty: no volume
     * there. 0.39.z78 run EK: a whole scan - JUnit and offline checks only; the records are fed by {@link #feed}.
     */
    synchronized List<Long> volumeCells(int x, int y, int z) {
        fresh();
        int r = runAt(x, y, z);
        if (r < 0) return List.of();
        int root = find(r);
        List<Long> out = new ArrayList<>();
        for (Chunk ch : chunks.values()) {
            if (ch.lo.length == 0) continue;
            for (int c = 0; c < 256; c++)
                for (int i = ch.start[c]; i < ch.start[c + 1]; i++) {
                    if (find(ch.id[i]) != root) continue;
                    int tx = (ch.cx << 4) + (c & 15), tz = (ch.cz << 4) + (c >> 4);
                    for (int yy = ch.lo[i]; yy <= ch.hi[i]; yy++) out.add(cell(tx, yy, tz));
                }
        }
        return out;
    }

    /**
     * 0.39.z78 run EK: a cave record's feed of its volume's cells - where the pass over the chunks held stands (a snapshot
     * of their keys at the pass's start, the chunk and run reached) and, per chunk, how many of its runs were the volume's
     * when last fed (a chunk whose count is unchanged is passed over: its cells are in already).
     */
    public static final class Feed {
        private long fedVersion = -1, passVersion;
        private List<Long> keys;
        private int pos, run = -1, col, pending;
        private boolean onePass;
        private final Map<Long, Integer> runsIn = new HashMap<>();

        /** A whole pass ran to its end at least once (the record is loaded). */
        public boolean complete() { return onePass; }

        /** A pass is running. */
        public boolean filling() { return keys != null; }
    }

    /**
     * 0.39.z78 run EK (the manager's fix; run EK 02:30:20-02:46:19: the whole scan - every run of every chunk and the
     * whole 209,647-cell volume - on every index change, for every known cave): one budgeted step of feeding the volume of
     * x y z into {@code add}, from where {@code f} stands. A pass starts when the index changed since the last one; each
     * chunk's runs of the volume are counted, and fed only when the count changed (a chunk new to the volume, or a volume
     * joined to it). Returns the runs and cells touched ({@code budget} at most, past it by one chunk's run count or one
     * run). The entry's chunk not held: nothing fed, the pass ends (not loaded).
     */
    public synchronized int feed(int x, int y, int z, Feed f, int budget, java.util.function.LongConsumer add) {
        if (f.keys == null) {
            if (f.fedVersion == version) return 0;
            f.keys = new ArrayList<>(chunks.keySet());
            f.pos = 0;
            f.run = -1;
            f.passVersion = version;
        }
        fresh();
        int ops = 1;
        int r = runAt(x, y, z);
        if (r < 0) { f.keys = null; f.fedVersion = f.passVersion; return ops; }
        int root = find(r);
        while (f.pos < f.keys.size() && ops < budget) {
            long key = f.keys.get(f.pos);
            Chunk ch = chunks.get(key);
            if (ch == null || ch.lo.length == 0) { f.pos++; f.run = -1; ops++; continue; }
            if (f.run < 0) {
                int n = 0;
                for (int i = 0; i < ch.lo.length; i++) if (find(ch.id[i]) == root) n++;
                ops += ch.lo.length;
                Integer was = f.runsIn.get(key);
                if (n == 0 || (was != null && was == n)) { f.pos++; continue; }
                f.pending = n;
                f.run = 0;
                f.col = 0;
            }
            while (f.run < ch.lo.length && ops < budget) {
                int i = f.run;
                while (ch.start[f.col + 1] <= i) f.col++;
                ops++;
                if (find(ch.id[i]) == root) {
                    int tx = (ch.cx << 4) + (f.col & 15), tz = (ch.cz << 4) + (f.col >> 4);
                    for (int yy = ch.lo[i]; yy <= ch.hi[i]; yy++) add.accept(cell(tx, yy, tz));
                    ops += ch.hi[i] - ch.lo[i] + 1;
                }
                f.run++;
            }
            if (f.run >= ch.lo.length) { f.runsIn.put(key, f.pending); f.pos++; f.run = -1; }
        }
        if (f.pos >= f.keys.size()) { f.keys = null; f.fedVersion = f.passVersion; f.onePass = true; }
        return ops;
    }

    /** The volume x y z is in (its root), -1 none. */
    public synchronized int volumeAt(int x, int y, int z) {
        fresh();
        int r = runAt(x, y, z);
        return r < 0 ? -1 : find(r);
    }

    /** The volume of a root as it stands now (null: not a root of this index). */
    public synchronized Volume volume(int root) {
        fresh();
        if (root < 0 || root >= next || find(root) != root) return null;
        return new Volume(root, cells[root], minY[root], maxY[root], java.util.Collections.unmodifiableSet(new LinkedHashSet<>(credits.getOrDefault(root, Set.of()))));
    }

    /** The volume is a known cave (this session's credits, or a known cave of Sightings marked in it). */
    public synchronized boolean isKnown(int root) {
        fresh();
        return root >= 0 && root < next && known.contains(find(root));
    }

    /** A known cave of Sightings has the cell x y z (its entry or a seen cell): its volume is known (a reload keeps it known). */
    public synchronized void markKnown(int x, int y, int z) {
        fresh();
        long c = cell(x, y, z);
        knownCells.add(c);
        int r = runAt(x, y, z);
        if (r >= 0) known.add(find(r));
    }

    /**
     * A sight ray's path, the cells it passed in order up to (not including) the block that stopped it, or to its target:
     * the last index cell on it is the air the ray saw deepest in a volume - that cell is credited ({@link #credit}). No
     * index cell on the path (stopped by rock before any cave air, or only open sky): nothing credited (NO_VOLUME).
     */
    public synchronized Result creditRay(List<int[]> path) {
        fresh();
        boolean dip = false;
        for (int i = path.size() - 1; i >= 0; i--) {
            int[] p = path.get(i);
            int r = runAt(p[0], p[1], p[2]);
            if (r < 0) continue;
            if (!cave(find(r))) { dip = true; continue; }                        // run ED: a dip's air is passed over
            return credit(p[0], p[1], p[2]);
        }
        return new Result(dip ? Credit.DIP : Credit.NO_VOLUME, -1, null);
    }

    /**
     * The air cell x y z of a volume was seen (a ray passed it): the cell is a credit. At {@link #KNOWN_RAYS} distinct
     * credited cells the volume becomes a known cave; a known one stays known (ALREADY_KNOWN). A cell that is no index
     * cell credits nothing (NO_VOLUME): no credit for rock beside air (rule 17).
     */
    public synchronized Result credit(int x, int y, int z) {
        fresh();
        int r = runAt(x, y, z);
        if (r < 0) return new Result(Credit.NO_VOLUME, -1, null);
        int root = find(r);
        if (known.contains(root)) return new Result(Credit.ALREADY_KNOWN, root, null);
        if (!cave(root)) return new Result(Credit.DIP, root, null);              // run ED: no floor, no cave
        long c = cell(x, y, z);
        credited.add(c);
        Set<Long> cs = credits.computeIfAbsent(root, k -> new LinkedHashSet<>());
        cs.add(c);
        if (cs.size() < KNOWN_RAYS) return new Result(Credit.CREDITED, root, null);
        return new Result(Credit.KNOWN, root, makeKnown(root));
    }

    /** Job-caveair: what a tunnel's air hand-over found - the credit, the volume's cells (0: no volume), the known cave made (null: none). */
    public record Lip(Credit what, long cells, Known known) {}

    /**
     * Job-caveair (run JBA 08:17:12: a tunnel at y 16 broke into air at -127 12 -37 that the index never credited - a
     * sealed volume has no sky opening, so {@link #cave} is false and {@link #credit} answers DIP): the tunnel stood beside
     * the air cell x y z and saw it, so the tunnel is the volume's lip. A volume of at least {@code minCells} cells
     * (Tuning tunnel.caveMinCells) becomes a known cave at once, entered at that cell (KNOWN); fewer is a pocket (DIP, its
     * cells counted); a known volume is ALREADY_KNOWN; no index cell there is NO_VOLUME (not known, rule 15).
     */
    public synchronized Lip lipCredit(int x, int y, int z, long minCells) {
        fresh();
        int r = runAt(x, y, z);
        if (r < 0) return new Lip(Credit.NO_VOLUME, 0, null);
        int root = find(r);
        if (known.contains(root)) return new Lip(Credit.ALREADY_KNOWN, cells[root], null);
        if (cells[root] < minCells) return new Lip(Credit.DIP, cells[root], null);
        long c = cell(x, y, z);
        credited.add(c);
        credits.computeIfAbsent(root, k -> new LinkedHashSet<>()).add(c);
        return new Lip(Credit.KNOWN, cells[root], makeKnown(root));
    }

    /** The volume of {@code root} becomes known: its credited cells and its rim credits' cells kept by cell (known stays known). */
    private Known makeKnown(int root) {
        Set<Long> cs = credits.get(root);
        known.add(root);
        knownCells.addAll(cs);
        for (long c : rimSeen) { int q = runAt(c); if (q >= 0 && find(q) == root) knownCells.add(c); }
        int[] entry = entry(cs);
        double walk = io.github.mojolowjo.entropybot.aa.brain.Unit.walkBps(), cap = io.github.mojolowjo.entropybot.aa.brain.Tuning.get("cave.exploreMinutes");
        return new Known(entry[0], entry[1], entry[2], Size.of(cells[root], walk, cap), Depth.of(minY[root]), List.copyOf(cs));
    }

    /** Run EG: after a rebuild or a chunk's credits found their volume - any volume at KNOWN_RAYS credits not known yet is due. */
    private void mergedCheck() {
        for (var e : credits.entrySet())
            if (e.getValue().size() >= KNOWN_RAYS && !known.contains(e.getKey()) && find(e.getKey()) == e.getKey()) mergedKnown.add(e.getValue().iterator().next());
    }

    /** Run EG: a credited cell of each volume a merge took to KNOWN_RAYS credits (drained by {@link #drainKnown}). */
    private final Set<Long> mergedKnown = new LinkedHashSet<>();

    /**
     * Run EG: the volumes a merge (a chunk load joining two credited volumes) took to {@link #KNOWN_RAYS} credits become
     * known caves now (each a cave by the floor; a dip stays credited, never known); the caller stores each fact
     * (CaveRays: Sightings.knowCave, "sense: cave known").
     */
    public synchronized List<Known> drainKnown() {
        fresh();
        List<Known> out = new ArrayList<>();
        if (mergedKnown.isEmpty()) return out;
        List<Long> todo = new ArrayList<>(mergedKnown);
        mergedKnown.clear();
        for (long c : todo) {
            int r = runAt(c);
            if (r < 0) continue;
            int root = find(r);
            Set<Long> cs = credits.get(root);
            if (known.contains(root) || cs == null || cs.size() < KNOWN_RAYS || !cave(root)) continue;
            out.add(makeKnown(root));
        }
        return out;
    }

    /** The entry: of the seen (credited) cells, the one nearest its column's surface (the way in a player saw). */
    private int[] entry(Set<Long> seen) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (long c : seen) {
            int[] p = Sightings.cellOf(c);
            int s = surfaceAt(p[0], p[2]);
            long d = s == Integer.MAX_VALUE ? Long.MAX_VALUE - 1 : (long) s - p[1];
            if (best == null || d < bestD) { best = p; bestD = d; }
        }
        return best;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** The surface (WORLD_SURFACE: the first air over the top block) of column x z, MAX_VALUE: its chunk not held. */
    /**
     * 0.39.z78 J6c (the owner's ruling 1, 2026-10-28): the index's air answer at a cell - TRUE: air (an indexed air run, or
     * at / above the column's ground: open to the sky); FALSE: not air (a solid block, or a fluid - the runs hold air only,
     * rough, rule 14); null: its chunk is not indexed (not loaded: rule 15, not known). The open face test of the
     * air-exposed blocks (CaveRecord's exposure, through Nearby.exposed).
     */
    public synchronized Boolean airAt(int x, int y, int z) {
        Chunk ch = chunks.get(chunkKey(x >> 4, z >> 4));
        if (ch == null) return null;
        int c = (x & 15) + 16 * (z & 15);
        if (y >= ch.surface[c]) return Boolean.TRUE;
        for (int i = ch.start[c]; i < ch.start[c + 1]; i++) if (y >= ch.lo[i] && y <= ch.hi[i]) return Boolean.TRUE;
        return Boolean.FALSE;
    }

    private int surfaceAt(int x, int z) {
        Chunk ch = chunks.get(chunkKey(x >> 4, z >> 4));
        return ch == null ? Integer.MAX_VALUE : ch.surface[(x & 15) + 16 * (z & 15)];
    }

    /**
     * surface (owner 2026-11-02): the ground of column x z as indexed (the first air over the top block, leaves no roof; a
     * ravine floor's column: its rim, {@link #rimmed}), MAX_VALUE: its chunk not indexed (rule 15). The guard's depth fact
     * (PolicyRuntime.depthAt) reads it on the pathfinder thread.
     */
    public synchronized int groundAt(int x, int z) { return surfaceAt(x, z); }

    /**
     * surface (owner 2026-11-02: "treating the ravine as a cave mouth makes the most sense"): a column whose ground lies
     * {@code drop} or more below the highest ground within {@code r} columns (inside the chunk: rule 14, rough at the
     * chunk's edge) gets that rim as its ground, so the air between is indexed as cave air and the floor counts as under
     * ground. A cliff's foot within r of its top gets the same. Pure; a new array.
     */
    public static int[] rimmed(int[] ground, int drop, int r) {
        int[] out = ground.clone();
        if (drop <= 0 || r <= 0) return out;
        for (int c = 0; c < 256; c++) {
            int x = c & 15, z = c >> 4, rim = ground[c];
            for (int dz = Math.max(0, z - r); dz <= Math.min(15, z + r); dz++)
                for (int dx = Math.max(0, x - r); dx <= Math.min(15, x + r); dx++) rim = Math.max(rim, ground[dx + 16 * dz]);
            if (rim - ground[c] >= drop) out[c] = rim;
        }
        return out;
    }

    /**
     * The rays' targets near the eyes: per run within {@code r} (horizontal) of x z of a cave not known, the run's cell
     * nearest the eye's Y, and for a run with opening cells of a cave not rim-credited yet the roof block over them (its
     * lip, run EE); nearest first, at most {@code n}. {@code ok}: a cell's further test (the view cone, a recent
     * blocked ray). Where to LOOK, never where to go (rule 17).
     */
    public synchronized List<int[]> targets(int x, int y, int z, int r, int n, CellTest ok) {
        fresh();
        Set<Opening> rimOps = rimOpenings();
        List<int[]> out = new ArrayList<>();
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++)
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
                Chunk ch = chunks.get(chunkKey(cx, cz));
                if (ch == null || ch.lo.length == 0) continue;
                for (int c = 0; c < 256; c++)
                    for (int i = ch.start[c]; i < ch.start[c + 1]; i++) {
                        int rt = find(ch.id[i]);
                        if (known.contains(rt) || !cave(rt)) continue;                // run ED: no ray at a dip
                        int tx = (cx << 4) + (c & 15), tz = (cz << 4) + (c >> 4);
                        if (Math.abs(tx - x) > r || Math.abs(tz - z) > r) continue;
                        int ty = Math.max(ch.lo[i], Math.min(ch.hi[i], y));
                        if (ok == null || ok.test(tx, ty, tz)) out.add(new int[]{tx, ty, tz});
                        // run EE: the lip of an opening not yet seen - the roof block over the column's opening cells
                        // (solid: the run is maximal and lies under the ground), where a ray from afar ends
                        Opening lipOf = openingOf.get(cell(tx, ch.hi[i], tz));        // the opening under this lip; its own rim credit only (review)
                        if (sky(tx, tz) <= ch.hi[i] && (lipOf == null || !rimOps.contains(lipOf)) && (ok == null || ok.test(tx, ch.hi[i] + 1, tz)))
                            out.add(new int[]{tx, ch.hi[i] + 1, tz});
                    }
            }
        out.sort((a, b) -> Long.compare(d2(a, x, y, z), d2(b, x, y, z)));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /**
     * The openings near x y z (explore targets): each passing opening of a cave not known yet that holds a rim credit
     * itself ({@link #TARGET_RAYS}: a ray ended on THAT opening's lip, {@link #rimCredit}), as its credited cell nearest
     * x y z, within {@code r} (horizontal); nearest first. An opening no ray saw is never a target, even when another
     * opening of its volume was seen (c921e903 review; the manager's ruling on J6 (a): x-ray by proxy, rule 17) - none:
     * the explore explores plainly.
     */
    public synchronized List<int[]> openings(int x, int y, int z, int r) {
        fresh();
        measure();
        // c921e903 review (rule 17, the manager's ruling): a target only at an opening that itself holds a rim credit - a
        // ray ended on ITS lip - and it passes the floor; aimed at its credited cell (the seen air beside the lip). Another
        // passing opening of the same volume that no ray saw is never a target.
        Map<Opening, int[]> best = new java.util.IdentityHashMap<>();
        for (long c : rimSeen) {
            Opening o = openingOf.get(c);
            int q = runAt(c);
            if (o == null || q < 0) continue;
            int root = find(q);
            if (known.contains(root) || !passes(o, root)) continue;
            int[] p = Sightings.cellOf(c);
            if (Math.abs(p[0] - x) > r || Math.abs(p[2] - z) > r) continue;
            int[] was = best.get(o);
            if (was == null || d2(p, x, y, z) < d2(was, x, y, z)) best.put(o, p);
        }
        List<int[]> out = new ArrayList<>(best.values());
        out.sort((a, b) -> Long.compare(d2(a, x, y, z), d2(b, x, y, z)));
        return out;
    }
    /** The lowest surface of the four side columns of x z (MAX_VALUE: none held): the sky a cell of x z touches from y up. */
    private int sky(int x, int z) {
        int s = Integer.MAX_VALUE;
        for (int[] d : SIDES) s = Math.min(s, surfaceAt(x + d[0], z + d[1]));
        return s;
    }

    /** c921e903 review (rule 17): the openings that hold a rim credit themselves (a ray ended on THEIR lip). */
    private Set<Opening> rimOpenings() {
        measure();
        Set<Opening> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (long c : rimSeen) { Opening o = openingOf.get(c); if (o != null) out.add(o); }
        return out;
    }

    /** The volumes with a rim credit (loaded cells only). */
    private Set<Integer> rimRoots() {
        Set<Integer> out = new HashSet<>();
        for (long c : rimSeen) { int r = runAt(c); if (r >= 0) out.add(find(r)); }
        return out;
    }

    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * Run EE (the manager's ruling): a sight ray ended on the solid block x y z. Beside it an opening cell of a cave not
     * known (an air cell of the cave beside a sky-lit column): the ray saw the lip of that hole - a RIM credit, which makes
     * the opening an explore target ({@link #openings}) and never makes the cave known. The opening cell is sky-exposed,
     * not air behind a wall (rule 17). No such cell: NO_VOLUME; the same opening cell again: ALREADY_KNOWN.
     */
    public synchronized Result rimCredit(int x, int y, int z) {
        fresh();
        if (runAt(x, y, z) >= 0) return new Result(Credit.NO_VOLUME, -1, null);   // an air cell is no block
        for (int[] d : SIX) {
            int ax = x + d[0], ay = y + d[1], az = z + d[2];
            int r = runAt(ax, ay, az);
            if (r < 0 || sky(ax, az) > ay) continue;                              // not an opening cell
            int root = find(r);
            if (known.contains(root) || !cave(root)) continue;
            boolean first = rimSeen.add(cell(ax, ay, az));
            return new Result(first ? Credit.RIM : Credit.ALREADY_KNOWN, root, null);      // ALREADY_KNOWN: this lip was seen before
        }
        return new Result(Credit.NO_VOLUME, -1, null);
    }

    /**
     * Run EE probe: "cave index: volumes N (caves C, dips D, no opening O), rim credits R, air credits A, known M" - the
     * volumes of the loaded chunks, the credited cells (rim, air) and the known volumes.
     */
    public synchronized String counts() {
        fresh();
        int n = 0, caves = 0, none = 0, k = 0;
        for (int i = 0; i < next; i++) {
            if (find(i) != i) continue;
            n++;
            if (rimLow[i] == Integer.MAX_VALUE) none++;
            else if (cave(i)) caves++;
            if (known.contains(i)) k++;
        }
        return "cave index: volumes " + n + " (caves " + caves + ", dips " + (n - caves - none) + ", no opening " + none + "), rim credits "
                + rimSeen.size() + ", air credits " + credited.size() + ", known " + k;
    }

    /** The volume a label names: its entry (the opening cell nearest the eyes, else the cell found), rim, reach (-1: no opening), size, depth. */
    public record Spot(int x, int y, int z, int root, int rim, int reach, Size size, Depth depth, boolean cave, List<Long> cells) {}

    /**
     * The owner's label aim (aa cave yes|no): the volume whose air the crosshair's ray passed last ({@code path}, the
     * cells before the block it hit), else one beside the block it hit ({@code hit}, null: none), else the opening cell
     * nearest the eyes within {@code reach}; null: no air volume here. Any volume - a dip too: the owner labels what the
     * floor gets wrong. Pure.
     */
    public synchronized Spot labelSpot(List<int[]> path, int[] hit, double ex, double ey, double ez, double reach) {
        fresh();
        int r = -1;
        int[] at = null;
        if (path != null) for (int i = path.size() - 1; i >= 0 && r < 0; i--) { int[] p = path.get(i); r = runAt(p[0], p[1], p[2]); if (r >= 0) at = p; }
        if (r < 0 && hit != null) {
            double best = Double.MAX_VALUE;
            for (int[] d : SIX) {
                int[] n = {hit[0] + d[0], hit[1] + d[1], hit[2] + d[2]};
                int q = runAt(n[0], n[1], n[2]);
                double dd = sq(n[0] + 0.5 - ex) + sq(n[1] + 0.5 - ey) + sq(n[2] + 0.5 - ez);
                if (q >= 0 && dd < best) { best = dd; r = q; at = n; }
            }
        }
        int span = (int) Math.ceil(reach);
        if (r < 0) {
            double best = reach * reach;
            for (int x = (int) Math.floor(ex) - span; x <= (int) Math.floor(ex) + span; x++)
                for (int z = (int) Math.floor(ez) - span; z <= (int) Math.floor(ez) + span; z++)
                    for (int y = (int) Math.floor(ey) - span; y <= (int) Math.floor(ey) + span; y++) {
                        int q = runAt(x, y, z);
                        if (q < 0 || sky(x, z) > y) continue;                          // an opening cell only
                        double dd = sq(x + 0.5 - ex) + sq(y + 0.5 - ey) + sq(z + 0.5 - ez);
                        if (dd <= best) { best = dd; r = q; at = new int[]{x, y, z}; }
                    }
        }
        if (r < 0) return null;
        int root = find(r);
        Opening o = best(root);                                                    // run EF: the opening that decides it
        int[] entry = o == null ? at : nearest(o, ex, ey, ez);
        double walk = io.github.mojolowjo.entropybot.aa.brain.Unit.walkBps(), cap = io.github.mojolowjo.entropybot.aa.brain.Tuning.get("cave.exploreMinutes");
        return new Spot(entry[0], entry[1], entry[2], root, o == null ? 0 : o.rim(), o == null ? -1 : o.lowY() - minY[root],
                Size.of(cells[root], walk, cap), Depth.of(minY[root]), cave(root), o == null ? List.of() : o.cells());
    }

    private static double sq(double v) { return v * v; }

    /** aa cave check: the credits of the volume at x y z this session - {rim credits, air credits, known 0/1}; null: no volume there (not loaded). */
    public synchronized int[] creditsAt(int x, int y, int z) {
        fresh();
        int r = runAt(x, y, z);
        if (r < 0) return null;
        int root = find(r), rim = 0;
        for (long c : rimSeen) { int q = runAt(c); if (q >= 0 && find(q) == root) rim++; }
        return new int[]{rim, credits.getOrDefault(root, Set.of()).size(), known.contains(root) ? 1 : 0};
    }

    /**
     * J6b run EF (3): the other openings of the volume x y z is in, each as its STABLE cell ({@link #stableCell}: the same
     * cell wherever the bot stands, so a hold on it keys the opening, 94b82de3 review 1), nearest first from fx fy fz, at
     * most {@code max}; the opening holding x y z left out (the cave way's next lips when the first is unreachable).
     * Only openings that pass the floor (review 3). These need no ray-seen lip: allowed for a KNOWN cave only (D12, the
     * owner's cheat - its volume's cells are the bot's map); the caller passes a known cave's entry.
     */
    public synchronized List<int[]> otherOpenings(int x, int y, int z, int fx, int fy, int fz, int max) {
        fresh();
        int r = runAt(x, y, z);
        if (r < 0) return List.of();
        measure();
        int root = find(r);
        long at = cell(x, y, z);
        List<int[]> out = new ArrayList<>();
        for (Opening o : byRoot.getOrDefault(root, List.of())) {
            if (o.cells().contains(at) || !passes(o, root)) continue;
            out.add(stableCell(o));
        }
        out.sort((a, b) -> Long.compare(d2(a, fx, fy, fz), d2(b, fx, fy, fz)));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** An opening's stable cell: its lowest cell, ties by x then z (the same for every asker). */
    static int[] stableCell(Opening o) {
        int[] best = null;
        for (long c : o.cells()) {
            int[] p = Sightings.cellOf(c);
            if (best == null || p[1] < best[1] || (p[1] == best[1] && (p[0] < best[0] || (p[0] == best[0] && p[2] < best[2])))) best = p;
        }
        return best;
    }

    /** One cave of {@link #truth}: its opening cell nearest the bot, size bucket, depth band, rim and air credits, known. */
    public record Truth(int x, int y, int z, Size size, Depth depth, int rim, int air, boolean known, int root) {}

    /** {@link #truth}'s answer: the caves nearest first, then the dips and the volumes with no opening within the radius. */
    public record TruthList(List<Truth> caves, int dips, int noOpening) {}

    /**
     * The owner's debug read (aa cave list, the manager's ruling after run EE): every index volume with a cell within
     * {@code r} (horizontal) of x z, nearest first; the caves (passing the floor, {@link #isCave}) one by one, the dips and
     * the volumes with no opening counted. The truth from the index, so an empty list at a real cave says the floor or the
     * index is wrong, not the rays. Rule 17: a debug read only - never a way, a target or a fact; nothing in the planner
     * reads it (J6CaveIndexTest greps for it).
     */
    public synchronized TruthList truth(int x, int y, int z, int r) {
        fresh();
        List<Chunk> near = new ArrayList<>();
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++)
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) { Chunk ch = chunks.get(chunkKey(cx, cz)); if (ch != null) near.add(ch); }
        return truthOf(near, x, y, z, r);
    }

    /** As {@link #truth} over every chunk the index holds, no radius ({@code aa cave list all}; the same debug read, rule 17). */
    public synchronized TruthList truthAll(int x, int y, int z) {
        fresh();
        return truthOf(new ArrayList<>(chunks.values()), x, y, z, Integer.MAX_VALUE);
    }

    private TruthList truthOf(List<Chunk> scan, int x, int y, int z, int r) {
        Set<Integer> all = new HashSet<>();
        for (Chunk ch : scan) {
                if (ch.lo.length == 0) continue;
                int cx = ch.cx, cz = ch.cz;
                for (int c = 0; c < 256; c++) {
                    int tx = (cx << 4) + (c & 15), tz = (cz << 4) + (c >> 4);
                    if (r != Integer.MAX_VALUE && (Math.abs(tx - x) > r || Math.abs(tz - z) > r)) continue;
                    for (int i = ch.start[c]; i < ch.start[c + 1]; i++) all.add(find(ch.id[i]));
                }
        }
        Map<Integer, Integer> rim = new HashMap<>();
        for (long c : rimSeen) { int q = runAt(c); if (q >= 0) rim.merge(find(q), 1, Integer::sum); }
        double walk = io.github.mojolowjo.entropybot.aa.brain.Unit.walkBps(), cap = io.github.mojolowjo.entropybot.aa.brain.Tuning.get("cave.exploreMinutes");
        List<Truth> caves = new ArrayList<>();
        int dips = 0, none = 0;
        for (int root : all) {
            if (rimLow[root] == Integer.MAX_VALUE) { none++; continue; }
            if (!cave(root)) { dips++; continue; }
            int[] e = nearest(best(root), x, y, z);                                    // run EF: the passing opening
            caves.add(new Truth(e[0], e[1], e[2], Size.of(cells[root], walk, cap), Depth.of(minY[root]), rim.getOrDefault(root, 0),
                    credits.getOrDefault(root, Set.of()).size(), known.contains(root), root));
        }
        caves.sort((a, b) -> Long.compare(d2(new int[]{a.x(), a.y(), a.z()}, x, y, z), d2(new int[]{b.x(), b.y(), b.z()}, x, y, z)));
        return new TruthList(caves, dips, none);
    }

    @FunctionalInterface
    public interface CellTest { boolean test(int x, int y, int z); }

    private static long d2(int[] a, int x, int y, int z) {
        long dx = a[0] - x, dy = a[1] - y, dz = a[2] - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** The volumes credited and not known yet (aa cave list): root -> its credited cells ({@link #cell} packed). */
    public synchronized Map<Integer, List<Long>> credited() {
        fresh();
        Map<Integer, List<Long>> out = new java.util.TreeMap<>();
        for (var e : credits.entrySet()) if (find(e.getKey()) == e.getKey() && !known.contains(e.getKey())) out.put(e.getKey(), List.copyOf(e.getValue()));
        return out;
    }

    /**
     * The cells a ray from {@code a} to {@code b} passes, in order (a voxel walk: each cell it enters, the start cell
     * first, the end cell last). Pure.
     */
    public static List<int[]> path(double ax, double ay, double az, double bx, double by, double bz) {
        List<int[]> out = new ArrayList<>();
        int x = (int) Math.floor(ax), y = (int) Math.floor(ay), z = (int) Math.floor(az);
        int ex = (int) Math.floor(bx), ey = (int) Math.floor(by), ez = (int) Math.floor(bz);
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx), tdy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy), tdz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tx = sx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? (x + 1 - ax) : (ax - x)) * tdx;
        double ty = sy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? (y + 1 - ay) : (ay - y)) * tdy;
        double tz = sz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? (z + 1 - az) : (az - z)) * tdz;
        out.add(new int[]{x, y, z});
        int guard = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z) + 3;   // structural: a cell a step at most, plus slack
        while ((x != ex || y != ey || z != ez) && guard-- > 0) {
            if (tx <= ty && tx <= tz) { x += sx; tx += tdx; }
            else if (ty <= tz) { y += sy; ty += tdy; }
            else { z += sz; tz += tdz; }
            out.add(new int[]{x, y, z});
        }
        return out;
    }

    /**
     * Run ED: the ground of a column - the first cell above its highest block that is not air and not leaves (leaves are a
     * tree's crown, no cave roof), scanning down from {@code worldSurface} (WORLD_SURFACE: the first air over the top block);
     * {@code open} answers "air or leaves". The air under a crown lies above the ground: never indexed. Pure.
     */
    public static int ground(int x, int z, int worldSurface, int minY, CellTest open) {
        int y = worldSurface - 1;
        while (y >= minY && open.test(x, y, z)) y--;
        return y + 1;
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
