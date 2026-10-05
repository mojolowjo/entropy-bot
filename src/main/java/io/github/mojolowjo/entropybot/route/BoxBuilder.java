package io.github.mojolowjo.entropybot.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds one box from its moves: doors (grouped crossing moves per face) and the crossing matrix (one Dijkstra per
 * enterable door over the box plus a one-cell margin). See {@link Door} and {@link SectionRecord} for the rules.
 * Pure function of the input; runs on a worker. 5832 positions x ~22 moves: a few ms with fakes.
 *
 * <p>Grouping: per face, the face-plane points of the box-side ends of the crossing moves are joined (union-find) with
 * their plane neighbours (4-neighbours, plus the diagonal neighbours on the side faces, where v is the height, so a
 * run of steps stays one door), but only inside the same 4x4 piece of the face ({@link #PIECE}). Each group is one
 * door; its mask holds the plane points of its box-side cells.
 *
 * <p>Why pieces (a change against the plan's "connected run = one door"): a crossing is the cheapest over all of a
 * door's cells, so a 16-wide door lets a route slide along it for free; chained over several boxes that made a swim
 * across a lake look cheaper than the walk round it (RouterTest). With 4x4 pieces the slide is at most 3 blocks per
 * door (the way HPA* bounds an entrance's width). A flat box gets 16 doors instead of 4.
 *
 * <p>Crossings are measured between door <i>points</i>, as in HPA*: from the entry cell nearest the middle of door i to
 * the exit cell nearest the middle of door j, plus that cell's exit move (any exit cell of j when the middle one can't
 * be reached). "Cheapest over all cells" at both ends was a lower bound, but chained box to box it let a route enter
 * at one end of a door and leave the next box from the other end for free, so a swim along a box boundary looked
 * three times cheaper than it is. With 4-cell pieces a point is at most 2 blocks from any cell of its door.
 */
public final class BoxBuilder {
    /** Largest door, in face-plane cells each way. */
    public static final int PIECE = 4;

    private BoxBuilder() {
    }

    /** Builds the record. Throws only on a broken {@link CellMoves} (the caller counts it). */
    public static SectionRecord build(BuildInput in) {
        return build(in.key(), new BoxGrid(in.key(), in.moves()), in.quality(), in.builtAt());
    }

    static SectionRecord build(SectionKey key, BoxGrid g, SectionRecord.Quality quality, long builtAt) {
        List<Door> doors = new ArrayList<>();
        List<int[]> exitOwn = new ArrayList<>();      // per door: grid idx of exit sources
        List<double[]> exitCost = new ArrayList<>();  // per door: exit move costs
        List<int[]> entryOwn = new ArrayList<>();     // per door: grid idx of entry targets
        for (int f = 0; f < 6; f++) {
            List<BoxGrid.Crossing> cs = g.crossings.get(f);
            if (cs.isEmpty()) continue;
            int[] parent = new int[256];
            boolean[] occ = new boolean[256];
            for (int i = 0; i < 256; i++) parent[i] = i;
            for (BoxGrid.Crossing c : cs) occ[c.ou() + 16 * c.ov()] = true;
            boolean side = f != 2 && f != 3;
            for (int p = 0; p < 256; p++) {
                if (!occ[p]) continue;
                int pu = p & 15, pv = p >> 4;
                for (int du = -1; du <= 1; du++)
                    for (int dv = -1; dv <= 1; dv++) {
                        if (du == 0 && dv == 0) continue;
                        if (du != 0 && dv != 0 && !side) continue;
                        int qu = pu + du, qv = pv + dv;
                        if (qu < 0 || qu > 15 || qv < 0 || qv > 15) continue;
                        if (qu / PIECE != pu / PIECE || qv / PIECE != pv / PIECE) continue; // pieces of 4x4
                        int q = qu + 16 * qv;
                        if (occ[q]) union(parent, p, q);
                    }
            }
            // one door per root, in order of the smallest plane point
            int[] doorOfRoot = new int[256];
            Arrays.fill(doorOfRoot, -1);
            List<Integer> roots = new ArrayList<>();
            for (int p = 0; p < 256; p++) {
                if (!occ[p]) continue;
                int r = find(parent, p);
                if (doorOfRoot[r] < 0) {
                    doorOfRoot[r] = roots.size();
                    roots.add(r);
                }
            }
            int nd = roots.size();
            long[][] masks = new long[nd][4];
            List<List<BoxGrid.Crossing>> per = new ArrayList<>();
            for (int d = 0; d < nd; d++) per.add(new ArrayList<>());
            for (BoxGrid.Crossing c : cs) {
                int d = doorOfRoot[find(parent, c.ou() + 16 * c.ov())];
                per.get(d).add(c);
                int a = c.ou() + 16 * c.ov();
                masks[d][a >> 6] |= 1L << (a & 63); // box side only: far ends made neighbouring pieces "match"
            }
            for (int d = 0; d < nd; d++) {
                List<BoxGrid.Crossing> list = per.get(d);
                int ne = 0, nn = 0;
                for (BoxGrid.Crossing c : list) if (c.exit()) ne++; else nn++;
                int[] eo = new int[ne];
                double[] ec = new double[ne];
                int[] en = new int[nn];
                int a = 0, b = 0;
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                double sx = 0, sy = 0, sz = 0;
                for (BoxGrid.Crossing c : list) {
                    if (c.exit()) {
                        eo[a] = c.own();
                        ec[a++] = c.cost();
                    } else {
                        en[b++] = c.own();
                    }
                    int x = g.ox + BoxGrid.lx(c.own()), y = g.oy + BoxGrid.ly(c.own()), z = g.oz + BoxGrid.lz(c.own());
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                    sx += x; sy += y; sz += z;
                }
                double cx = sx / list.size(), cy = sy / list.size(), cz = sz / list.size();
                int best = list.get(0).own();
                double bestD = Double.POSITIVE_INFINITY;
                for (BoxGrid.Crossing c : list) {
                    double dx = g.ox + BoxGrid.lx(c.own()) - cx, dy = g.oy + BoxGrid.ly(c.own()) - cy,
                            dz = g.oz + BoxGrid.lz(c.own()) - cz;
                    double dd = dx * dx + dy * dy + dz * dz;
                    if (dd < bestD) {
                        bestD = dd;
                        best = c.own();
                    }
                }
                doors.add(new Door(f, masks[d],
                        g.ox + BoxGrid.lx(best), g.oy + BoxGrid.ly(best), g.oz + BoxGrid.lz(best),
                        minX, minY, minZ, maxX, maxY, maxZ, ne > 0, nn > 0));
                exitOwn.add(eo);
                exitCost.add(ec);
                entryOwn.add(en);
            }
        }
        int n = doors.size();
        char[] crossing = new char[n * n];
        Arrays.fill(crossing, SectionRecord.IMPOSSIBLE);
        // door points (HPA*-style): the entry cell and the exit cell nearest the middle of each door
        int[] exitPt = new int[n];
        double[] exitPtCost = new double[n];
        for (int j = 0; j < n; j++) {
            int[] eo = exitOwn.get(j);
            exitPt[j] = middle(g, eo);
            exitPtCost[j] = Double.POSITIVE_INFINITY;
            for (int k = 0; k < eo.length; k++)
                if (eo[k] == exitPt[j]) exitPtCost[j] = Math.min(exitPtCost[j], exitCost.get(j)[k]);
        }
        for (int i = 0; i < n; i++) {
            int[] src = entryOwn.get(i);
            if (src.length == 0) continue;
            double[] dist = g.dijkstra(new int[]{middle(g, src)}, null, false);
            for (int j = 0; j < n; j++) {
                double best = exitPt[j] < 0 ? Double.POSITIVE_INFINITY : dist[exitPt[j]] + exitPtCost[j];
                if (!(best < Double.POSITIVE_INFINITY)) {
                    // the door's middle is cut off from here: any of its exits (the piece is not one walkable run)
                    int[] eo = exitOwn.get(j);
                    double[] ec = exitCost.get(j);
                    for (int k = 0; k < eo.length; k++) best = Math.min(best, dist[eo[k]] + ec[k]);
                }
                crossing[i * n + j] = SectionRecord.quarterTicks(best);
            }
        }
        return new SectionRecord(key, quality, builtAt, g.walkHash(), doors, crossing);
    }

    /**
     * Builds and stores the box, unless {@code in.allowBreak()} (review R5: counted as refused, not stored). Never
     * throws: an exception is counted and logged. Returns true when stored.
     */
    public static boolean buildInto(BuildInput in, RouteStore store, RouteCounters counters, RouteLog log) {
        try {
            if (in.allowBreak()) {
                if (counters != null) counters.refusedBreaking();
                return false;
            }
            SectionRecord r = build(in);
            store.put(r);
            if (counters != null) counters.boxBuilt();
            return true;
        } catch (RuntimeException | StackOverflowError e) {
            if (counters != null) counters.workerException("building " + in.key(), e, log);
            else if (log != null) log.error("building " + in.key(), e);
            return false;
        }
    }

    /** The cell of {@code cells} (grid indices, duplicates allowed) nearest their middle, or -1 for none. */
    private static int middle(BoxGrid g, int[] cells) {
        if (cells.length == 0) return -1;
        double sx = 0, sy = 0, sz = 0;
        for (int c : cells) {
            sx += BoxGrid.lx(c);
            sy += BoxGrid.ly(c);
            sz += BoxGrid.lz(c);
        }
        sx /= cells.length;
        sy /= cells.length;
        sz /= cells.length;
        int best = cells[0];
        double bd = Double.POSITIVE_INFINITY;
        for (int c : cells) {
            double dx = BoxGrid.lx(c) - sx, dy = BoxGrid.ly(c) - sy, dz = BoxGrid.lz(c) - sz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bd || (d == bd && c < best)) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    private static int find(int[] p, int a) {
        while (p[a] != a) {
            p[a] = p[p[a]];
            a = p[a];
        }
        return a;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[Math.max(ra, rb)] = Math.min(ra, rb);
    }
}
