package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 0.39.z78 J6b (D12, the owner 2026-10-27 late: "Per cave we need to remember what ores we saw in them, if we explored
 * all of it, keep track of all the branches. Fine with using cheats to remember where we have been."): the record of one
 * KNOWN cave, kept in the one knowledge store ({@link Sightings}, beside the known-cave fact; rule 23: one place, one
 * reader) and persisted per world with it. Only the store writes it (its writers are package-private and named
 * {@code rec...}; J6bCaveRecordTest greps that only Sightings calls them).
 * <ul>
 * <li>{@code cells}: the volume's air cells from the cave index (the owner's cheat for known caves, rule 17's ruling),
 * fed in place as its chunks load ({@link #recFeed}, under {@link #BUDGET}); only their count is persisted.</li>
 * <li>{@code seen}: the cells the bot stood in or saw round it - the cave cells within {@link #SEE_STEPS} steps of the
 * feet through the cave's own air, from the cave cell nearest the feet within the eyes' reach ({@link #cellInReach}).</li>
 * <li>{@code passages} (D15 pass two: THE cave model; the frontier groups are deleted): the cave read as passages
 * ({@link io.github.mojolowjo.entropybot.cave.Passages}) - the caving step's last measure from its feet, or, never
 * measured, the record's own from the entry; each with its ends, length, unseen count and ore faces. Persisted as the
 * list (rule 23: the one store); while a caving step runs the planner's reads HOLD the list the step found first
 * ({@link #recHold}), so a walking bot's price does not move each metre; the step's end stores its last measure.</li>
 * <li>{@code ores}: the ore cells seen in the cave (the ore index, by sight: rule 17), by family, left or mined.</li>
 * <li>{@code done}: THE done reader (rule 23 (2)): the passages measured and none open (every cell of each seen, no end
 * past the flood's cap); the caving step and the planner's offered check ask it.</li>
 * <li>{@code lastAt}, {@code walkedMinutes}, {@link #oreSeenPerMinute} (the ore seen a minute walked here - a record
 * fact; 0.39.z78 J6d: no price reads it, the cave way is priced by its walks).</li>
 * </ul>
 * 0.39.z78 run EK (the manager's fix; 02:30:20-02:46:19: every caving tick 7-70 s - the whole 209,647-cell volume
 * flooded for every branch, several times a tick, and on every planner ask): no reader walks the record whole. The sums
 * (cells seen in the volume) and the frontier change with each cell; the facts are cached until a record write
 * ({@link #facts}); the cells come by budgeted steps. Pure (JUnit: J6bCaveRecordTest, J6bEkTest). Loader notes: none.
 */
public final class CaveRecord {
    /**
     * The steps through the cave's air from the feet a walker counts as seen: what a player sees of a dark tunnel round
     * him without a torch, about a carver tunnel's width (2-3) past the next bend either way (an estimate; the owner
     * allows the cheat for known caves: "Fine with using cheats to remember where we have been").
     */
    public static final int SEE_STEPS = 6; // structural: a tunnel's width past the next bend, either way (estimate)
    /**
     * J6c review of 98889d5f: the most cells one stand touches - the cells within {@link #SEE_STEPS} face steps (the 3D
     * octahedron, (2r+1)(2r^2+2r+3)/3), each read and marked seen (two touches); the feeder leaves it out of the tick's
     * budget so the stand after it stays inside.
     */
    public static final int STAND_MAX = 2 * (2 * SEE_STEPS + 1) * (2 * SEE_STEPS * SEE_STEPS + 2 * SEE_STEPS + 3) / 3; // structural: the octahedron of SEE_STEPS, two touches a cell
    /**
     * Run EK: the cells (index runs and cells fed, cells searched) the record touches in one client tick at most. A
     * HashSet op on a boxed cell costs ~50-100 ns, so 16384 is ~1-2 ms - a few percent of the 50 ms tick - and a cave of
     * 209,647 cells is fed in about 13 ticks (under a second). A run is never split: one step may pass it by one run (at
     * most the world's height, 384 cells) or one chunk's run count.
     */
    public static final int BUDGET = 16384; // structural: ~1-2 ms of set work in a 50 ms client tick

    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** An ore cell seen in the cave: its family and whether it was mined. */
    public record Ore(String family, boolean mined) {}

    public final int x, y, z;
    private final Set<Long> seen = new HashSet<>();
    private final Set<Long> cells = new HashSet<>();
    private int cellCount;
    /** Run EK: the seen cells that are cells of the volume (unseen() = cellCount - seenIn). */
    private int seenIn;
    private final Map<Long, Ore> ores = new LinkedHashMap<>();
    private long lastAt;
    private double walkedMinutes;
    private int oreSeenWalking;
    /** D15 pass two: the passages (null: never measured), where they were measured from, why the measure is empty. */
    private List<io.github.mojolowjo.entropybot.cave.Passages.Passage> passages;
    private int[] passagesFrom;
    private String passagesWhy;
    /** D15 pass two: a caving step runs - the planner reads the list as the step found it first; {@code later} its last. */
    private boolean held, heldFresh;
    private io.github.mojolowjo.entropybot.cave.Passages.Found later;
    private int[] laterFrom;
    /** The passages with their unseen counts measured again, by the record's version. */
    private List<io.github.mojolowjo.entropybot.cave.Passages.Passage> passagesNow;
    private long passagesNowAt = -1;
    /** J6b review 4: a walk that gained nothing (no cell seen, no ore mined) - the counts it left; -1: none. */
    private int stuckSeen = -1, stuckOre = -1, stuckCells = -1;
    /** Run EK: the index feed (null: cells given whole, recCells) and the facts' cache (by the record's version). */
    private CaveIndex.Feed feed;
    private long version, factsAt = -1;
    private Facts factsNow;
    /** Run EK: the cells touched since the last {@link #takeTouched} (the budget's JUnit). */
    private long touched;
    /**
     * 0.39.z78 J6c (the owner's ruling 1): the cave's AIR-EXPOSED blocks - the not-air blocks with a face on the index's air,
     * found by {@link io.github.mojolowjo.entropybot.aa.Nearby#exposed} over the index's air answer ({@link CaveIndex#airAt})
     * - counted once each, by Y; the cells not yet looked round, and the neighbours whose chunk is not indexed (by chunk:
     * looked at again once it is; any left = the volume is OPEN at the loaded edge, ruling 2: a lower bound). In memory
     * only, as the cells.
     */
    private final Set<Long> exposedSet = new HashSet<>();
    private final TreeMap<Integer, Integer> exposedByY = new TreeMap<>();
    private final ArrayDeque<Long> toExpose = new ArrayDeque<>();
    private final Map<Long, Set<Long>> exposePending = new java.util.HashMap<>();

    CaveRecord(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

    public String where() { return x + " " + y + " " + z; }

    // ---- the store's writers (Sightings only) ----

    /** The volume's cells given whole (in place; never shrinks in a session - unloaded parts stay). */
    void recCells(Collection<Long> now) {
        for (long c : now) addCell(c);
    }

    /**
     * Run EK: one budgeted step of the volume's cells from the index (the chunks put since the last pass, a volume joined;
     * in place, never copied). The cells touched this step ({@code budget} at most, one run or one chunk's run count over).
     */
    int recFeed(CaveIndex idx, int budget) {
        if (feed == null) feed = new CaveIndex.Feed();
        int n = idx.feed(x, y, z, feed, budget, this::addCell);
        // D15 pass four: a merged record feeds from each kept entry (its lips) too - their index volumes may differ
        for (int i = 0; i < lips.size() && n < budget; i++) {
            if (lipFeeds.get(i) == null) lipFeeds.set(i, new CaveIndex.Feed());
            int[] l = lips.get(i);
            n += idx.feed(l[0], l[1], l[2], lipFeeds.get(i), budget - n, this::addCell);
        }
        touched += n;
        spend.spent += n;
        return n;
    }

    /**
     * J6c review of 98889d5f: the cells spent this client tick by the feed, the stands, the regroups and the air-exposed
     * counts of EVERY record - one counter (Sightings.caveFeed starts the tick and hands each record the store's one);
     * BUDGET is the total. A record not in a store's feed keeps its own, started again by its own stand ({@code owned}).
     */
    static final class Spend {
        long spent;
        final boolean owned;
        Spend(boolean owned) { this.owned = owned; }
    }

    private Spend spend = new Spend(true);

    /** J6c review: the store's one per-tick counter for this record (Sightings.caveFeed). */
    void recSpend(Spend shared) { spend = shared; }

    /** J6c review: the cells this tick's one budget has left (0: none). */
    static int left(Spend s) { return (int) Math.max(0, Math.min(Integer.MAX_VALUE, BUDGET - s.spent)); }

    /** J6c review: the air-exposed count still has work (cells not looked round, or neighbours pending a chunk). */
    boolean exposing() { return !toExpose.isEmpty() || !exposePending.isEmpty(); }

    /**
     * 0.39.z78 J6c (the owner's rulings 1 and 2): one budgeted step of the air-exposed count - the neighbours of the cells
     * not yet looked round, and the pending ones whose chunk {@code air} answers now; each a block where {@code air} says
     * not air and {@link io.github.mojolowjo.entropybot.aa.Nearby#exposed} finds an open face. The neighbour tests spent
     * ({@code budget} at most, one chunk's pending over).
     */
    int recExpose(io.github.mojolowjo.entropybot.aa.Nearby.Open air, int budget) {
        int spent = 0;
        if (!exposePending.isEmpty()) {
            for (var it = exposePending.entrySet().iterator(); it.hasNext() && spent < budget; ) {
                var e = it.next();
                int[] p = Sightings.cellOf(e.getValue().iterator().next());
                spent++;
                if (air.open(p[0], p[1], p[2]) == null) continue;               // its chunk still not indexed
                it.remove();
                for (long b : e.getValue()) { classify(air, b); spent++; }
            }
        }
        while (!toExpose.isEmpty() && spent < budget) {
            int[] p = Sightings.cellOf(toExpose.poll());
            for (int[] o : SIX) { classify(air, Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2])); spent++; }
        }
        touched += spent;
        spend.spent += spent;
        return spent;
    }

    private void classify(io.github.mojolowjo.entropybot.aa.Nearby.Open air, long b) {
        if (exposedSet.contains(b)) return;
        int[] p = Sightings.cellOf(b);
        Boolean a = air.open(p[0], p[1], p[2]);
        if (a == null) { exposePending.computeIfAbsent(CaveIndex.chunkKey(p[0] >> 4, p[2] >> 4), k -> new java.util.LinkedHashSet<>()).add(b); return; }
        if (a || !Boolean.TRUE.equals(io.github.mojolowjo.entropybot.aa.Nearby.exposed(air, p[0], p[1], p[2]))) return;
        exposedSet.add(b);
        exposedByY.merge(p[1], 1, Integer::sum);
    }

    /**
     * 0.39.z78 J6c: the air-exposed blocks by Y (a copy), their count; {@code open}: a neighbour's chunk is not indexed (the
     * volume touches the loaded edge: at least this, ruling 2); {@code done}: the cells are fed and every one looked round.
     */
    public record Exposure(Map<Integer, Integer> byY, long blocks, boolean open, boolean done) {}

    Exposure exposure() {
        return new Exposure(new TreeMap<>(exposedByY), exposedSet.size(), !exposePending.isEmpty(), loaded() && toExpose.isEmpty());
    }

    private void addCell(long c) {
        if (!cells.add(c)) return;
        toExpose.add(c);                                                        // J6c: looked round for air-exposed blocks
        version++;
        cellCount = Math.max(cellCount, cells.size());
        if (seen.contains(c)) seenIn++;
    }

    /** A cell seen: the sum changed in place; true: new. */
    private boolean markSeen(long c) {
        if (!seen.add(c)) return false;
        version++;
        touched++;
        if (cells.contains(c)) seenIn++;
        return true;
    }

    /**
     * The bot stands at x y z: the cave cells within SEE_STEPS through the cave's air are seen, from the feet's cell or
     * the cave cell nearest the feet within the eyes' reach (run EK: the lip 3 x 2 from the entry sees into it); the
     * newly seen count.
     */
    int recStood(int fx, int fy, int fz, long now) { return recStood(fx, fy, fz, now, null); }

    /** A cell the eyes look at (D15 pass three: in the view cone and the rays' range); x y z. */
    public interface Look { boolean at(int x, int y, int z); }

    /**
     * D15 pass three (run JAK: 234 cells "seen" by a 3 s walk): as above, only the cells {@code look} answers for are
     * marked seen - the eyes' cone and range, as the ore rays cover them; null: every cell of the stand (tests, a replay).
     */
    int recStood(int fx, int fy, int fz, long now, Look look) {
        if (lastAt != now) { lastAt = now; version++; if (spend.owned) spend.spent = 0; }   // 88b28290 review 1: the stand is once a tick too (a record of its own)
        long start = Sightings.cell(fx, fy, fz);
        if (!cells.contains(start)) start = cellInReach(fx, fy, fz);
        if (start == Long.MIN_VALUE) return 0;
        int added = 0;
        long before = touched;
        Map<Long, Integer> depth = new java.util.HashMap<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        depth.put(start, 0);
        q.add(start);
        while (!q.isEmpty()) {
            long c = q.poll();
            touched++;
            if (look == null) { if (markSeen(c)) added++; }
            else { int[] q0 = Sightings.cellOf(c); if (look.at(q0[0], q0[1], q0[2]) && markSeen(c)) added++; }
            int d = depth.get(c);
            if (d >= SEE_STEPS) continue;
            int[] p = Sightings.cellOf(c);
            for (int[] o : SIX) {
                long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                if (cells.contains(n) && !depth.containsKey(n)) { depth.put(n, d + 1); q.add(n); }
            }
        }
        spend.spent += touched - before;                                     // J6c review: the stand is in the tick's one budget
        return added;
    }

    /** Cells seen otherwise (a ray passed them): those of the cave count. */
    int recSee(Collection<Long> passed) {
        int added = 0;
        for (long c : passed) if (cells.contains(c) && markSeen(c)) added++;
        return added;
    }

    /** An ore cell seen in the cave (by sight: the ore index); true: new. A mined one stays mined. */
    boolean recOre(long cell, String family) {
        if (ores.containsKey(cell)) return false;
        ores.put(cell, new Ore(family, false));
        version++;
        return true;
    }

    /** An ore cell of the cave mined. */
    boolean recMined(long cell) {
        Ore o = ores.get(cell);
        if (o == null || o.mined()) return false;
        ores.put(cell, new Ore(o.family(), true));
        version++;
        return true;
    }

    /**
     * A caving walk ended: its minutes inside and the ore cells it saw. {@code gained} false (J6b review 4: no cell seen
     * and no ore mined): the cave is not offered again until something new is known of it - a cell seen, an ore seen, more
     * cells from the index ({@link #stuck}); so a walk that cannot get on never loops.
     */
    void recWalked(double minutes, int oreSeen, long now, Boolean gained) {
        version++;
        walkedMinutes += Math.max(0, minutes);
        oreSeenWalking += Math.max(0, oreSeen);
        lastAt = now;
        if (gained == null) return;                                       // 57db13b7 review (b): an owner stop leaves the mark as it was
        if (gained) { stuckSeen = stuckOre = stuckCells = -1; }
        else { stuckSeen = seen.size(); stuckOre = ores.size(); stuckCells = cellCount; }
    }

    /**
     * The last walk gained nothing and nothing new is known of the cave since (review 4). 57db13b7 review (a): only GROWTH
     * lifts it - more cells seen, more ore seen, more cells from the index; a mined ore or a smaller count lifts nothing.
     */
    public boolean stuck() { return stuckSeen >= 0 && !(seen.size() > stuckSeen || ores.size() > stuckOre || cellCount > stuckCells); }

    /**
     * Run EK: the cave cell nearest the feet within the eyes' reach of them ({@link io.github.mojolowjo.entropybot.cave.ExploreRules#withinReach},
     * the lip walk's own reach); MIN_VALUE: none. A cube of the reach round the feet (9 x 9 x 9 cells at most).
     */
    /**
     * Run ES (review of 2394a868): the feet are in this cave - one of its cells, or a cave cell within the eyes' reach that
     * the eyes SEE ({@code sees}: the cell {x, y, z} is in sight from the feet - GoalReachBlock's rule: reach AND sight; a
     * wall between is outside). The nearest {@link #SIGHT_TRIES} cells in reach are tried, nearest first. No cells: false.
     */
    boolean holdsFeet(int fx, int fy, int fz, java.util.function.Predicate<int[]> sees) {
        if (cells.isEmpty()) return false;
        if (cells.contains(Sightings.cell(fx, fy, fz))) return true;
        int r = (int) Math.ceil(io.github.mojolowjo.entropybot.cave.ExploreRules.OPENING_REACH);
        int[] feet = {fx, fy, fz};
        List<long[]> near = new ArrayList<>();                                  // {cell, distance squared}
        for (int dy = -r; dy <= r + 2; dy++)                                        // structural: the eyes 1.62 over the feet
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    long c = Sightings.cell(fx + dx, fy + dy, fz + dz);
                    touched++;
                    if (cells.contains(c) && io.github.mojolowjo.entropybot.cave.ExploreRules.withinReach(feet, new int[]{fx + dx, fy + dy, fz + dz}))
                        near.add(new long[]{c, (long) dx * dx + (long) dy * dy + (long) dz * dz});
                }
        near.sort(java.util.Comparator.comparingLong(a -> a[1]));
        for (int i = 0; i < near.size() && i < SIGHT_TRIES; i++)
            if (sees == null || sees.test(Sightings.cellOf(near.get(i)[0]))) return true;
        return false;
    }

    /** Review of 2394a868: the cave cells in reach whose sight is tried at most (the nearest; a ray each). */
    static final int SIGHT_TRIES = 8; // structural: a few rays, the nearest cells of the reach cube

    long cellInReach(int fx, int fy, int fz) {
        int r = (int) Math.ceil(io.github.mojolowjo.entropybot.cave.ExploreRules.OPENING_REACH);
        long best = Long.MIN_VALUE, bd = Long.MAX_VALUE;
        int[] feet = {fx, fy, fz};
        for (int dy = -r; dy <= r + 2; dy++)                                        // structural: the eyes 1.62 over the feet
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    long c = Sightings.cell(fx + dx, fy + dy, fz + dz);
                    touched++;
                    if (!cells.contains(c) || !io.github.mojolowjo.entropybot.cave.ExploreRules.withinReach(feet, new int[]{fx + dx, fy + dy, fz + dz})) continue;
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d < bd) { bd = d; best = c; }
                }
        return best;
    }

    // ---- D15 pass two: the passages (the store's writers) ----

    /**
     * The caving step's measure from {@code from} (its feet). Held (a step runs) and the step's first already stored: kept
     * as {@code later}, stored when the hold ends - so the planner's price holds while the bot walks.
     */
    void recPassages(io.github.mojolowjo.entropybot.cave.Passages.Found f, int[] from) {
        if (f == null) return;
        if (held && !heldFresh) { later = f; laterFrom = from; return; }
        heldFresh = false;
        storePassages(f, from);
    }

    private void storePassages(io.github.mojolowjo.entropybot.cave.Passages.Found f, int[] from) {
        passages = List.copyOf(f.passages());
        passagesWhy = f.why();
        passagesFrom = from == null ? null : from.clone();
        measuredCells = cellCount;
        version++;
    }

    /** The cells known at the last measure (the record measures again from the entry once more cells arrive). */
    private int measuredCells;

    /**
     * A caving step starts ({@code on}) or ends: while on, the planner reads the list the step measured first; at the end
     * the step's last measure is stored (the step's end fact re-opens the price).
     */
    void recHold(boolean on) {
        if (on) { held = true; heldFresh = true; later = null; return; }
        held = false;
        heldFresh = false;
        if (later != null) storePassages(later, laterFrom);
        later = null;
        laterFrom = null;
        version++;
    }

    public boolean held() { return held; }

    /** The record's own measure from the entry (never measured, the cells loaded): the lip case floods from cave air near it. */
    private void measureFromEntry() {
        Set<Long> ore = new HashSet<>();
        for (var e : ores.entrySet()) if (!e.getValue().mined()) ore.add(e.getKey());
        int[] from = {x, y, z};
        var f = io.github.mojolowjo.entropybot.cave.Passages.build((px, py, pz) -> cells.contains(Sightings.cell(px, py, pz)), seen::contains, from, ore,
                io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageDrop"), io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageForkLayers"),
                io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageCells"), io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageRoomWidth"),
                io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageLipNear"));
        storePassages(f, from);
    }

    /**
     * THE passages of this cave: the last measure, unseen counts measured again against the seen cells (not while held,
     * not without their cells: a reloaded list keeps its saved counts); never measured and the cells loaded: measured from
     * the entry. null: not measured (the cells not loaded).
     */
    public List<io.github.mojolowjo.entropybot.cave.Passages.Passage> passages() {
        if (loaded() && !held && (passages == null || cellCount != measuredCells)) measureFromEntry();   // more cells arrived: measured again
        if (passages == null) return null;
        if (held) return passages;
        if (passagesNowAt == version && passagesNow != null) return passagesNow;
        List<io.github.mojolowjo.entropybot.cave.Passages.Passage> out = new ArrayList<>();
        for (var p : passages) {
            if (p.cells().isEmpty()) { out.add(p); continue; }
            int n = 0;
            for (long c : p.cells()) if (!seen.contains(c)) n++;          // at most 2 x cave.passageCells (not in BUDGET, as the step's flood)
            out.add(n == p.unseen() ? p : p.withUnseen(n));
        }
        passagesNow = List.copyOf(out);
        passagesNowAt = version;
        return passagesNow;
    }

    /** Why the last measure found no passage (rule 7); null: it found some, or none was made. */
    public String passagesWhy() { return passagesWhy; }

    /** The open passages at the last measure; -1: not measured. */
    public int openPassages() {
        var ps = passages();
        if (ps == null) return -1;
        int n = 0;
        for (var p : ps) if (p.open()) n++;
        return n;
    }

    // ---- the readers ----

    /**
     * The cells are loaded: given whole, or the index's first feed pass this session ran to its end - frontier and done
     * are measured now. Run EK: while the first pass runs the record is {@link #filling}, not loaded (rule 15).
     */
    public boolean loaded() {
        if (cells.isEmpty() || (feed != null && !feed.complete())) return false;
        for (CaveIndex.Feed lf : lipFeeds) if (lf != null && !lf.complete()) return false;
        return true;
    }

    /** Run EK: the index's first feed pass is running (the cells so far: {@link #cellsSoFar}). */
    public boolean filling() {
        if (feed != null && !feed.complete() && feed.filling()) return true;
        for (CaveIndex.Feed lf : lipFeeds) if (lf != null && !lf.complete() && lf.filling()) return true;
        return false;
    }

    // ---- D15 pass four: one record per cave space (the merge), the entries kept as lips, the promise past a pocket ----

    /** The other entries of this cave space (records merged into this one), x y z each. */
    private final List<int[]> lips = new ArrayList<>();
    private final List<CaveIndex.Feed> lipFeeds = new ArrayList<>();

    /** The kept entries of records merged into this one (copies). */
    public List<int[]> lips() {
        List<int[]> out = new ArrayList<>();
        for (int[] l : lips) out.add(l.clone());
        return out;
    }

    /** Run JAK: the two records are one cave space - they share an air cell, or one's entry is a cell of the other. */
    boolean sharesSpaceWith(CaveRecord o) {
        if (o == this || cells.isEmpty() || o.cells.isEmpty()) return false;
        if (cells.contains(Sightings.cell(o.x, o.y, o.z)) || o.cells.contains(Sightings.cell(x, y, z))) return true;
        Set<Long> small = cells.size() <= o.cells.size() ? cells : o.cells, big = small == cells ? o.cells : cells;
        for (long c : small) if (big.contains(c)) return true;                 // once per growth of either (Sightings.caveMerge)
        return false;
    }

    /**
     * D15 pass four (run JAK: the same cave space entered by two entries, two records of 44 and 37 passages, a DONE mark on
     * one not holding the other): {@code o} is merged into this one - cells, seen cells, ore, walked minutes united; its
     * entry and lips kept as this one's lips (with their feeds); the passages measured again from every entry. A done mark
     * is kept with its cell count (more cells now: done no more until a step says so).
     */
    void recMerge(CaveRecord o) {
        lips.add(new int[]{o.x, o.y, o.z});
        lipFeeds.add(o.feed);
        for (int i = 0; i < o.lips.size(); i++) { lips.add(o.lips.get(i)); lipFeeds.add(o.lipFeeds.get(i)); }
        for (long c : o.cells) addCell(c);
        cellCount = Math.max(cellCount, o.cellCount);
        for (long c : o.seen) markSeen(c);
        for (var e : o.ores.entrySet()) {
            Ore mine = ores.get(e.getKey());
            if (mine == null || (!mine.mined() && e.getValue().mined())) ores.put(e.getKey(), e.getValue());
        }
        walkedMinutes += o.walkedMinutes;
        oreSeenWalking += o.oreSeenWalking;
        if (doneWhy == null && o.doneWhy != null) { doneWhy = o.doneWhy; doneCells = o.doneCells; }
        if (passages == null && o.passages != null) { passages = o.passages; passagesWhy = o.passagesWhy; }
        measuredCells = -1;                                                     // measured again (passages(), from every entry)
        entryAt = -1;
        held |= o.held;
        stuckSeen = stuckOre = stuckCells = -1;                                 // more is known of it now
        version++;
    }

    /** The cells promised past a pocket: the open passages' cells from the record's entries, and the targets off the flood. */
    public record Promise(int cells, int open, List<int[]> targets) {}

    private List<io.github.mojolowjo.entropybot.cave.Passages.Passage> entryMeasure;
    private int entryAt = -1;

    /** The passages measured from the entry and every lip (cached by the cell count; cells only grow). */
    private List<io.github.mojolowjo.entropybot.cave.Passages.Passage> entryMeasure() {
        if (entryMeasure != null && entryAt == cellCount) return entryMeasure;
        List<io.github.mojolowjo.entropybot.cave.Passages.Passage> all = new ArrayList<>();
        List<int[]> from = new ArrayList<>();
        from.add(new int[]{x, y, z});
        from.addAll(lips);
        for (int[] f : from) {
            var found = io.github.mojolowjo.entropybot.cave.Passages.build((px, py, pz) -> cells.contains(Sightings.cell(px, py, pz)), seen::contains, f, Set.of(),
                    io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageDrop"), io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageForkLayers"),
                    io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageCells"), io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageRoomWidth"),
                    io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageLipNear"));
            all.addAll(found.passages());
        }
        entryMeasure = List.copyOf(all);
        entryAt = cellCount;
        return entryMeasure;
    }

    /**
     * D15 pass four (run JAM): what the record promises past the step's flood ({@code flood}: the cells its passages hold)
     * - the cells of the open passages measured from the entries, and the {@code n} nearest {@code feet} of their far ends
     * off the flood (no such end: the nearest unseen cells off it). Not loaded: nothing promised.
     */
    public Promise promise(int[] feet, Set<Long> flood, int n) {
        if (!loaded() || n <= 0) return new Promise(0, 0, List.of());
        int promised = 0, open = 0;
        List<int[]> ends = new ArrayList<>();
        Set<Long> took = new HashSet<>();
        for (var p : entryMeasure()) {
            int un = 0;
            for (long c : p.cells()) if (!seen.contains(c)) un++;
            if (un == 0 && p.endKind() != io.github.mojolowjo.entropybot.cave.Passages.EndKind.CAPPED) continue;
            open++;
            promised += p.cells().size();
            if (!flood.contains(p.key()) && took.add(p.key())) ends.add(p.end().clone());
        }
        if (ends.isEmpty()) {
            for (long c : cells) {                                              // the n nearest kept (a volume may be 200,000 cells)
                if (seen.contains(c) || flood.contains(c)) continue;
                int[] q = Sightings.cellOf(c);
                if (!io.github.mojolowjo.entropybot.cave.Passages.walkable((px, py, pz) -> cells.contains(Sightings.cell(px, py, pz)), q[0], q[1], q[2])) continue;   // run EN: never a mid-air cell
                if (ends.size() >= n && d2(q, feet) >= d2(ends.get(ends.size() - 1), feet)) continue;
                ends.add(q);
                ends.sort(java.util.Comparator.comparingLong(a -> d2(a, feet)));
                if (ends.size() > n) ends.remove(ends.size() - 1);
            }
        }
        ends.sort(java.util.Comparator.comparingLong(a -> d2(a, feet)));
        return new Promise(promised, open, ends.size() > n ? List.copyOf(ends.subList(0, n)) : List.copyOf(ends));
    }

    private static long d2(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    public int cellsSoFar() { return cells.size(); }

    public int cellCount() { return cellCount; }

    public int seenCount() { return seen.size(); }

    /** The cells not seen yet (the cells' count less the seen ones; rough while not loaded). O(1). */
    public int unseen() {
        if (!loaded()) return Math.max(0, cellCount - seen.size());
        return Math.max(0, cellCount - seenIn);
    }

    /** Seen cells / cells (0: no cells known). */
    public double explored() { return cellCount == 0 ? 0 : (cellCount - unseen()) / (double) cellCount; }

    /**
     * Run GT fix (2026-11-01: 115 blocks from the entry the record held iron 0 left while the eyes saw 24 veins - the feed
     * tested "beside the cave" with CaveIndex.volumeAt(entry), and the index keeps loaded chunks only, so with the entry's
     * chunk unloaded the root was -1 and every ore was rejected): an ore at x y z is beside this cave when one of its six
     * neighbours is a cell of the record - the record's own cells, the one store (rule 23), loaded entry or not. Pure.
     */
    public boolean beside(int x, int y, int z) {
        for (int[] o : SIX) if (cells.contains(Sightings.cell(x + o[0], y + o[1], z + o[2]))) return true;
        return false;
    }

    /** D15: the cell is air of this cave's volume (the record's own cells, rule 23). */
    public boolean air(long c) { return cells.contains(c); }

    /** D15: the cell was seen (in reach and sight of a stand). */
    public boolean wasSeen(long c) { return seen.contains(c); }

    /**
     * THE done reader (rule 23 (2); D15 pass two: on the passages): measured, the measure found passages (an empty one
     * says why, never done), and none of them open. Not loaded: the saved list.
     */
    public boolean done() {
        if (doneWhy != null && cellCount <= doneCells) return true;              // D15 pass three: the step's DONE, until more cells arrive
        var ps = passages();
        if (ps == null || passagesWhy != null || ps.isEmpty()) return false;
        for (var p : ps) if (p.open()) return false;
        return true;
    }

    /**
     * D15 pass three (run JAK: a cave DONE at 12% was entered again): a caving step ended DONE here - {@code why} kept with
     * the cell count then; {@link #done} holds until more cells of the cave arrive. Persisted.
     */
    private String doneWhy;
    private int doneCells;

    void recDone(String why) {
        doneWhy = why == null ? "done" : why;
        doneCells = cellCount;
        version++;
    }

    /** The step's DONE reason (null: none). */
    public String doneWhy() { return doneWhy; }

    /** The open passages at the last measure (-1: never measured). */
    public int groups() { return openPassages(); }

    /** family -> {left, mined} of the ore cells seen here. */
    public Map<String, int[]> oresByFamily() {
        Map<String, int[]> out = new TreeMap<>();
        for (Ore o : ores.values()) out.computeIfAbsent(o.family(), k -> new int[2])[o.mined() ? 1 : 0]++;
        return out;
    }

    /** The ore cells of {@code family} seen here and not mined. */
    public List<int[]> left(String family) {
        List<int[]> out = new ArrayList<>();
        for (var e : ores.entrySet()) if (!e.getValue().mined() && e.getValue().family().equals(family)) out.add(Sightings.cellOf(e.getKey()));
        return out;
    }

    public long lastAt() { return lastAt; }

    public double walkedMinutes() { return walkedMinutes; }

    /** Ore seen a minute of caving here (NaN: never walked - the prior prices it). */
    public double oreSeenPerMinute() { return walkedMinutes > 0 ? oreSeenWalking / walkedMinutes : Double.NaN; }

    /** The record as offered for {@code family}: not done, or done with ore of it seen and left. */
    public boolean offeredFor(String family) { return !stuck() && (!done() || !left(family).isEmpty()); }

    /** "x y z: explored 60%, 2 open passages, iron 3 left 1 mined, walked 4 min" (aa cave list, aa why, the step's end line). */
    public String line() {
        int g = groups();
        int un = unseen();
        List<String> o = new ArrayList<>();
        for (var e : oresByFamily().entrySet()) o.add(e.getKey() + " " + e.getValue()[0] + " left " + e.getValue()[1] + " mined");
        return where() + ": explored " + Math.round(explored() * 100) + "% (" + (cellCount - un) + " of " + cellCount + " cells)"
                + (filling() ? " (reading its cells: " + cells.size() + " so far)" : "") + ", "
                + (g < 0 ? "passages not measured" : g + " open passage" + (g == 1 ? "" : "s")) + (passagesWhy != null ? " (" + passagesWhy + ")" : "") + (done() ? ", done" : "")
                + (o.isEmpty() ? ", no ore seen" : ", " + String.join(", ", o)) + (walkedMinutes > 0 ? String.format(java.util.Locale.ROOT, ", walked %.1f min", walkedMinutes) : "")
                + passagesTail();
    }

    /** Owner command read (aa cave list): " | passages: 2 of 5 open | done: why" - open of total, and the done reason; "" when not measured and not done. */
    private String passagesTail() {
        var ps = passages();
        String open = ps == null ? "" : " | passages: " + openPassages() + " of " + ps.size() + " open";
        String why = doneWhy != null && done() ? " | done: " + doneWhy : done() ? " | done: every passage seen" : "";
        return open + why;
    }

    /** Run EK: the cells touched since the last call (the budget's measure), and the count starts again. */
    long takeTouched() {
        long t = touched;
        touched = 0;
        return t;
    }

    /** A copy of what the record says now, for readers off the store's lock (the planner, aa cave list, aa why). */
    public record Facts(int x, int y, int z, int cellCount, int unseen, double explored, int passages, boolean done, Map<String, int[]> ores,
                        double walkedMinutes, double oreSeenPerMinute, long lastAt, boolean loaded, String line, boolean stuck, boolean filling) {
        public Facts(int x, int y, int z, int cellCount, int unseen, double explored, int passages, boolean done, Map<String, int[]> ores,
                     double walkedMinutes, double oreSeenPerMinute, long lastAt, boolean loaded, String line, boolean stuck) {
            this(x, y, z, cellCount, unseen, explored, passages, done, ores, walkedMinutes, oreSeenPerMinute, lastAt, loaded, line, stuck, false);
        }

        public Facts(int x, int y, int z, int cellCount, int unseen, double explored, int passages, boolean done, Map<String, int[]> ores,
                     double walkedMinutes, double oreSeenPerMinute, long lastAt, boolean loaded, String line) {
            this(x, y, z, cellCount, unseen, explored, passages, done, ores, walkedMinutes, oreSeenPerMinute, lastAt, loaded, line, false, false);
        }

        public int left(String family) { int[] v = ores.get(family); return v == null ? 0 : v[0]; }

        /** A way for the family: not stuck (review 4), and not done - or done with its ore seen and left. */
        public boolean offeredFor(String family) { return !stuck && (!done || left(family) > 0); }

        // 0.39.z78 J6d (rule 14): the measured yield (rate, measured, measureFloorMinutes) is deleted with the cave way's
        // rate - the price is the walks; oreSeenPerMinute stays a record fact (the step's end line), read by no price
    }

    /** Run EK: the facts, cached until a record write (a cell fed or seen, an ore, a walk) - the planner's asks read the cache. */
    Facts facts() {
        if (factsNow != null && factsAt == version) return factsNow;
        factsNow = new Facts(x, y, z, cellCount, unseen(), explored(), openPassages(), done(), oresByFamily(), walkedMinutes, oreSeenPerMinute(), lastAt, loaded(), line(), stuck(), filling());
        factsAt = version;
        return factsNow;
    }

    // ---- the file (the store's) ----

    com.google.gson.JsonObject json() {
        var j = new com.google.gson.JsonObject();
        j.addProperty("x", x); j.addProperty("y", y); j.addProperty("z", z);
        j.addProperty("cells", cellCount);
        j.addProperty("seenZ", PackedCells.pack(seen));
        var oa = new com.google.gson.JsonArray();
        for (var e : ores.entrySet()) {
            int[] p = Sightings.cellOf(e.getKey());
            var a = new com.google.gson.JsonArray();
            a.add(p[0]); a.add(p[1]); a.add(p[2]); a.add(e.getValue().family()); a.add(e.getValue().mined());
            oa.add(a);
        }
        j.add("ores", oa);
        j.addProperty("lastAt", lastAt);
        j.addProperty("walkedMinutes", walkedMinutes);
        j.addProperty("oreSeenWalking", oreSeenWalking);
        // D15 pass two: the passage list is the saved record of what is left (rule 23); the cells are not saved
        var ps = held ? passages : passages();                                   // held: the list the planner reads
        if (ps != null) {
            var pa = new com.google.gson.JsonArray();
            for (var p : ps) {
                var a = new com.google.gson.JsonArray();
                a.add(p.id()); a.add(p.parent());
                a.add(p.start()[0]); a.add(p.start()[1]); a.add(p.start()[2]);
                a.add(p.end()[0]); a.add(p.end()[1]); a.add(p.end()[2]);
                a.add(p.length()); a.add(p.width()); a.add(p.fromFeet()); a.add(p.unseen()); a.add(p.oreFaces()); a.add(p.endKind().name());
                pa.add(a);
            }
            j.add("passages", pa);
            if (passagesWhy != null) j.addProperty("passagesWhy", passagesWhy);
        }
        j.addProperty("stuckSeen", stuckSeen); j.addProperty("stuckOre", stuckOre); j.addProperty("stuckCells", stuckCells);
        if (doneWhy != null) { j.addProperty("doneWhy", doneWhy); j.addProperty("doneCells", doneCells); }
        if (!lips.isEmpty()) {                                                  // D15 pass four: the merged entries
            var la = new com.google.gson.JsonArray();
            for (int[] l : lips) { var a = new com.google.gson.JsonArray(); a.add(l[0]); a.add(l[1]); a.add(l[2]); la.add(a); }
            j.add("lips", la);
        }
        return j;
    }

    static CaveRecord of(com.google.gson.JsonObject j) {
        CaveRecord r = new CaveRecord(j.get("x").getAsInt(), j.get("y").getAsInt(), j.get("z").getAsInt());
        r.cellCount = j.get("cells").getAsInt();
        r.seen.addAll(PackedCells.unpack(j.get("seenZ").getAsString()));
        for (var e : j.getAsJsonArray("ores")) {
            var a = e.getAsJsonArray();
            r.ores.put(Sightings.cell(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()), new Ore(a.get(3).getAsString(), a.get(4).getAsBoolean()));
        }
        r.lastAt = j.get("lastAt").getAsLong();
        r.walkedMinutes = j.get("walkedMinutes").getAsDouble();
        r.oreSeenWalking = j.get("oreSeenWalking").getAsInt();
        if (j.has("passages")) {
            List<io.github.mojolowjo.entropybot.cave.Passages.Passage> ps = new ArrayList<>();
            for (var e : j.getAsJsonArray("passages")) {
                var a = e.getAsJsonArray();
                ps.add(new io.github.mojolowjo.entropybot.cave.Passages.Passage(a.get(0).getAsInt(), a.get(1).getAsInt(),
                        new int[]{a.get(2).getAsInt(), a.get(3).getAsInt(), a.get(4).getAsInt()}, new int[]{a.get(5).getAsInt(), a.get(6).getAsInt(), a.get(7).getAsInt()},
                        a.get(8).getAsInt(), a.get(9).getAsInt(), a.get(10).getAsInt(), List.of(), a.get(11).getAsInt(), a.get(12).getAsInt(),
                        io.github.mojolowjo.entropybot.cave.Passages.EndKind.valueOf(a.get(13).getAsString())));
            }
            r.passages = List.copyOf(ps);
            r.passagesWhy = j.has("passagesWhy") ? j.get("passagesWhy").getAsString() : null;
        } else if (j.has("groups")) {
            // D15 pass two (rule 7): a save from before the passage model holds frontier groups - dropped, said; the
            // passages are measured again from the entry once the cave's cells load
            io.github.mojolowjo.entropybot.Quiet.line("cave: the record at " + r.where() + " is an old save (" + j.get("groups").getAsInt() + " frontier group(s), done "
                    + (j.has("done") && j.get("done").getAsBoolean()) + "): its groups are dropped, its passages are measured again when its cells load");
        }
        if (j.has("lips"))
            for (var e : j.getAsJsonArray("lips")) {
                var a = e.getAsJsonArray();
                r.lips.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                r.lipFeeds.add(null);
            }
        if (j.has("doneWhy")) { r.doneWhy = j.get("doneWhy").getAsString(); r.doneCells = j.get("doneCells").getAsInt(); }
        if (j.has("stuckSeen")) { r.stuckSeen = j.get("stuckSeen").getAsInt(); r.stuckOre = j.get("stuckOre").getAsInt(); r.stuckCells = j.get("stuckCells").getAsInt(); }
        return r;
    }
}
