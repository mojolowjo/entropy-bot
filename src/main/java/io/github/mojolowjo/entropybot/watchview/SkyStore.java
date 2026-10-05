package io.github.mojolowjo.entropybot.watchview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 0.17.1: what the surface scan ({@link SkyScan}) found, per chunk: the faces whose sky cell lies in that chunk (sorted
 * face keys, the light in front of each), the floor of each of its 256 columns, and a version for the chunk's mesh.
 * In memory only (never saved: the game shows the surface to anyone, nothing to remember), pruned to the render
 * distance, capped at {@link #MAX_FACES} faces with the farthest chunks dropped first. Also the scan's queue of chunks
 * to (re)scan, nearest first. Thread-safe (the client tick writes, the render thread reads); pure (JUnit SkyScanTest).
 *
 * <p>Size: at render distance 5 the scan covers about 97 chunks (vanilla's round view) = 24,800 columns. Faces per
 * column, counted on paper: open meadow ~1.3 (the top plus the odd step), spruce forest with Fast leaves ~10 (ground,
 * steps, a 10-high trunk's sides and ~150 outer leaf faces per tree at one tree per ~25 columns), Fancy leaves ~16 (the
 * faces between leaves too). So a forest is ~250,000 faces (Fancy ~400,000), a meadow ~32,000. The cap of 600,000 holds a
 * Fancy forest at render distance 5 with room; at larger distances the farthest chunks are left out (said in status).
 * Memory: 9 bytes a face here (~5.4 MB at the cap), 96 bytes a face in the GPU buffers (~58 MB at the cap).
 */
public final class SkyStore {
    public static final int MAX_FACES = 600_000;

    /** One chunk's result. faces: sorted {@link #faceKey}s; light[i] belongs to faces[i]; floors[lz * 16 + lx]. */
    public record Chunk(int cx, int cz, long[] faces, byte[] light, int[] floors, long version) {
        public int minFloor() {
            int m = Integer.MAX_VALUE;
            for (int f : floors) m = Math.min(m, f);
            return m;
        }
    }

    private final int maxFaces;
    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final LinkedHashSet<Long> queue = new LinkedHashSet<>();
    /** Chunks left out because of the cap, until the eye moves to another chunk. */
    private final LinkedHashSet<Long> capped = new LinkedHashSet<>();
    private int total;
    private long version, nextChunkVersion, pruned, cappedDrops;

    public SkyStore() { this(MAX_FACES); }

    public SkyStore(int maxFaces) { this.maxFaces = Math.max(1, maxFaces); }

    public static long chunkKey(int cx, int cz) { return (long) cx << 32 | (cz & 0xFFFFFFFFL); }

    public static int keyX(long k) { return (int) (k >> 32); }

    public static int keyZ(long k) { return (int) k; }

    public static long faceKey(int x, int y, int z, int side) { return SeenMesh.faceKey(x, y, z, side); }

    // ---- results -------------------------------------------------------------------------------------------------

    /** A new version number for a chunk result (so a mesh built from an older result is rebuilt). */
    public synchronized long newChunkVersion() { return ++nextChunkVersion; }

    /**
     * Stores a chunk's result (replacing the old one). Over the cap, the chunks farthest from the eye's chunk go first;
     * returns false when the new chunk itself was the one dropped.
     */
    public synchronized boolean put(Chunk c, int eyeCx, int eyeCz) {
        long k = chunkKey(c.cx(), c.cz());
        Chunk old = chunks.put(k, c);
        if (old != null) total -= old.faces().length;
        total += c.faces().length;
        version++;
        boolean kept = true;
        while (total > maxFaces && !chunks.isEmpty()) {
            long far = farthest(eyeCx, eyeCz);
            Chunk gone = chunks.remove(far);
            total -= gone.faces().length;
            capped.add(far);
            cappedDrops++;
            if (far == k) kept = false;
        }
        return kept;
    }

    private long farthest(int eyeCx, int eyeCz) {
        long best = 0, bestD = -1;
        for (long k : chunks.keySet()) {
            long dx = keyX(k) - eyeCx, dz = keyZ(k) - eyeCz, d = dx * dx + dz * dz;
            if (d > bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    public synchronized Chunk get(int cx, int cz) { return chunks.get(chunkKey(cx, cz)); }

    public synchronized boolean has(int cx, int cz) { return chunks.containsKey(chunkKey(cx, cz)); }

    public synchronized void remove(int cx, int cz) {
        Chunk c = chunks.remove(chunkKey(cx, cz));
        if (c != null) {
            total -= c.faces().length;
            version++;
        }
        queue.remove(chunkKey(cx, cz));
    }

    /** Is the block column x z in a scanned chunk? (Then its sky faces come from the scan.) */
    public synchronized boolean covers(int x, int z) { return chunks.containsKey(chunkKey(x >> 4, z >> 4)); }

    /** The floor of column x z, or Integer.MIN_VALUE when its chunk was not scanned. */
    public synchronized int floorAt(int x, int z) {
        Chunk c = chunks.get(chunkKey(x >> 4, z >> 4));
        return c == null ? Integer.MIN_VALUE : c.floors()[(z & 15) * 16 + (x & 15)];
    }

    /** Is the cell x y z a sky cell by the last scan (above its column's floor)? False when not scanned. */
    public synchronized boolean skyCell(int x, int y, int z) {
        Chunk c = chunks.get(chunkKey(x >> 4, z >> 4));
        return c != null && y > c.floors()[(z & 15) * 16 + (x & 15)];
    }

    /** Is this face among the scanned ones (the chunk of its sky cell scanned, the face in it)? */
    public synchronized boolean contains(int x, int y, int z, int side) {
        if (side < 0 || side > 5) return false;
        int[] o = Shell.OFF[side];
        Chunk c = chunks.get(chunkKey((x + o[0]) >> 4, (z + o[2]) >> 4));
        return c != null && Arrays.binarySearch(c.faces(), faceKey(x, y, z, side)) >= 0;
    }

    /**
     * The main mesh leaves a seen face out because the scan's meshes stand for it: a face from the rays' surface store
     * whenever the chunk of its air cell is scanned (the scan is the authority on sky faces there, so stale ray faces go
     * too); a face from the saved store only when the scan has that very face.
     */
    public synchronized boolean drawsInstead(int x, int y, int z, int side, boolean fromSurfaceStore) {
        if (side < 0 || side > 5) return false;
        int[] o = Shell.OFF[side];
        Chunk c = chunks.get(chunkKey((x + o[0]) >> 4, (z + o[2]) >> 4));
        if (c == null) return false;
        return fromSurfaceStore || Arrays.binarySearch(c.faces(), faceKey(x, y, z, side)) >= 0;
    }

    /** Drops every chunk not within radiusChunks of the eye's chunk (also from the queue). Returns the chunks dropped. */
    public synchronized int pruneOutside(int eyeCx, int eyeCz, int radiusChunks) {
        int n = 0;
        Iterator<Map.Entry<Long, Chunk>> it = chunks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Chunk> e = it.next();
            if (SeenFaces.chunkWithin(keyX(e.getKey()), keyZ(e.getKey()), eyeCx, eyeCz, radiusChunks)) continue;
            total -= e.getValue().faces().length;
            it.remove();
            n++;
        }
        queue.removeIf(k -> !SeenFaces.chunkWithin(keyX(k), keyZ(k), eyeCx, eyeCz, radiusChunks));
        if (n > 0) {
            version++;
            pruned += n;
        }
        return n;
    }

    /** A snapshot of the chunks (for the meshes). */
    public synchronized List<Chunk> chunks() { return new ArrayList<>(chunks.values()); }

    public synchronized void clear() {
        chunks.clear();
        queue.clear();
        capped.clear();
        total = 0;
        version++;
    }

    public synchronized int faces() { return total; }

    public synchronized int chunkCount() { return chunks.size(); }

    public synchronized long version() { return version; }

    public synchronized long pruned() { return pruned; }

    public synchronized long cappedDrops() { return cappedDrops; }

    public synchronized int cappedNow() { return capped.size(); }

    // ---- queue ---------------------------------------------------------------------------------------------------

    /** Queue a chunk for a (re)scan, unless the cap left it out (until the eye changes chunk, {@link #eyeMoved}). */
    public synchronized void enqueue(int cx, int cz) {
        long k = chunkKey(cx, cz);
        if (!capped.contains(k)) queue.add(k);
    }

    /** The eye moved to another chunk: chunks the cap left out may be nearer now. */
    public synchronized void eyeMoved() { capped.clear(); }

    public synchronized boolean queued(int cx, int cz) { return queue.contains(chunkKey(cx, cz)); }

    public synchronized int queueSize() { return queue.size(); }

    /** Takes the queued chunk nearest to the eye's chunk (ties: the one queued first), or null when empty. */
    public synchronized long[] next(int eyeCx, int eyeCz) {
        if (queue.isEmpty()) return null;
        long best = 0, bestD = Long.MAX_VALUE;
        for (long k : queue) {
            long dx = keyX(k) - eyeCx, dz = keyZ(k) - eyeCz, d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        queue.remove(best);
        return new long[]{keyX(best), keyZ(best)};
    }
}
