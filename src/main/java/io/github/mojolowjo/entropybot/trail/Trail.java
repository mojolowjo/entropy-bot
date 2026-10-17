package io.github.mojolowjo.entropybot.trail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.32.2 (PATHS_PLAN P2): the walked trail of one world: a point every {@link #STEP} blocks moved (with the dimension
 * and whether the sky was open there), the newest {@link #MAX} kept (about 80 km). Retrace hops back along it, and
 * boxes along it for the route map. Plain Java (JUnit). Loader API: none.
 */
public final class Trail {
    public static final int STEP = 4, MAX = 20000;

    public record P(String dim, int x, int y, int z, boolean sky) {
        String line() { return dim + " " + x + " " + y + " " + z + " " + (sky ? 1 : 0); }
    }

    private final ArrayDeque<P> points = new ArrayDeque<>();
    private boolean dirty;
    private long added;

    /** Adds the spot when it is STEP or more from the last one (or in another dimension). True when added. */
    public synchronized boolean offer(String dim, int x, int y, int z, boolean sky) {
        P last = points.peekLast();
        if (last != null && last.dim.equals(dim)) {
            long dx = x - last.x, dy = y - last.y, dz = z - last.z;
            if (dx * dx + dy * dy + dz * dz < (long) STEP * STEP) return false;
        }
        points.addLast(new P(dim, x, y, z, sky));
        while (points.size() > MAX) points.removeFirst();
        dirty = true;
        added++;
        return true;
    }

    public synchronized int size() { return points.size(); }

    public synchronized List<P> points() { return new ArrayList<>(points); }

    public synchronized boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public synchronized String counters() { return "trail: " + points.size() + " points (" + added + " added this session)"; }

    public synchronized String toText() {
        StringBuilder sb = new StringBuilder();
        for (P p : points) sb.append(p.line()).append('\n');
        return sb.toString();
    }

    /** Lines "dim x y z sky"; broken lines skipped (counted in skipped[0]). */
    public static Trail fromText(String text, int[] skipped) {
        Trail t = new Trail();
        if (text == null) return t;
        for (String l : text.split("\n")) {
            String[] w = l.trim().split(" ");
            if (w.length != 5) { if (!l.isBlank() && skipped != null) skipped[0]++; continue; }
            try {
                t.points.addLast(new P(w[0], Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]), w[4].equals("1")));
            } catch (NumberFormatException e) {
                if (skipped != null) skipped[0]++;
            }
        }
        while (t.points.size() > MAX) t.points.removeFirst();
        return t;
    }

    /**
     * The way back: from the newest trail point within {@code near} of x y z, back along the trail in hops of about
     * {@code hop} blocks, until a point with open sky (included, the last hop) or {@code maxBack} points. Empty when the
     * bot is nowhere near its trail, or the trail never had sky.
     */
    public synchronized List<int[]> retrace(String dim, int x, int y, int z, int near, int hop, int maxBack) {
        List<P> l = new ArrayList<>(points);
        int start = -1;
        for (int i = l.size() - 1; i >= 0 && i >= l.size() - 2000; i--) {
            P p = l.get(i);
            if (!p.dim.equals(dim)) continue;
            long dx = p.x - x, dy = p.y - y, dz = p.z - z;
            if (dx * dx + dy * dy + dz * dz <= (long) near * near) { start = i; break; }
        }
        List<int[]> out = new ArrayList<>();
        if (start < 0) return out;
        P lastHop = l.get(start);
        boolean sky = false;
        for (int i = start - 1, n = 0; i >= 0 && n < maxBack; i--, n++) {
            P p = l.get(i);
            if (!p.dim.equals(dim)) break;
            long dx = p.x - lastHop.x, dy = p.y - lastHop.y, dz = p.z - lastHop.z;
            if (p.sky || dx * dx + dy * dy + dz * dz >= (long) hop * hop) {
                out.add(new int[]{p.x, p.y, p.z});
                lastHop = p;
            }
            if (p.sky) { sky = true; break; }
        }
        if (!sky) out.clear();
        return out;
    }

    /**
     * Boxes along the trail for the route map: the trail's points in one dimension grouped into {@code size} x size
     * column squares (x1 z1 x2 z2 y1 y2, y = the points' range +- 8), the most visited first, at most {@code max}.
     */
    public synchronized List<int[]> boxes(String dim, int size, int max) {
        Map<Long, int[]> cells = new LinkedHashMap<>();       // key -> {x1,z1,x2,z2,ymin,ymax,count}
        for (P p : points) {
            if (!p.dim.equals(dim)) continue;
            int cx = Math.floorDiv(p.x, size), cz = Math.floorDiv(p.z, size);
            long k = ((long) cx << 32) ^ (cz & 0xffffffffL);
            int[] c = cells.computeIfAbsent(k, kk -> new int[]{cx * size, cz * size, cx * size + size - 1, cz * size + size - 1, p.y, p.y, 0});
            c[4] = Math.min(c[4], p.y);
            c[5] = Math.max(c[5], p.y);
            c[6]++;
        }
        List<int[]> l = new ArrayList<>(cells.values());
        l.sort((a, b) -> Integer.compare(b[6], a[6]));
        List<int[]> out = new ArrayList<>();
        for (Iterator<int[]> it = l.iterator(); it.hasNext() && out.size() < max; ) {
            int[] c = it.next();
            out.add(new int[]{c[0], c[1], c[2], c[3], c[4] - 8, c[5] + 8});
        }
        return out;
    }

    /**
     * Corridors between areas for the route map: each area (x1 z1 x2 z2) to its nearest other area, column squares of
     * {@code width} every {@code width} blocks along the line between their centres (y1..y2 given), at most {@code max}.
     */
    public static List<int[]> corridors(List<int[]> areas, int width, int y1, int y2, int max) {
        List<int[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < areas.size() && out.size() < max; i++) {
            int[] a = areas.get(i);
            int best = -1;
            long bd = Long.MAX_VALUE;
            double ax = (a[0] + a[2]) / 2.0, az = (a[1] + a[3]) / 2.0;
            for (int j = 0; j < areas.size(); j++) {
                if (j == i) continue;
                int[] b = areas.get(j);
                double bx = (b[0] + b[2]) / 2.0, bz = (b[1] + b[3]) / 2.0;
                long d = (long) ((ax - bx) * (ax - bx) + (az - bz) * (az - bz));
                if (d < bd) { bd = d; best = j; }
            }
            if (best < 0) continue;
            String key = Math.min(i, best) + "-" + Math.max(i, best);
            if (!seen.add(key)) continue;
            int[] b = areas.get(best);
            double bx = (b[0] + b[2]) / 2.0, bz = (b[1] + b[3]) / 2.0;
            double len = Math.sqrt(bd);
            int steps = Math.max(1, (int) Math.ceil(len / width));
            for (int s = 1; s < steps && out.size() < max; s++) {
                int x = (int) Math.round(ax + (bx - ax) * s / steps), z = (int) Math.round(az + (bz - az) * s / steps);
                out.add(new int[]{x - width / 2, z - width / 2, x + width / 2 - 1, z + width / 2 - 1, y1, y2});
            }
        }
        return out;
    }
}