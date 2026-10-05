package io.github.mojolowjo.entropybot.watchview;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Camera v2 (docs/CAMERA_PLAN.md): the "known air" cells, the only cells whose walls the tunnel view may draw. A cell
 * gets in when the bot broke the block there (a clear's broken cell), stood in it (its trail), or saw it from where it
 * stood (connected air in its line of sight underground; see {@link Sight}). Never whole dig boxes (review C6).
 *
 * <p>Bounded like the other notes: at most {@link #MAX_PER_REGION} cells per 256x256 region (the "area") and
 * {@link #MAX_TOTAL} in all; past a cap the oldest cell goes first (a cell seen again counts as new). Persisted to
 * {@code entropybot/knownair.bin} (about 8 bytes a cell, at most ~0.8 MB: cheap). Thread-safe; pure Java.
 */
public final class KnownAir {
    public static final int REGION_SHIFT = 8;
    public static final int MAX_PER_REGION = 30_000, MAX_TOTAL = 100_000;
    static final int MAGIC = 0x45424B41;             // "EBKA"
    static final int FORMAT = 1;

    private final int maxPerRegion, maxTotal;
    /** dim -> region key -> cell key -> sequence number (insertion order = age). */
    private final Map<String, Map<Long, LinkedHashMap<Long, Long>>> dims = new HashMap<>();
    private long seq, version, evicted;
    private int total;

    public KnownAir() { this(MAX_PER_REGION, MAX_TOTAL); }

    public KnownAir(int maxPerRegion, int maxTotal) {
        this.maxPerRegion = Math.max(1, maxPerRegion);
        this.maxTotal = Math.max(1, maxTotal);
    }

    public static long region(int x, int z) {
        return ((long) (x >> REGION_SHIFT) << 32) ^ ((z >> REGION_SHIFT) & 0xFFFFFFFFL);
    }

    /** Adds a cell; true when it was new (the set, and so {@link #version()}, changed). A known cell counts as new again for the eviction order. */
    public synchronized boolean add(String dim, int x, int y, int z) {
        if (dim == null) return false;
        long key = CellKey.of(x, y, z);
        Map<Long, LinkedHashMap<Long, Long>> regions = dims.computeIfAbsent(dim, d -> new HashMap<>());
        long rk = region(x, z);
        LinkedHashMap<Long, Long> cells = regions.computeIfAbsent(rk, r -> new LinkedHashMap<>());
        Long old = cells.remove(key);
        cells.put(key, ++seq);
        if (old != null) return false;
        total++;
        version++;
        while (cells.size() > maxPerRegion) removeOldest(dim, regions, rk, cells);
        while (total > maxTotal) evictOldestAnywhere();
        return true;
    }

    private void removeOldest(String dim, Map<Long, LinkedHashMap<Long, Long>> regions, long rk, LinkedHashMap<Long, Long> cells) {
        Iterator<Long> it = cells.keySet().iterator();
        it.next();
        it.remove();
        total--;
        evicted++;
        version++;
        if (cells.isEmpty()) {
            regions.remove(rk);
            if (regions.isEmpty()) dims.remove(dim);
        }
    }

    private void evictOldestAnywhere() {
        String bestDim = null;
        long bestRegion = 0, bestSeq = Long.MAX_VALUE;
        for (Map.Entry<String, Map<Long, LinkedHashMap<Long, Long>>> d : dims.entrySet()) {
            for (Map.Entry<Long, LinkedHashMap<Long, Long>> r : d.getValue().entrySet()) {
                long first = r.getValue().values().iterator().next();
                if (first < bestSeq) {
                    bestSeq = first;
                    bestDim = d.getKey();
                    bestRegion = r.getKey();
                }
            }
        }
        if (bestDim == null) {
            total = 0;
            return;
        }
        Map<Long, LinkedHashMap<Long, Long>> regions = dims.get(bestDim);
        removeOldest(bestDim, regions, bestRegion, regions.get(bestRegion));
    }

    public synchronized boolean contains(String dim, int x, int y, int z) {
        Map<Long, LinkedHashMap<Long, Long>> regions = dims.get(dim);
        if (regions == null) return false;
        LinkedHashMap<Long, Long> cells = regions.get(region(x, z));
        return cells != null && cells.containsKey(CellKey.of(x, y, z));
    }

    public boolean contains(String dim, long key) {
        return contains(dim, CellKey.x(key), CellKey.y(key), CellKey.z(key));
    }

    /** The known cells of dim within radius (a sphere) of x y z. */
    public synchronized long[] near(String dim, int x, int y, int z, int radius) {
        Map<Long, LinkedHashMap<Long, Long>> regions = dims.get(dim);
        if (regions == null) return new long[0];
        long r2 = (long) radius * radius;
        List<Long> out = new ArrayList<>();
        for (int rx = (x - radius) >> REGION_SHIFT; rx <= (x + radius) >> REGION_SHIFT; rx++)
            for (int rz = (z - radius) >> REGION_SHIFT; rz <= (z + radius) >> REGION_SHIFT; rz++) {
                LinkedHashMap<Long, Long> cells = regions.get(region(rx << REGION_SHIFT, rz << REGION_SHIFT));
                if (cells == null) continue;
                for (long k : cells.keySet()) {
                    long dx = CellKey.x(k) - x, dy = CellKey.y(k) - y, dz = CellKey.z(k) - z;
                    if (dx * dx + dy * dy + dz * dz <= r2) out.add(k);
                }
            }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** Changes with every cell added or dropped: the mesh is rebuilt only when this moved. */
    public synchronized long version() { return version; }

    public synchronized int size() { return total; }

    public synchronized long evicted() { return evicted; }

    public synchronized void clear() {
        dims.clear();
        total = 0;
        version++;
    }

    /** Writes every cell, oldest first per dimension. */
    public synchronized void write(OutputStream os) throws IOException {
        DataOutputStream out = new DataOutputStream(os);
        out.writeInt(MAGIC);
        out.writeInt(FORMAT);
        out.writeInt(dims.size());
        for (Map.Entry<String, Map<Long, LinkedHashMap<Long, Long>>> d : dims.entrySet()) {
            List<long[]> all = new ArrayList<>();
            for (LinkedHashMap<Long, Long> cells : d.getValue().values())
                for (Map.Entry<Long, Long> c : cells.entrySet()) all.add(new long[]{c.getValue(), c.getKey()});
            all.sort((a, b) -> Long.compare(a[0], b[0]));
            out.writeUTF(d.getKey());
            out.writeInt(all.size());
            for (long[] c : all) out.writeLong(c[1]);
        }
        out.flush();
    }

    /** Replaces the cells with the file's. Throws on a broken file (the caller sets it aside and starts empty). */
    public synchronized int read(InputStream is) throws IOException {
        DataInputStream in = new DataInputStream(is);
        if (in.readInt() != MAGIC) throw new IOException("not a known-air file");
        int fmt = in.readInt();
        if (fmt != FORMAT) throw new IOException("unknown format " + fmt);
        int nd = in.readInt();
        if (nd < 0 || nd > 64) throw new IOException("bad dimension count " + nd);
        Map<String, long[]> read = new LinkedHashMap<>();
        for (int i = 0; i < nd; i++) {
            String dim = in.readUTF();
            int n = in.readInt();
            if (n < 0 || n > 10_000_000) throw new IOException("bad cell count " + n);
            long[] cells = new long[n];
            for (int j = 0; j < n; j++) cells[j] = in.readLong();
            read.put(dim, cells);
        }
        clear();
        int added = 0;
        for (Map.Entry<String, long[]> e : read.entrySet())
            for (long k : e.getValue()) if (add(e.getKey(), CellKey.x(k), CellKey.y(k), CellKey.z(k))) added++;
        return added;
    }
}
