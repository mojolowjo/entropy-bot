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
 * The faces the bot's own view has seen ({@code watch seen}, docs/CAMERA_PLAN.md "Visible-faces idea"): per block a
 * 6-bit mask of its seen sides and the brightest light (0-15) it was seen at. Bounded like {@link KnownAir}: at most
 * {@link #MAX_PER_REGION} blocks per 256x256 region and {@link #MAX_TOTAL} in all, oldest first (a block seen again
 * counts as new). Persisted to {@code entropybot/seenfaces.bin} (10 bytes a block, at most ~2 MB). Thread-safe; pure.
 */
public final class SeenFaces {
    public static final int MAX_PER_REGION = 60_000, MAX_TOTAL = 200_000;
    static final int MAGIC = 0x45425346;             // "EBSF"
    static final int FORMAT = 1;

    /** A stored block: its key ({@link CellKey}), the mask of seen sides (bit = Shell.OFF index) and the brightest light. */
    public record Entry(long key, int mask, int light) {}

    private final int maxPerRegion, maxTotal;
    /** dim -> region -> block key -> seq << 16 | light << 6 | mask (insertion order = age). */
    private final Map<String, Map<Long, LinkedHashMap<Long, Long>>> dims = new HashMap<>();
    private long seq, version, evicted;
    private int total, faces;

    public SeenFaces() { this(MAX_PER_REGION, MAX_TOTAL); }

    public SeenFaces(int maxPerRegion, int maxTotal) {
        this.maxPerRegion = Math.max(1, maxPerRegion);
        this.maxTotal = Math.max(1, maxTotal);
    }

    /** Adds a seen face; true when the store changed (a new face, or brighter than before), so {@link #version()} moved. */
    public boolean add(String dim, int x, int y, int z, int side, int light) {
        if (side < 0 || side > 5) return false;
        return put(dim, x, y, z, 1 << side, light);
    }

    private synchronized boolean put(String dim, int x, int y, int z, int maskBits, int light) {
        if (dim == null || maskBits == 0) return false;
        int l = Math.max(0, Math.min(15, light));
        long key = CellKey.of(x, y, z);
        Map<Long, LinkedHashMap<Long, Long>> regions = dims.computeIfAbsent(dim, d -> new HashMap<>());
        long rk = KnownAir.region(x, z);
        LinkedHashMap<Long, Long> cells = regions.computeIfAbsent(rk, r -> new LinkedHashMap<>());
        Long old = cells.remove(key);
        int mask = old == null ? 0 : (int) (old & 63), oldLight = old == null ? 0 : (int) (old >> 6 & 15);
        int nm = mask | (maskBits & 63), nl = Math.max(oldLight, l);
        cells.put(key, (++seq) << 16 | (long) nl << 6 | nm);
        faces += Integer.bitCount(nm) - Integer.bitCount(mask);
        boolean changed = old == null || nm != mask || nl != oldLight;
        if (changed) version++;
        if (old == null) {
            total++;
            while (cells.size() > maxPerRegion) removeOldest(dim, regions, rk, cells);
            while (total > maxTotal) evictOldestAnywhere();
        }
        return changed;
    }

    private void removeOldest(String dim, Map<Long, LinkedHashMap<Long, Long>> regions, long rk, LinkedHashMap<Long, Long> cells) {
        Iterator<Map.Entry<Long, Long>> it = cells.entrySet().iterator();
        Map.Entry<Long, Long> e = it.next();
        faces -= Integer.bitCount((int) (e.getValue() & 63));
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
        for (Map.Entry<String, Map<Long, LinkedHashMap<Long, Long>>> d : dims.entrySet())
            for (Map.Entry<Long, LinkedHashMap<Long, Long>> r : d.getValue().entrySet()) {
                long first = r.getValue().values().iterator().next() >>> 16;
                if (first < bestSeq) {
                    bestSeq = first;
                    bestDim = d.getKey();
                    bestRegion = r.getKey();
                }
            }
        if (bestDim == null) {
            total = 0;
            faces = 0;
            return;
        }
        Map<Long, LinkedHashMap<Long, Long>> regions = dims.get(bestDim);
        removeOldest(bestDim, regions, bestRegion, regions.get(bestRegion));
    }

    /** The entry for a block, or null. */
    public synchronized Entry get(String dim, int x, int y, int z) {
        Map<Long, LinkedHashMap<Long, Long>> regions = dims.get(dim);
        if (regions == null) return null;
        LinkedHashMap<Long, Long> cells = regions.get(KnownAir.region(x, z));
        Long v = cells == null ? null : cells.get(CellKey.of(x, y, z));
        return v == null ? null : new Entry(CellKey.of(x, y, z), (int) (v & 63), (int) (v >> 6 & 15));
    }

    /** The stored blocks of dim within radius (a sphere) of x y z. */
    public synchronized List<Entry> near(String dim, int x, int y, int z, int radius) {
        List<Entry> out = new ArrayList<>();
        Map<Long, LinkedHashMap<Long, Long>> regions = dims.get(dim);
        if (regions == null) return out;
        long r2 = (long) radius * radius;
        for (int rx = (x - radius) >> KnownAir.REGION_SHIFT; rx <= (x + radius) >> KnownAir.REGION_SHIFT; rx++)
            for (int rz = (z - radius) >> KnownAir.REGION_SHIFT; rz <= (z + radius) >> KnownAir.REGION_SHIFT; rz++) {
                LinkedHashMap<Long, Long> cells = regions.get(KnownAir.region(rx << KnownAir.REGION_SHIFT, rz << KnownAir.REGION_SHIFT));
                if (cells == null) continue;
                for (Map.Entry<Long, Long> c : cells.entrySet()) {
                    long k = c.getKey();
                    long dx = CellKey.x(k) - x, dy = CellKey.y(k) - y, dz = CellKey.z(k) - z;
                    if (dx * dx + dy * dy + dz * dz <= r2) out.add(new Entry(k, (int) (c.getValue() & 63), (int) (c.getValue() >> 6 & 15)));
                }
            }
        return out;
    }

    /**
     * A chunk is within the render distance of the eye's chunk (vanilla's round view, one chunk of slack like
     * {@code ChunkTrackingView.isWithinDistance} with the outer ring): dx, dz less one chunk each, squared, below r squared.
     */
    public static boolean chunkWithin(int chunkX, int chunkZ, int eyeChunkX, int eyeChunkZ, int radiusChunks) {
        long dx = Math.max(0, Math.abs((long) chunkX - eyeChunkX) - 1), dz = Math.max(0, Math.abs((long) chunkZ - eyeChunkZ) - 1);
        long r = Math.max(1, radiusChunks);
        return dx * dx + dz * dz < r * r;
    }

    /**
     * The surface store's eviction (0.16.1): drops every block of another dimension, and every block whose chunk is not
     * within radiusChunks of the eye's chunk ({@link #chunkWithin}). A region (256 x 256) wholly outside is dropped at once.
     * Returns the blocks dropped (counted in {@link #pruned()}, not in {@link #evicted()}).
     */
    public synchronized int pruneOutside(String keepDim, int eyeChunkX, int eyeChunkZ, int radiusChunks) {
        int removed = 0;
        Iterator<Map.Entry<String, Map<Long, LinkedHashMap<Long, Long>>>> dit = dims.entrySet().iterator();
        while (dit.hasNext()) {
            Map.Entry<String, Map<Long, LinkedHashMap<Long, Long>>> d = dit.next();
            boolean other = !d.getKey().equals(keepDim);
            Iterator<LinkedHashMap<Long, Long>> rit = d.getValue().values().iterator();
            while (rit.hasNext()) {
                LinkedHashMap<Long, Long> cells = rit.next();
                if (cells.isEmpty()) {
                    rit.remove();
                    continue;
                }
                long anyKey = cells.keySet().iterator().next();
                int rx = CellKey.x(anyKey) >> KnownAir.REGION_SHIFT, rz = CellKey.z(anyKey) >> KnownAir.REGION_SHIFT;
                int per = 1 << (KnownAir.REGION_SHIFT - 4);            // chunks per region side (16)
                int nearX = Math.max(rx * per, Math.min(rx * per + per - 1, eyeChunkX)), nearZ = Math.max(rz * per, Math.min(rz * per + per - 1, eyeChunkZ));
                if (other || !chunkWithin(nearX, nearZ, eyeChunkX, eyeChunkZ, radiusChunks)) {
                    for (long v : cells.values()) faces -= Integer.bitCount((int) (v & 63));
                    removed += cells.size();
                    total -= cells.size();
                    rit.remove();
                    continue;
                }
                Iterator<Map.Entry<Long, Long>> it = cells.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Long, Long> c = it.next();
                    long k = c.getKey();
                    if (chunkWithin(CellKey.x(k) >> 4, CellKey.z(k) >> 4, eyeChunkX, eyeChunkZ, radiusChunks)) continue;
                    faces -= Integer.bitCount((int) (c.getValue() & 63));
                    total--;
                    removed++;
                    it.remove();
                }
                if (cells.isEmpty()) rit.remove();
            }
            if (d.getValue().isEmpty()) dit.remove();
        }
        if (removed > 0) {
            version++;
            pruned += removed;
        }
        return removed;
    }

    private long pruned;

    /** Blocks dropped by {@link #pruneOutside} so far. */
    public synchronized long pruned() { return pruned; }

    public synchronized long version() { return version; }

    /** Blocks stored. */
    public synchronized int size() { return total; }

    /** Faces stored (seen sides over all blocks). */
    public synchronized int faces() { return faces; }

    public synchronized long evicted() { return evicted; }

    public synchronized void clear() {
        dims.clear();
        total = 0;
        faces = 0;
        version++;
    }

    /** Writes every block, oldest first per dimension: key (8 bytes) and light << 6 | mask (2 bytes). */
    public synchronized void write(OutputStream os) throws IOException {
        DataOutputStream out = new DataOutputStream(os);
        out.writeInt(MAGIC);
        out.writeInt(FORMAT);
        out.writeInt(dims.size());
        for (Map.Entry<String, Map<Long, LinkedHashMap<Long, Long>>> d : dims.entrySet()) {
            List<long[]> all = new ArrayList<>();
            for (LinkedHashMap<Long, Long> cells : d.getValue().values())
                for (Map.Entry<Long, Long> c : cells.entrySet()) all.add(new long[]{c.getValue() >>> 16, c.getKey(), c.getValue() & 0x3FF});
            all.sort((a, b) -> Long.compare(a[0], b[0]));
            out.writeUTF(d.getKey());
            out.writeInt(all.size());
            for (long[] c : all) {
                out.writeLong(c[1]);
                out.writeShort((int) c[2]);
            }
        }
        out.flush();
    }

    /** Replaces the store with the file's; returns the blocks read. Throws on a broken file (the caller sets it aside). */
    public int read(InputStream is) throws IOException {
        DataInputStream in = new DataInputStream(is);
        if (in.readInt() != MAGIC) throw new IOException("not a seen-faces file");
        int fmt = in.readInt();
        if (fmt != FORMAT) throw new IOException("unknown format " + fmt);
        int nd = in.readInt();
        if (nd < 0 || nd > 64) throw new IOException("bad dimension count " + nd);
        Map<String, long[][]> read = new LinkedHashMap<>();
        for (int i = 0; i < nd; i++) {
            String dim = in.readUTF();
            int n = in.readInt();
            if (n < 0 || n > 10_000_000) throw new IOException("bad block count " + n);
            long[][] blocks = new long[n][2];
            for (int j = 0; j < n; j++) {
                blocks[j][0] = in.readLong();
                int v = in.readUnsignedShort();
                if ((v & 63) == 0 || v > 0x3FF) throw new IOException("bad face mask " + v);
                blocks[j][1] = v;
            }
            read.put(dim, blocks);
        }
        clear();
        int added = 0;
        for (Map.Entry<String, long[][]> e : read.entrySet())
            for (long[] b : e.getValue()) {
                long k = b[0];
                if (put(e.getKey(), CellKey.x(k), CellKey.y(k), CellKey.z(k), (int) (b[1] & 63), (int) (b[1] >> 6 & 15))) added++;
            }
        return added;
    }
}
