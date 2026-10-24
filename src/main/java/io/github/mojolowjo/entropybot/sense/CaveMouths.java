package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.39.z44 (CAVE_DETECTION option 1, owner: "it seems most like how a player would see it"): cave mouths from the surface
 * shape, confirmed by sight. Three states (rule 15):
 * <ul>
 * <li>CANDIDATE: a column of a loaded chunk whose WORLD_SURFACE height lies {@code cave.holeDepth}+ below ground on both
 *     sides along x and along z within {@code cave.holeReach} columns (a hole), or along one axis for a run of
 *     {@code cave.ravineRun}+ such columns (a ravine). Only the chunk's 256 heights are read (the surface is what a player
 *     sees from above); kept in memory per loaded chunk, never a fact (rule 17), dropped with the chunk.</li>
 * <li>SEEN: a ray from the eyes (the shared Seen budget) reached the candidate's cell or ran on into the hole and ended on a
 *     cell under its column's surface, and that cell (or the air cell under it) has sky light at most {@code cave.maxSky}
 *     (it goes underground, not a surface dip): a {@link Sightings} record "mouth" (sightings.json caves map).</li>
 * <li>GONE: the bot stands by the mouth and its cell is not air: the record is forgotten.</li>
 * </ul>
 * Pure (JUnit: Z44CaveMouthTest). Loader notes: none (the heights and the rays are read in ChunkPass / SightingsRuntime).
 */
public final class CaveMouths {
    public enum State { CANDIDATE, SEEN, GONE }

    public static int holeDepth() { return Tuning.i("cave.holeDepth"); }
    public static int holeReach() { return Tuning.i("cave.holeReach"); }
    public static int ravineRun() { return Tuning.i("cave.ravineRun"); }
    public static int maxSky() { return Tuning.i("cave.maxSky"); }

    /** A candidate: the lowest open cell at the top of the hole (the air cell over its floor), and its shape. */
    public record Candidate(int x, int y, int z, String shape) {}

    /**
     * The candidate rule over one chunk's heights. {@code top[lx + 16 * lz]} = the WORLD_SURFACE top block Y of the column
     * (the cell over it is the first air). Edge columns compare with the columns inside the chunk only. Columns of one hole
     * within {@link Sightings#merge()} are one candidate: the lowest.
     */
    public static List<Candidate> candidates(int cx, int cz, int[] top) {
        int d = holeDepth(), r = holeReach();
        int[] axis = new int[256];                        // bit 1: walled along x, bit 2: walled along z
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int h = top[lx + 16 * lz], need = h + d;
                boolean xs = high(top, lx, lz, -1, 0, r, need) && high(top, lx, lz, 1, 0, r, need);
                boolean zs = high(top, lx, lz, 0, -1, r, need) && high(top, lx, lz, 0, 1, r, need);
                axis[lx + 16 * lz] = (xs ? 1 : 0) | (zs ? 2 : 0);
            }
        List<Candidate> raw = new ArrayList<>();
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int a = axis[lx + 16 * lz];
                if (a == 0) continue;
                String shape;
                if (a == 3) shape = "hole";
                else {
                    // a ravine: walled along one axis, the run goes along the other
                    int dx = a == 1 ? 0 : 1, dz = a == 1 ? 1 : 0;
                    int run = 1 + runOf(axis, lx, lz, dx, dz, a) + runOf(axis, lx, lz, -dx, -dz, a);
                    if (run < ravineRun()) continue;
                    shape = "ravine";
                }
                raw.add(new Candidate((cx << 4) + lx, top[lx + 16 * lz] + 1, (cz << 4) + lz, shape));
            }
        raw.sort((p, q) -> Integer.compare(p.y(), q.y()));
        List<Candidate> out = new ArrayList<>();
        long m2 = (long) Sightings.merge() * Sightings.merge();
        for (Candidate c : raw) {
            boolean near = false;
            for (Candidate o : out) {
                long dx = o.x() - c.x(), dz = o.z() - c.z();
                if (dx * dx + dz * dz <= m2) { near = true; break; }
            }
            if (!near) out.add(c);
        }
        return out;
    }

    private static boolean high(int[] top, int lx, int lz, int dx, int dz, int r, int need) {
        for (int k = 1; k <= r; k++) {
            int x = lx + dx * k, z = lz + dz * k;
            if (x < 0 || x > 15 || z < 0 || z > 15) return false;
            if (top[x + 16 * z] >= need) return true;
        }
        return false;
    }

    private static int runOf(int[] axis, int lx, int lz, int dx, int dz, int a) {
        int n = 0;
        for (int x = lx + dx, z = lz + dz; x >= 0 && x <= 15 && z >= 0 && z <= 15 && (axis[x + 16 * z] & a) != 0; x += dx, z += dz) n++;
        return n;
    }

    /**
     * The confirmation rule. {@code reached}: the ray reached the cell (nothing stood between); {@code underSurface}: the
     * cell lies under its column's surface (inside the hole's walls or the candidate cell itself); the cell is air with sky
     * light at most {@link #maxSky()}, or the air cell under it is. True: the candidate is a cave mouth.
     */
    public static boolean goesUnder(boolean reached, boolean underSurface, boolean air, int sky, boolean belowAir, int belowSky) {
        if (!reached || !underSurface) return false;
        return (air && sky <= maxSky()) || (belowAir && belowSky <= maxSky());
    }

    /** The state of a place: a mouth record known or not; the bot standing by it and its cell air or not. */
    public static State state(boolean recorded, boolean standingBy, boolean cellAir) {
        if (!recorded) return State.CANDIDATE;
        return standingBy && !cellAir ? State.GONE : State.SEEN;
    }

    // ---- the candidates of the loaded chunks (memory only) ----

    private final Map<Long, List<Candidate>> byChunk = new HashMap<>();

    public synchronized void put(int cx, int cz, List<Candidate> l) {
        if (l == null || l.isEmpty()) byChunk.remove(ChunkSummary.key(cx, cz));
        else byChunk.put(ChunkSummary.key(cx, cz), List.copyOf(l));
    }

    public synchronized void drop(int cx, int cz) { byChunk.remove(ChunkSummary.key(cx, cz)); }

    public synchronized void clear() { byChunk.clear(); }

    public synchronized int count() {
        int n = 0;
        for (List<Candidate> l : byChunk.values()) n += l.size();
        return n;
    }

    /** The candidates within r (3D) of x y z, nearest first. */
    public synchronized List<Candidate> near(double x, double y, double z, int r) {
        List<Candidate> out = new ArrayList<>();
        double r2 = (double) r * r;
        for (List<Candidate> l : byChunk.values())
            for (Candidate c : l) if (d2(c, x, y, z) <= r2) out.add(c);
        out.sort((p, q) -> Double.compare(d2(p, x, y, z), d2(q, x, y, z)));
        return out;
    }

    private static double d2(Candidate c, double x, double y, double z) {
        double dx = c.x() + 0.5 - x, dy = c.y() + 0.5 - y, dz = c.z() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
