package io.github.mojolowjo.entropybot.threat;

/**
 * 0.23.2: the event-driven reach grid (the owner's design, 2026-10-07). The block array is kept between refreshes:
 * single cells are patched from block changes ({@link #patch}), a box shift copies only the new slice ({@link #moveTo}),
 * and the search re-runs only when the array is dirty or the bot changed cell ({@link #ensure}: coalesced, at most one
 * walk search and one spider search per call however many patches or new mobs came in). A full copy is the safety
 * net (every 5 s, and on a chunk load/unload inside the box), done by the caller through {@link #full}.
 * Pure Java: the game side gives a {@link Source}.
 */
public final class ReachCache {
    /** Reads one world cell: its {@link ReachGrid} code and its light (light only asked where a mob could stand). */
    public interface Source {
        byte code(int wx, int wy, int wz);

        byte light(int wx, int wy, int wz);
    }

    public final int sx, sy, sz;
    private ReachGrid grid;
    private boolean dirty = true;
    private int[] walk, spider;
    private int sbx = Integer.MIN_VALUE, sby, sbz;
    // counters
    public long patches, searches, fulls, slices, sliceCells, cellsRead;
    public double lastSearchMs;

    public ReachCache(int sx, int sy, int sz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
    }

    public ReachGrid grid() { return grid; }

    public boolean dirty() { return dirty; }

    public int[] walk() { return walk; }

    /** The whole box read again (the safety net). */
    public void full(Source s, int ox, int oy, int oz) {
        ReachGrid g = new ReachGrid(sx, sy, sz, ox, oy, oz);
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++)
            g.codes[g.idx(x, y, z)] = s.code(ox + x, oy + y, oz + z);
        cellsRead += (long) sx * sy * sz;
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) lightAt(s, g, x, y, z);
        ReachGrid old = grid;
        // the same blocks as before (the usual case): the last search still stands
        boolean same = old != null && old.ox == ox && old.oy == oy && old.oz == oz && java.util.Arrays.equals(old.codes, g.codes);
        grid = g;
        if (!same) dirty = true;
        fulls++;
    }

    /**
     * The box moves to a new origin: cells that stay are copied, only the new slice is read. Returns false when the
     * origin is the same. No grid yet, or a jump past the box, reads it all ({@link #full}).
     */
    public boolean moveTo(Source s, int ox, int oy, int oz) {
        ReachGrid old = grid;
        if (old != null && old.ox == ox && old.oy == oy && old.oz == oz) return false;
        if (old == null || Math.abs(ox - old.ox) >= sx || Math.abs(oy - old.oy) >= sy || Math.abs(oz - old.oz) >= sz) {
            full(s, ox, oy, oz);
            return true;
        }
        ReachGrid g = new ReachGrid(sx, sy, sz, ox, oy, oz);
        int dx = ox - old.ox, dy = oy - old.oy, dz = oz - old.oz;
        boolean[] fresh = new boolean[g.codes.length];
        int read = 0;
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            int i = g.idx(x, y, z);
            int qx = x + dx, qy = y + dy, qz = z + dz;
            if (old.in(qx, qy, qz)) {
                int j = old.idx(qx, qy, qz);
                g.codes[i] = old.codes[j];
                g.light[i] = old.light[j];
            } else {
                g.codes[i] = s.code(ox + x, oy + y, oz + z);
                fresh[i] = true;
                read++;
            }
        }
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++)
            if (fresh[g.idx(x, y, z)]) lightAt(s, g, x, y, z);
        grid = g;
        dirty = true;
        slices++;
        sliceCells += read;
        cellsRead += read;
        return true;
    }

    /** One block changed: patches its cell (and the light where a mob now stands). False when outside the box or unchanged. */
    public boolean patch(int wx, int wy, int wz, byte code, Source s) {
        ReachGrid g = grid;
        if (g == null) return false;
        int x = wx - g.ox, y = wy - g.oy, z = wz - g.oz;
        if (!g.in(x, y, z)) return false;
        int i = g.idx(x, y, z);
        if (g.codes[i] == code) return false;
        g.codes[i] = code;
        if (s != null) for (int yy = y - 1; yy <= y + 1; yy++) if (g.in(x, yy, z)) lightAt(s, g, x, yy, z);
        dirty = true;
        patches++;
        return true;
    }

    /** Whether world (wx, wy, wz) lies in the box. */
    public boolean inBox(int wx, int wy, int wz) {
        ReachGrid g = grid;
        return g != null && g.in(wx - g.ox, wy - g.oy, wz - g.oz);
    }

    /** Whether chunk (cx, cz) overlaps the box. */
    public boolean overlapsChunk(int cx, int cz) {
        ReachGrid g = grid;
        if (g == null) return false;
        int x0 = cx << 4, z0 = cz << 4;
        return x0 <= g.ox + sx - 1 && x0 + 15 >= g.ox && z0 <= g.oz + sz - 1 && z0 + 15 >= g.oz;
    }

    /**
     * The coalesced search: runs only when the array is dirty, the bot (world cell) moved, or a spider answer is asked
     * for the first time since. Returns true when a search ran.
     */
    public boolean ensure(int bwx, int bwy, int bwz, boolean needSpider) {
        ReachGrid g = grid;
        if (g == null) return false;
        boolean moved = bwx != sbx || bwy != sby || bwz != sbz;
        boolean stale = dirty || moved || walk == null;
        if (!stale && (!needSpider || spider != null)) return false;
        long t0 = System.nanoTime();
        int bx = bwx - g.ox, by = bwy - g.oy, bz = bwz - g.oz;
        if (stale) {
            walk = g.search(bx, by, bz, false);
            spider = null;
        }
        if (needSpider && spider == null) spider = g.search(bx, by, bz, true);
        sbx = bwx;
        sby = bwy;
        sbz = bwz;
        dirty = false;
        searches++;
        lastSearchMs = (System.nanoTime() - t0) / 1e6;
        return true;
    }

    /** The moves a mob at world (wx, wy, wz) needs (-1 no path, -2 outside / no search yet). Call {@link #ensure} first. */
    public int dist(int wx, int wy, int wz, boolean spiderMove) {
        ReachGrid g = grid;
        int[] d = spiderMove && spider != null ? spider : walk;
        if (g == null || d == null) return -2;
        return g.distAt(d, wx, wy, wz);
    }

    /** Light only where a mob could stand (feet free, floor below or a low block), as the 0.23.0 snapshot did. */
    private void lightAt(Source s, ReachGrid g, int x, int y, int z) {
        int i = g.idx(x, y, z);
        g.light[i] = 0;
        if (y < 1 || y >= sy - 1) return;
        byte c = g.codes[i];
        if ((c != ReachGrid.AIR && c != ReachGrid.LOW) || g.codes[g.idx(x, y + 1, z)] != ReachGrid.AIR) return;
        byte below = g.codes[g.idx(x, y - 1, z)];
        if (below != ReachGrid.SOLID && c != ReachGrid.LOW) return;
        g.light[i] = s.light(g.ox + x, g.oy + y, g.oz + z);
    }
}
