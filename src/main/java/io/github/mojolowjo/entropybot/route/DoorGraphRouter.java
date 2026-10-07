package io.github.mojolowjo.entropybot.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link Router}: a backward Dijkstra over the door graph of one dim.
 *
 * <p>Nodes: for every door d of box B, {@code in(B,d)} ("just entered B through d") and {@code out(B,e)} ("just left
 * B through e"). Edges, read backwards from the goal:
 * <ul>
 *   <li>{@code in(B,d) -> out(B,e)} costs the stored crossing {@code ticks(d,e)};</li>
 *   <li>{@code out(B,e) -> in(N,g)}, N the face neighbour, g on the opposite face and enterable: 0 when the two
 *       doors' masks share a cell; a door with no such match at all links to the nearest enterable door only, at
 *       straight-line cost (doors the two boxes saw differently, e.g. one of them is stale). See {@link #linkTo}.</li>
 * </ul>
 * Seeds: {@code in(G,d)} for the goal box G = the cost from the door's cells to the goal (a reverse Dijkstra in G's grid
 * when {@link RouteRequest#moves()} is given, else straight-line x costHeuristic). Stale boxes take part as they are.
 */
final class DoorGraphRouter implements Router {
    private final RouteStore store;
    private final RouteCounters counters;
    private final RouteLog log;

    DoorGraphRouter(RouteStore store, RouteCounters counters, RouteLog log) {
        this.store = store;
        this.counters = counters;
        this.log = log;
    }

    @Override
    public RoutePlan plan(RouteRequest r) {
        long t0 = System.nanoTime();
        try {
            RoutePlan p = plan0(r, t0);
            if (counters != null) counters.planMade();
            return p;
        } catch (RuntimeException | StackOverflowError e) {
            if (counters != null) counters.workerException("planning " + r.start() + " -> " + r.goal(), e, log);
            else if (log != null) log.error("planning", e);
            return new RoutePlan(RoutePlan.Status.ERROR, Double.POSITIVE_INFINITY, List.of(), CostToGo.empty(), 0,
                    micros(t0), "error: " + e);
        }
    }

    private static long micros(long t0) {
        return (System.nanoTime() - t0) / 1000;
    }

    private RoutePlan plan0(RouteRequest r, long t0) {
        if (r.start() == null || r.goal() == null || !(r.costHeuristic() > 0))
            return new RoutePlan(RoutePlan.Status.BAD_REQUEST, Double.POSITIVE_INFINITY, List.of(), null, 0,
                    micros(t0), "bad request");
        final int dim = r.dim();
        final double coef = r.costHeuristic();
        // ---- index the boxes of this dim
        List<SectionRecord> recs = new ArrayList<>();
        store.forEach(rec -> {
            if (rec.key().dim() == dim) recs.add(rec);
        });
        Map<SectionKey, Integer> boxIdx = new HashMap<>(recs.size() * 2);
        int[] base = new int[recs.size() + 1];
        for (int b = 0; b < recs.size(); b++) {
            boxIdx.put(recs.get(b).key(), b);
            base[b + 1] = base[b] + 2 * recs.get(b).doorCount();
        }
        int nodes = base[recs.size()];
        SectionKey gKey = SectionKey.of(dim, r.goal().x(), r.goal().y(), r.goal().z());
        Integer gB = boxIdx.get(gKey);
        if (gB == null)
            return new RoutePlan(RoutePlan.Status.NO_GOAL_BOX, Double.POSITIVE_INFINITY, List.of(), null, 0,
                    micros(t0), "goal box " + gKey + " not built yet");
        SectionRecord gRec = recs.get(gB);

        // ---- seeds in the goal box
        double[] dist = new double[nodes];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        int[] succ = new int[nodes];
        Arrays.fill(succ, -1);
        MinHeap heap = new MinHeap(1024);
        double[] goalSeed = goalCosts(r, gKey, gRec, coef);
        for (int d = 0; d < gRec.doorCount(); d++) {
            if (!gRec.doors().get(d).canEnter() || !(goalSeed[d] < Double.POSITIVE_INFINITY)) continue;
            int in = base[gB] + 2 * d;
            dist[in] = goalSeed[d];
            heap.push(goalSeed[d], in);
        }
        // ---- backward Dijkstra (0.23.3: a penalised leaving door costs more, EdgePenalties)
        final long nowMs = System.currentTimeMillis();
        final boolean pens = !io.github.mojolowjo.entropybot.move.EdgePenalties.GLOBAL.isEmpty();
        int settled = 0;
        while (!heap.isEmpty()) {
            double v = heap.peekKey();
            int node = heap.pop();
            if (v > dist[node]) continue;
            settled++;
            int b = upperBox(base, node);
            int d = (node - base[b]) >> 1;
            SectionRecord rec = recs.get(b);
            if (((node - base[b]) & 1) == 0) {
                // in(B,d): reached from out(P,e) of the face neighbour P across d's face
                Door g = rec.doors().get(d);
                Integer pB = boxIdx.get(rec.key().neighbour(g.face()));
                if (pB == null) continue;
                SectionRecord pRec = recs.get(pB);
                int opp = Door.opposite(g.face());
                for (int e = 0; e < pRec.doorCount(); e++) {
                    Door de = pRec.doors().get(e);
                    if (de.face() != opp || !de.canLeave()) continue;
                    double l = linkTo(de, rec, d, coef);
                    if (!(l < Double.POSITIVE_INFINITY)) continue;
                    int out = base[pB] + 2 * e + 1;
                    double nv = v + l;
                    if (nv < dist[out]) {
                        dist[out] = nv;
                        succ[out] = node;
                        heap.push(nv, out);
                    }
                }
            } else {
                // out(B,e): reached from in(B,d) for every d with a crossing to e
                int n = rec.doorCount();
                double pen = pens ? penalty(rec, d, nowMs) : 0;
                for (int i = 0; i < n; i++) {
                    if (!rec.doors().get(i).canEnter()) continue;
                    double c = rec.ticks(i, d);
                    if (!(c < Double.POSITIVE_INFINITY)) continue;
                    c += pen;
                    int in = base[b] + 2 * i;
                    double nv = v + c;
                    if (nv < dist[in]) {
                        dist[in] = nv;
                        succ[in] = node;
                        heap.push(nv, in);
                    }
                }
            }
        }
        // ---- the table
        Map<SectionKey, CostToGo.BoxCosts> boxes = new HashMap<>(recs.size() * 2);
        for (int b = 0; b < recs.size(); b++) {
            SectionRecord rec = recs.get(b);
            if (rec.empty()) continue;
            double[] ex = new double[rec.doorCount()];
            for (int e = 0; e < ex.length; e++) ex[e] = dist[base[b] + 2 * e + 1];
            boxes.put(rec.key(), new CostToGo.BoxCosts(rec.key(), store.isStale(rec.key()),
                    rec.doors().toArray(new Door[0]), ex));
        }
        CostToGo table = new CostToGo(dim, gKey, r.goal(), boxes);

        // ---- the start
        SectionKey sKey = SectionKey.of(dim, r.start().x(), r.start().y(), r.start().z());
        if (sKey.equals(gKey))
            return new RoutePlan(RoutePlan.Status.OK, r.start().dist(r.goal()) * coef, List.of(), table, settled,
                    micros(t0), "ok (same box)");
        Integer sB = boxIdx.get(sKey);
        if (sB == null)
            return new RoutePlan(RoutePlan.Status.NO_START_BOX, Double.POSITIVE_INFINITY, List.of(), table, settled,
                    micros(t0), "start box " + sKey + " not built yet");
        SectionRecord sRec = recs.get(sB);
        double[] startCost = startCosts(r, sKey, sRec, coef);
        double best = Double.POSITIVE_INFINITY;
        int bestE = -1;
        for (int e = 0; e < sRec.doorCount(); e++) {
            if (!sRec.doors().get(e).canLeave()) continue;
            double v = startCost[e] + dist[base[sB] + 2 * e + 1] + (pens ? penalty(sRec, e, nowMs) : 0);
            if (v < best) {
                best = v;
                bestE = e;
            }
        }
        if (bestE < 0)
            return new RoutePlan(RoutePlan.Status.UNREACHABLE, Double.POSITIVE_INFINITY, List.of(), table, settled,
                    micros(t0), "no way from " + sKey + " to " + gKey + " in the map");
        List<RoutePlan.Step> path = new ArrayList<>();
        int node = base[sB] + 2 * bestE + 1;
        int guard = nodes + 1;
        while (node >= 0 && guard-- > 0) {
            int b = upperBox(base, node);
            int d = (node - base[b]) >> 1;
            if (((node - base[b]) & 1) == 1) {
                SectionRecord rec = recs.get(b);
                path.add(new RoutePlan.Step(rec.key(), d, rec.doors().get(d).rep()));
            }
            node = succ[node];
        }
        return new RoutePlan(RoutePlan.Status.OK, best, path, table, settled, micros(t0), "ok");
    }

    /** 0.23.3: the penalty on leaving rec through door d (EdgePenalties, keyed by the door's representative cell). */
    static double penalty(SectionRecord rec, int d, long nowMs) {
        Cell r = rec.doors().get(d).rep();
        if (r == null) return 0;
        return io.github.mojolowjo.entropybot.move.EdgePenalties.GLOBAL.penalty(
                io.github.mojolowjo.entropybot.move.EdgePenalties.key(rec.key().toString(), r.x(), r.y(), r.z()), nowMs);
    }

    /** The box b with base[b] <= node < base[b+1]. */
    private static int upperBox(int[] base, int node) {
        int lo = 0, hi = base.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (base[mid] <= node) lo = mid;
            else hi = mid - 1;
        }
        while (lo + 1 < base.length - 1 && base[lo + 1] <= node) lo++; // skip empty boxes sharing a base
        return lo;
    }

    /**
     * The link from leaving through {@code out} to entering box {@code nRec} through its door {@code g}: 0 when their
     * masks share a cell (both boxes saw the same crossing moves). A door that matches no enterable door of the
     * neighbour at all (the two boxes saw the face differently, e.g. one is stale) links only to the nearest one, at
     * straight-line cost. +infinity otherwise. (Linking every door at straight-line cost let routes slide along a face
     * at walking speed over water.)
     */
    static double linkTo(Door out, SectionRecord nRec, int g, double coef) {
        Door in = nRec.doors().get(g);
        if (masksOverlap(out.mask(), in.mask())) return 0;
        int opp = in.face();
        int nearest = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int k = 0; k < nRec.doorCount(); k++) {
            Door c = nRec.doors().get(k);
            if (c.face() != opp || !c.canEnter()) continue;
            if (masksOverlap(out.mask(), c.mask())) return Double.POSITIVE_INFINITY; // out has a real match
            double dist = distance(out, c);
            if (dist < best) {
                best = dist;
                nearest = k;
            }
        }
        return nearest == g ? Math.max(0, best - 1) * coef : Double.POSITIVE_INFINITY;
    }

    static boolean masksOverlap(long[] a, long[] b) {
        return (a[0] & b[0]) != 0 || (a[1] & b[1]) != 0 || (a[2] & b[2]) != 0 || (a[3] & b[3]) != 0;
    }

    private static double distance(Door out, Door in) {
        double dx = gap(out.minX(), out.maxX(), in.minX(), in.maxX());
        double dy = gap(out.minY(), out.maxY(), in.minY(), in.maxY());
        double dz = gap(out.minZ(), out.maxZ(), in.minZ(), in.maxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double gap(int aMin, int aMax, int bMin, int bMax) {
        if (aMax < bMin) return bMin - aMax;
        if (bMax < aMin) return aMin - bMax;
        return 0;
    }

    /** True when a mask bit of a lies on or next to (8 neighbours) a bit of b. */
    static boolean masksTouch(long[] a, long[] b) {
        for (int p = 0; p < 256; p++) {
            if ((a[p >> 6] & (1L << (p & 63))) == 0) continue;
            int u = p & 15, v = p >> 4;
            for (int du = -1; du <= 1; du++)
                for (int dv = -1; dv <= 1; dv++) {
                    int qu = u + du, qv = v + dv;
                    if (qu < 0 || qu > 15 || qv < 0 || qv > 15) continue;
                    int q = qu + 16 * qv;
                    if ((b[q >> 6] & (1L << (q & 63))) != 0) return true;
                }
        }
        return false;
    }

    /** Per door of the goal box: ticks from the door's cells to the goal (cells within goalRadius). */
    private double[] goalCosts(RouteRequest r, SectionKey gKey, SectionRecord gRec, double coef) {
        int n = gRec.doorCount();
        double[] out = new double[n];
        Cell goal = r.goal();
        if (r.moves() != null) {
            BoxGrid g = new BoxGrid(gKey, r.moves());
            int[] goals = cellsNear(g, goal, Math.max(0, r.goalRadius()));
            if (goals.length > 0) {
                double[] dist = g.dijkstra(goals, null, true);
                boolean any = false;
                for (int d = 0; d < n; d++) {
                    out[d] = minOnDoor(g, dist, gRec.doors().get(d));
                    any |= out[d] < Double.POSITIVE_INFINITY;
                }
                if (any) return out;
            }
        }
        for (int d = 0; d < n; d++)
            out[d] = Math.max(0, gRec.doors().get(d).distanceTo(goal.x(), goal.y(), goal.z()) - r.goalRadius()) * coef;
        return out;
    }

    /** Per door of the start box: ticks from the start cell to the door's cells. */
    private double[] startCosts(RouteRequest r, SectionKey sKey, SectionRecord sRec, double coef) {
        int n = sRec.doorCount();
        double[] out = new double[n];
        Cell s = r.start();
        if (r.moves() != null) {
            BoxGrid g = new BoxGrid(sKey, r.moves());
            int si = g.worldIdx(s.x(), s.y(), s.z());
            if (si >= 0 && g.standable[si]) {
                double[] dist = g.dijkstra(new int[]{si}, null, false);
                boolean any = false;
                for (int d = 0; d < n; d++) {
                    out[d] = minOnDoor(g, dist, sRec.doors().get(d));
                    any |= out[d] < Double.POSITIVE_INFINITY;
                }
                if (any) return out;
            }
        }
        for (int d = 0; d < n; d++) out[d] = sRec.doors().get(d).distanceTo(s.x(), s.y(), s.z()) * coef;
        return out;
    }

    private static double minOnDoor(BoxGrid g, double[] dist, Door d) {
        double best = Double.POSITIVE_INFINITY;
        for (int x = d.minX(); x <= d.maxX(); x++)
            for (int y = d.minY(); y <= d.maxY(); y++)
                for (int z = d.minZ(); z <= d.maxZ(); z++) {
                    int i = g.worldIdx(x, y, z);
                    if (i >= 0 && dist[i] < best && g.onDoor(i, d)) best = dist[i];
                }
        return best;
    }

    private static int[] cellsNear(BoxGrid g, Cell c, int radius) {
        List<Integer> out = new ArrayList<>();
        for (int x = c.x() - radius; x <= c.x() + radius; x++)
            for (int y = c.y() - radius; y <= c.y() + radius; y++)
                for (int z = c.z() - radius; z <= c.z() + radius; z++) {
                    double dx = x - c.x(), dy = y - c.y(), dz = z - c.z();
                    if (dx * dx + dy * dy + dz * dz > (double) radius * radius) continue;
                    int i = g.worldIdx(x, y, z);
                    if (i >= 0 && g.standable[i]) out.add(i);
                }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
}
