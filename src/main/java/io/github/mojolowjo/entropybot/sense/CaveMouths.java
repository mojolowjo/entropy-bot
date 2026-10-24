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
    public static int slopeMaxStep() { return Tuning.i("cave.slopeMaxStep"); }
    public static int slopeLen() { return Tuning.i("cave.slopeLen"); }
    public static int cliffDrop() { return Tuning.i("cave.cliffDrop"); }
    /** The columns within which the cliff side must rise (owner: "within 1-2 columns"). */
    public static int cliffReach() { return Tuning.i("cave.cliffReach"); }
    /** Rays confirm a candidate only within this many blocks of the eyes (owner: "when getting close"). */
    public static double confirmM() { return Tuning.get("cave.confirmM"); }

    /** The candidates a ray may confirm: within {@link #confirmM()} of the eyes (the rest get no ray). */
    public List<Candidate> toConfirm(double x, double y, double z) { return near(x, y, z, (int) Math.floor(confirmM())); }

    /**
     * 0.39.z46 (owner 2026-10-24: "a cylinder intersecting the ground at an angle: one side of the mouth slopes down
     * gently, the other side is a steep drop of at least 5 blocks ... any severe drop is also a potential ravine"): over one
     * chunk's WORLD_SURFACE heights, a MOUTH is a low column reached along one line by a gentle slope (each column at most
     * {@code cave.slopeMaxStep} lower, over {@code cave.slopeLen}+ columns, falling at least 1 in all) whose opposite side
     * rises {@code cave.cliffDrop}+ within 1-2 columns; a RAVINE is a column with such a rise on both sides of one line.
     * {@code top[lx + 16 * lz]} = the top block Y; edge columns compare with the chunk's own columns only. Columns of one
     * place within {@link Sightings#merge()} are one candidate: the deepest drop first, then the lowest.
     */
    public static List<Candidate> candidates(int cx, int cz, int[] top) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<Candidate> raw = new ArrayList<>();
        List<Integer> drops = new ArrayList<>();
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int h = top[lx + 16 * lz];
                String shape = null;
                int drop = 0;
                for (int[] d : dirs) {
                    int cliff = cliff(top, lx, lz, d[0], d[1], h);
                    if (cliff < cliffDrop()) continue;
                    int back = cliff(top, lx, lz, -d[0], -d[1], h);
                    if (back >= cliffDrop()) { shape = "ravine"; drop = Math.max(drop, Math.min(cliff, back)); }
                    else if (gentle(top, lx, lz, -d[0], -d[1], h)) { if (shape == null) shape = "mouth"; drop = Math.max(drop, cliff); }
                }
                if (shape == null) continue;
                raw.add(new Candidate((cx << 4) + lx, h + 1, (cz << 4) + lz, shape));
                drops.add(drop);
            }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) order.add(i);
        final List<Candidate> found = raw;
        order.sort((p, q) -> drops.get(p).equals(drops.get(q)) ? Integer.compare(found.get(p).y(), found.get(q).y()) : Integer.compare(drops.get(q), drops.get(p)));
        List<Candidate> sorted = new ArrayList<>();
        for (int i : order) sorted.add(found.get(i));
        raw = sorted;
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

    /** The most the ground rises within 1-2 columns along (dx, dz) over h (0: outside the chunk or lower). */
    private static int cliff(int[] top, int lx, int lz, int dx, int dz, int h) {
        int best = 0;
        for (int k = 1; k <= cliffReach(); k++) {
            int x = lx + dx * k, z = lz + dz * k;
            if (x < 0 || x > 15 || z < 0 || z > 15) break;
            best = Math.max(best, top[x + 16 * z] - h);
        }
        return best;
    }

    /** Along (dx, dz) the ground rises gently for slopeLen columns: each step 0..slopeMaxStep, at least 1 in all. */
    private static boolean gentle(int[] top, int lx, int lz, int dx, int dz, int h) {
        int prev = h;
        for (int k = 1; k <= slopeLen(); k++) {
            int x = lx + dx * k, z = lz + dz * k;
            if (x < 0 || x > 15 || z < 0 || z > 15) return false;
            int step = top[x + 16 * z] - prev;
            if (step < 0 || step > slopeMaxStep()) return false;
            prev = top[x + 16 * z];
        }
        return prev > h;
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
