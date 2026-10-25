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
 * 0.39.z54 A3: the heights the shape test reads are GROUND heights ({@link #ground}: leaves, logs and air stripped from the
 * column top), not the raw heightmap.
 * Pure (JUnit: Z44CaveMouthTest, Z54CaveRuleTest). Loader notes: none (the heights, the column's blocks and the rays are
 * read in ChunkPass / SightingsRuntime).
 */
public final class CaveMouths {
    public enum State { CANDIDATE, SEEN, GONE }

    public static int holeDepth() { return Tuning.i("cave.holeDepth"); }
    public static int holeReach() { return Tuning.i("cave.holeReach"); }
    public static int ravineRun() { return Tuning.i("cave.ravineRun"); }
    public static int maxSky() { return Tuning.i("cave.maxSky"); }

    /** A candidate: the lowest open cell at the top of the hole (the air cell over its floor), and its shape. */
    public record Candidate(int x, int y, int z, String shape, String numbers) {
        public Candidate(int x, int y, int z, String shape) { this(x, y, z, shape, ""); }
    }

    /** 0.39.z49 (owner's cave check on z48): the side named by a direction from the low column ("+x", "-z"). */
    static String side(int dx, int dz) { return dx > 0 ? "+x" : dx < 0 ? "-x" : dz > 0 ? "+z" : "-z"; }

    /**
     * 0.39.z49 (pure): the shape numbers the rule used, for the owner to check a spot against it: a mouth "slope -x 4 cols
     * steps 1,0,1,1 (+3), cliff +x +7"; a ravine "cliffs +x +6 / -x +5".
     */
    public static String shapeNumbers(String shape, String cliffSide, int cliff, String backSide, int back, int[] steps) {
        if ("ravine".equals(shape)) return "cliffs " + cliffSide + " +" + cliff + " / " + backSide + " +" + back;
        StringBuilder b = new StringBuilder();
        int sum = 0;
        for (int i = 0; i < steps.length; i++) { b.append(i == 0 ? "" : ",").append(steps[i]); sum += steps[i]; }
        return "slope " + backSide + " " + steps.length + " cols steps " + b + " (+" + sum + "), cliff " + cliffSide + " +" + cliff;
    }

    /**
     * 0.39.z49 (pure): the hole's column numbers a ray read: its depth from the candidate cell (the first air over the
     * surface top) to its lowest air cell, the sky light there and one above, water at its bottom.
     */
    public static String holeNumbers(int depth, int skyLow, int skyAbove, boolean water) {
        return "depth " + depth + " to its lowest air, sky " + skyLow + " there / " + skyAbove + " one above, water " + (water ? "yes" : "no");
    }

    /** The candidate's line head: "x y z (shape: numbers)". */
    public static String where(Candidate c) {
        return c.x() + " " + c.y() + " " + c.z() + " (" + c.shape() + (c.numbers().isEmpty() ? "" : ": " + c.numbers()) + ")";
    }

    /**
     * The candidate rule over one chunk's heights. {@code top[lx + 16 * lz]} = the WORLD_SURFACE top block Y of the column
     * (the cell over it is the first air). Edge columns compare with the columns inside the chunk only. Columns of one hole
     * within {@code cave.mergeM} are one candidate: the lowest.
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
     * place within {@code cave.mergeM} are one candidate (z50: the lowest point kept; was the deepest drop first, then the lowest).
     */
    public static List<Candidate> candidates(int cx, int cz, int[] top) { return shapes(cx, cz, top, null).candidates(); }

    /** 0.39.z50: as {@link #shapes(int, int, int[], boolean[], CavePatterns.Rule)} with the Tuning defaults (no pattern yet). */
    public static Shapes shapes(int cx, int cz, int[] top, boolean[] water) { return shapes(cx, cz, top, water, CavePatterns.defaults()); }

    /** 0.39.z48: one chunk's shapes: the candidates, and the same shapes over water ("flooded holes", noted, never targets). */
    public record Shapes(List<Candidate> candidates, List<Candidate> flooded) {}

    /**
     * 0.39.z48 (owner 2026-10-24, item 5): as {@link #candidates(int, int, int[])}, where {@code water[lx + 16 * lz]} marks a
     * water column (its top block holds water; null: none known): a water column is never a candidate, its shape is kept as
     * a flooded hole (a fact for later, not a target).
     */
    public static Shapes shapes(int cx, int cz, int[] top, boolean[] water, CavePatterns.Rule rule) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<Candidate> raw = new ArrayList<>();
        List<Candidate> flooded = new ArrayList<>();
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int h = top[lx + 16 * lz];
                String shape = null, numbers = "";
                int drop = 0;
                for (int[] d : dirs) {
                    int cliff = cliff(top, lx, lz, d[0], d[1], h);
                    if (cliff < rule.cliff()) continue;   // z50: the rule the cave patterns give (Tuning defaults before any)
                    int back = cliff(top, lx, lz, -d[0], -d[1], h);
                    int[] steps;
                    if (back >= rule.cliff()) {
                        if (!"ravine".equals(shape) || Math.min(cliff, back) > drop) numbers = shapeNumbers("ravine", side(d[0], d[1]), cliff, side(-d[0], -d[1]), back, null);
                        shape = "ravine"; drop = Math.max(drop, Math.min(cliff, back));
                    } else if ((steps = gentleSteps(top, lx, lz, -d[0], -d[1], h, rule.slope())) != null) {
                        if (shape == null) shape = "mouth";
                        if ("mouth".equals(shape) && cliff >= drop) numbers = shapeNumbers("mouth", side(d[0], d[1]), cliff, side(-d[0], -d[1]), back, steps);
                        drop = Math.max(drop, cliff);
                    }
                }
                if (shape == null) continue;
                if (water != null && water[lx + 16 * lz]) {
                    flooded.add(new Candidate((cx << 4) + lx, h + 1, (cz << 4) + lz, "flooded " + shape, numbers));
                    continue;
                }
                raw.add(new Candidate((cx << 4) + lx, h + 1, (cz << 4) + lz, shape, numbers));
            }
        // z50 (owner: one feature = one candidate): within cave.mergeM one candidate, the lowest point kept
        return new Shapes(CavePatterns.merge(raw), flooded);
    }

    /*
     * 0.39.z48 (item 5, owner 2026-10-24 on water; checked in the 1.21.1 source): the client is sent WORLD_SURFACE (not
     * air) and MOTION_BLOCKING (blocks motion OR holds a fluid); OCEAN_FLOOR is LIVE_WORLD, server-side only. At water both
     * client heights are the water's surface, so they cannot tell water; WORLD_SURFACE &gt; MOTION_BLOCKING marks a column
     * whose top is a non-blocking, fluid-free block (grass, a flower, snow). The water test is the top block's own fluid
     * (the surface cell, rule 17), read by ChunkPass into {@link #shapes}' water array.
     */

    /**
     * 0.39.z48 (item 4, S64): the floor of a hole's own air column. {@code air[i]} / {@code water[i]}: the cell i blocks
     * under the candidate cell (0 = the candidate cell) is air / holds water; {@code sky[i]}: its sky light. The lowest air
     * cell is the last of the run of air from the top (the first non-air ends it); -1: the candidate cell is not air.
     */
    public static int floor(boolean[] air) {
        if (air.length == 0 || !air[0]) return -1;
        int i = 0;
        while (i + 1 < air.length && air[i + 1]) i++;
        return i;
    }

    /**
     * 0.39.z48 (S64): the verdict over the hole's own column: null = a cave (the sky light at its lowest air cell or the one
     * above it is at most {@link #maxSky()}), else why not (no air, a water floor: a pond, lit to the floor).
     */
    public static String floorVerdict(boolean[] air, boolean[] water, int[] sky) {
        int f = floor(air);
        if (f < 0) return "the candidate cell is not air";
        if (f + 1 < water.length && water[f + 1]) return "its bottom is water (a pond), y-" + (f + 1);
        if (water[f]) return "its bottom is water (a pond), y-" + f;
        int above = f > 0 ? sky[f - 1] : sky[f];
        if (sky[f] <= maxSky() || above <= maxSky()) return null;
        return "lit to the floor (sky " + sky[f] + " at its lowest air, " + f + " below the rim)";
    }

    /** 0.39.z54 A3: a column whose chunk is not loaded has no ground height (rule 15: not loaded, never "flat" or "deep"). */
    public static final int NOT_LOADED = Integer.MIN_VALUE;   // structural: a sentinel, not a height

    /**
     * 0.39.z54 A3 (labelling walk on z53, owner: "a lot of the last few have just been in a forest"; the client has only
     * WORLD_SURFACE and MOTION_BLOCKING and both count leaves): the ground height of a column. From the heightmap's top
     * block {@code top} down past the cells {@code canopy} says are leaves, logs (the {@code #minecraft:leaves} and
     * {@code #minecraft:logs} tags, read by the caller) or air, to the first other block; that block's Y. At most
     * {@code cave.canopyMaxDepth} cells: past it the heightmap top is kept and {@link #canopyCapped} says so to the caller.
     */
    public static int ground(int top, java.util.function.IntPredicate canopy) {
        int max = Math.max(0, Tuning.i("cave.canopyMaxDepth"));
        int y = top;
        while (top - y < max && canopy.test(y)) y--;
        return canopy.test(y) ? top : y;
    }

    /** 0.39.z54 A3: the walk down a column ran past cave.canopyMaxDepth cells of leaves, logs and air (the heightmap top kept). */
    public static boolean canopyCapped(int top, int ground, java.util.function.IntPredicate canopy) {
        return ground == top && canopy.test(top);
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
    private static int[] gentleSteps(int[] top, int lx, int lz, int dx, int dz, int h, int minRise) {
        int prev = h;
        int[] steps = new int[slopeLen()];
        for (int k = 1; k <= slopeLen(); k++) {
            int x = lx + dx * k, z = lz + dz * k;
            if (x < 0 || x > 15 || z < 0 || z > 15) return null;
            int step = top[x + 16 * z] - prev;
            if (step < 0 || step > slopeMaxStep()) return null;
            steps[k - 1] = step;
            prev = top[x + 16 * z];
        }
        return prev - h >= Math.max(1, minRise) ? steps : null;   // structural 1: a slope rises
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

    public synchronized void drop(int cx, int cz) {
        byChunk.remove(ChunkSummary.key(cx, cz));
        floodedByChunk.remove(ChunkSummary.key(cx, cz));
    }

    public synchronized void clear() {
        byChunk.clear();
        floodedByChunk.clear();
        refused.clear();
    }

    /** 0.39.z48 (item 5): the flooded holes of the loaded chunks (noted for later, never targets). */
    private final Map<Long, List<Candidate>> floodedByChunk = new HashMap<>();

    public synchronized void putFlooded(int cx, int cz, List<Candidate> l) {
        if (l == null || l.isEmpty()) floodedByChunk.remove(ChunkSummary.key(cx, cz));
        else floodedByChunk.put(ChunkSummary.key(cx, cz), List.copyOf(l));
    }

    public synchronized int floodedCount() {
        int n = 0;
        for (List<Candidate> l : floodedByChunk.values()) n += l.size();
        return n;
    }

    /** 0.39.z48 (item 6): candidates a ray showed are not caves: never listed again (a re-read chunk keeps them out). */
    private final java.util.Set<Long> refused = new java.util.HashSet<>();

    public synchronized void refuse(int x, int y, int z) { refused.add(Seen.key(x, y, z)); }

    public synchronized boolean refused(int x, int y, int z) { return refused.contains(Seen.key(x, y, z)); }

    public synchronized int count() {
        int n = 0;
        for (List<Candidate> l : byChunk.values()) n += l.size();
        return n;
    }

    /** The candidates within r (3D) of x y z, nearest first. */
    public synchronized List<Candidate> near(double x, double y, double z, int r) {
        List<Candidate> out = new ArrayList<>();
        double r2 = (double) r * r;
        List<Candidate> all = new ArrayList<>();
        for (List<Candidate> l : byChunk.values()) all.addAll(l);
        // z50: one feature across chunk borders is one candidate too (the lowest kept)
        for (Candidate c : CavePatterns.merge(all)) if (d2(c, x, y, z) <= r2 && !refused.contains(Seen.key(c.x(), c.y(), c.z()))) out.add(c);
        out.sort((p, q) -> Double.compare(d2(p, x, y, z), d2(q, x, y, z)));
        return out;
    }

    /** 0.39.z52 (aa cave list): every candidate of the loaded chunks, merged, the refused ones included. */
    public synchronized List<Candidate> all() {
        List<Candidate> all = new ArrayList<>();
        for (List<Candidate> l : byChunk.values()) all.addAll(l);
        return CavePatterns.merge(all);
    }

    private static double d2(Candidate c, double x, double y, double z) {
        double dx = c.x() + 0.5 - x, dy = c.y() + 0.5 - y, dz = c.z() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
