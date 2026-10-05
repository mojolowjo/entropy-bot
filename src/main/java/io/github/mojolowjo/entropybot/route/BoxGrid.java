package io.github.mojolowjo.entropybot.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The walking graph of one box plus a one-cell margin (18x18x18 positions), read once from {@link CellMoves}:
 * forward and reverse edges for moves that stay inside box+margin, and the crossing moves through the box's faces.
 * Package-private working structure of {@link BoxBuilder} and the router.
 */
final class BoxGrid {
    static final int W = 18; // -1..16 local
    static final int N = W * W * W;

    final SectionKey key;
    final int ox, oy, oz; // world coords of local (0,0,0)
    final boolean[] standable = new boolean[N];
    // forward CSR
    final int[] fStart, fTo;
    final double[] fCost;
    // reverse CSR
    final int[] rStart, rFrom;
    final double[] rCost;
    /** Crossing moves per face: {ownIdx, otherWorld x,y,z?} kept as Crossing objects. */
    final List<List<Crossing>> crossings = new ArrayList<>();

    /** A move through face {@code face}. {@code own} = the grid index of the end in the box; exit = leaves the box. */
    record Crossing(int face, boolean exit, int own, int ou, int ov, int tu, int tv, double cost) {
    }

    static int idx(int lx, int ly, int lz) {
        return ((lx + 1) * W + (ly + 1)) * W + (lz + 1);
    }

    static int lx(int i) { return i / (W * W) - 1; }
    static int ly(int i) { return (i / W) % W - 1; }
    static int lz(int i) { return i % W - 1; }

    static boolean inBox(int lx, int ly, int lz) {
        return lx >= 0 && lx < 16 && ly >= 0 && ly < 16 && lz >= 0 && lz < 16;
    }

    static boolean inGrid(int lx, int ly, int lz) {
        return lx >= -1 && lx <= 16 && ly >= -1 && ly <= 16 && lz >= -1 && lz <= 16;
    }

    /** Face (0..5) of the face-adjacent box holding local (lx, ly, lz), or -1 (inside, or edge/corner/farther). */
    static int faceOf(int lx, int ly, int lz) {
        int bx = Math.floorDiv(lx, 16), by = Math.floorDiv(ly, 16), bz = Math.floorDiv(lz, 16);
        int off = Math.abs(bx) + Math.abs(by) + Math.abs(bz);
        if (off != 1 || Math.abs(bx) > 1 || Math.abs(by) > 1 || Math.abs(bz) > 1) return -1;
        if (bx != 0) return bx < 0 ? 0 : 1;
        if (by != 0) return by < 0 ? 2 : 3;
        return bz < 0 ? 4 : 5;
    }

    /** Face-plane coordinate u of a local position (see {@link Door}). */
    static int u(int face, int lx, int ly, int lz) {
        return face < 2 ? lz : lx;
    }

    static int v(int face, int lx, int ly, int lz) {
        return face < 2 ? ly : (face < 4 ? lz : ly);
    }

    BoxGrid(SectionKey key, CellMoves moves) {
        this.key = key;
        ox = key.minX();
        oy = key.minY();
        oz = key.minZ();
        for (int f = 0; f < 6; f++) crossings.add(new ArrayList<>());
        for (int i = 0; i < N; i++) standable[i] = moves.standable(ox + lx(i), oy + ly(i), oz + lz(i));
        int[] from = new int[1024], to = new int[1024];
        double[] cost = new double[1024];
        int[] m = {0};
        Object[] bufs = {from, to, cost};
        for (int i = 0; i < N; i++) {
            if (!standable[i]) continue;
            final int si = i, sx = lx(i), sy = ly(i), sz = lz(i);
            final boolean srcIn = inBox(sx, sy, sz);
            final int srcFace = srcIn ? -1 : faceOf(sx, sy, sz);
            moves.forEachMove(ox + sx, oy + sy, oz + sz, (wx, wy, wz, ticks) -> {
                if (!(ticks > 0 && ticks < Double.POSITIVE_INFINITY)) return;
                int tx = wx - ox, ty = wy - oy, tz = wz - oz;
                boolean tIn = inBox(tx, ty, tz);
                if (inGrid(tx, ty, tz)) {
                    int ti = idx(tx, ty, tz);
                    int k = m[0]++;
                    if (k == ((int[]) bufs[0]).length) {
                        int n2 = k * 2;
                        bufs[0] = Arrays.copyOf((int[]) bufs[0], n2);
                        bufs[1] = Arrays.copyOf((int[]) bufs[1], n2);
                        bufs[2] = Arrays.copyOf((double[]) bufs[2], n2);
                    }
                    ((int[]) bufs[0])[k] = si;
                    ((int[]) bufs[1])[k] = ti;
                    ((double[]) bufs[2])[k] = ticks;
                }
                if (srcIn && !tIn) {
                    int f = faceOf(tx, ty, tz);
                    if (f >= 0) crossings.get(f).add(new Crossing(f, true, si,
                            u(f, sx, sy, sz), v(f, sx, sy, sz), u(f, tx, ty, tz), v(f, tx, ty, tz), ticks));
                } else if (!srcIn && tIn && srcFace >= 0) {
                    int f = srcFace;
                    crossings.get(f).add(new Crossing(f, false, idx(tx, ty, tz),
                            u(f, tx, ty, tz), v(f, tx, ty, tz), u(f, sx, sy, sz), v(f, sx, sy, sz), ticks));
                }
            });
        }
        from = (int[]) bufs[0];
        to = (int[]) bufs[1];
        cost = (double[]) bufs[2];
        int e = m[0];
        fStart = new int[N + 1];
        rStart = new int[N + 1];
        for (int k = 0; k < e; k++) {
            fStart[from[k] + 1]++;
            rStart[to[k] + 1]++;
        }
        for (int i = 0; i < N; i++) {
            fStart[i + 1] += fStart[i];
            rStart[i + 1] += rStart[i];
        }
        fTo = new int[e];
        fCost = new double[e];
        rFrom = new int[e];
        rCost = new double[e];
        int[] fp = Arrays.copyOf(fStart, N), rp = Arrays.copyOf(rStart, N);
        for (int k = 0; k < e; k++) {
            int a = fp[from[k]]++;
            fTo[a] = to[k];
            fCost[a] = cost[k];
            int b = rp[to[k]]++;
            rFrom[b] = from[k];
            rCost[b] = cost[k];
        }
    }

    /** Multi-source Dijkstra; {@code reverse} walks the moves backwards (distance TO the sources). */
    double[] dijkstra(int[] sources, double[] startCost, boolean reverse) {
        double[] dist = new double[N];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        MinHeap h = new MinHeap(256);
        for (int s = 0; s < sources.length; s++) {
            int i = sources[s];
            double c = startCost == null ? 0 : startCost[s];
            if (c < dist[i]) {
                dist[i] = c;
                h.push(c, i);
            }
        }
        int[] st = reverse ? rStart : fStart, nb = reverse ? rFrom : fTo;
        double[] cs = reverse ? rCost : fCost;
        while (!h.isEmpty()) {
            double d = h.peekKey();
            int i = h.pop();
            if (d > dist[i]) continue;
            for (int k = st[i]; k < st[i + 1]; k++) {
                int j = nb[k];
                double nd = d + cs[k];
                if (nd < dist[j]) {
                    dist[j] = nd;
                    h.push(nd, j);
                }
            }
        }
        return dist;
    }

    /** Walk-grid hash: which of the 4096 box cells are standable. */
    long walkHash() {
        long h = 0xcbf29ce484222325L;
        long bits = 0;
        int n = 0;
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++) {
                    if (standable[idx(x, y, z)]) bits |= 1L << n;
                    if (++n == 64) {
                        h = RouteHashes.mixInt(RouteHashes.mixInt(h, (int) bits), (int) (bits >>> 32));
                        bits = 0;
                        n = 0;
                    }
                }
        return h;
    }

    int worldIdx(int x, int y, int z) {
        int lx = x - ox, ly = y - oy, lz = z - oz;
        return inGrid(lx, ly, lz) ? idx(lx, ly, lz) : -1;
    }

    /** True when the box cell {@code i} lies within door d's bounds and on its mask (the door's own cells). */
    boolean onDoor(int i, Door d) {
        int lx = lx(i), ly = ly(i), lz = lz(i);
        if (!inBox(lx, ly, lz)) return false;
        int x = ox + lx, y = oy + ly, z = oz + lz;
        if (x < d.minX() || x > d.maxX() || y < d.minY() || y > d.maxY() || z < d.minZ() || z > d.maxZ()) return false;
        return d.maskBit(u(d.face(), lx, ly, lz), v(d.face(), lx, ly, lz));
    }
}
