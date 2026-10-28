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
 * <li>{@code frontier}: the seen cells with an unseen neighbour in the volume, kept incrementally as cells arrive and
 * are seen; grouped by connected run (a branch not walked), re-grouped only when it changed; each group with the unseen
 * cells behind it counted up to {@link #BRANCH_MIN} + 1 (the only question asked of it: more than BRANCH_MIN?).</li>
 * <li>{@code ores}: the ore cells seen in the cave (the ore index, by sight: rule 17), by family, left or mined.</li>
 * <li>{@code done}: THE done reader (rule 23 (2)): something seen and no frontier group with more than
 * {@link #BRANCH_MIN} unseen cells behind it; the caving step asks it.</li>
 * <li>{@code lastAt}, {@code walkedMinutes}, {@link #oreSeenPerMinute} (the measured yield the cave way prices with
 * once the cave has been walked).</li>
 * </ul>
 * 0.39.z78 run EK (the manager's fix; 02:30:20-02:46:19: every caving tick 7-70 s - the whole 209,647-cell volume
 * flooded for every branch, several times a tick, and on every planner ask): no reader walks the record whole. The sums
 * (cells seen in the volume) and the frontier change with each cell; the facts are cached until a record write
 * ({@link #facts}); the cells come by budgeted steps. Pure (JUnit: J6bCaveRecordTest, J6bEkTest). Loader notes: none.
 */
public final class CaveRecord {
    /**
     * The unseen cells a frontier group must lead to, to be a branch: two blocks of the cave carver's thinnest tunnel (2
     * by 2, CaveIndex.CROSS_SECTION 4 cells a block; configured_carver/cave.json radius 1.5 x 0.7). Less is a dead-end nook
     * a player glances into, not a way on.
     */
    public static final int BRANCH_MIN = 2 * CaveIndex.CROSS_SECTION; // structural: two blocks of the thinnest carver tunnel
    /**
     * The steps through the cave's air from the feet a walker counts as seen: what a player sees of a dark tunnel round
     * him without a torch, about a carver tunnel's width (2-3) past the next bend either way (an estimate; the owner
     * allows the cheat for known caves: "Fine with using cheats to remember where we have been").
     */
    public static final int SEE_STEPS = 6; // structural: a tunnel's width past the next bend, either way (estimate)
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

    /**
     * A branch: its seen frontier cells (first: the one the walk goes to), their count, the unseen cells behind - counted
     * up to {@link #BRANCH_MIN} + 1 (run EK: "more than BRANCH_MIN" is all any reader asks).
     */
    public record Group(long first, int size, int behind, List<Long> cells) {
        public Group {
            cells = cells == null || cells.isEmpty() ? List.of(first) : List.copyOf(cells);
        }

        public Group(long first, int size, int behind) { this(first, size, behind, List.of(first)); }
    }

    /**
     * Run EN: the frontier cells a group keeps for the step's reach goal (its first ones from {@code first}, through the
     * group): enough for the few nearest the feet the goal is built over (CaveWalk.REACH_CELLS), never the whole group.
     */
    public static final int GROUP_CELLS = 16; // structural: a few times the reach goal's cells, the branch's mouth

    public final int x, y, z;
    private final Set<Long> seen = new HashSet<>();
    private final Set<Long> cells = new HashSet<>();
    private int cellCount;
    /** Run EK: the seen cells that are cells of the volume (unseen() = cellCount - seenIn). */
    private int seenIn;
    /** Run EK: the seen volume cells with an unseen volume neighbour; {@link #groups} rebuilt only when it changed. */
    private final java.util.TreeSet<Long> frontierCells = new java.util.TreeSet<>();     // sorted: a regroup walks it in steps
    private boolean frontierChanged = true;
    private List<Group> groupsNow = List.of();
    private final Map<Long, Ore> ores = new LinkedHashMap<>();
    private long lastAt;
    private double walkedMinutes;
    private int oreSeenWalking;
    private boolean doneSaved;
    private int groupsSaved = -1;
    /** J6b review 4: a walk that gained nothing (no cell seen, no ore mined) - the counts it left; -1: none. */
    private int stuckSeen = -1, stuckOre = -1, stuckCells = -1;
    /** Run EK: the index feed (null: cells given whole, recCells) and the facts' cache (by the record's version). */
    private CaveIndex.Feed feed;
    private long version, factsAt = -1;
    private Facts factsNow;
    /** Run EK: the cells touched since the last {@link #takeTouched} (the budget's JUnit). */
    private long touched;

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
        regroupSpent = 0;                                                   // 88b28290 review 1: a new tick (the one feeder's step)
        int n = idx.feed(x, y, z, feed, budget, this::addCell);
        touched += n;
        return n;
    }

    private void addCell(long c) {
        if (!cells.add(c)) return;
        version++;
        cellCount = Math.max(cellCount, cells.size());
        if (seen.contains(c)) {
            seenIn++;
            recheck(c);
            return;
        }
        int[] p = Sightings.cellOf(c);
        for (int[] o : SIX) {                                                   // a seen neighbour has an unseen one now
            long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
            if (seen.contains(n) && cells.contains(n) && frontierCells.add(n)) frontierChanged = true;
        }
    }

    /** {@code s} is a frontier cell: seen, of the volume, with an unseen volume neighbour. */
    private void recheck(long s) {
        boolean f = false;
        if (cells.contains(s) && seen.contains(s)) {
            int[] p = Sightings.cellOf(s);
            for (int[] o : SIX) {
                long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                if (cells.contains(n) && !seen.contains(n)) { f = true; break; }
            }
        }
        if (f ? frontierCells.add(s) : frontierCells.remove(s)) frontierChanged = true;
    }

    /** A cell seen: the sum and the frontier round it changed in place; true: new. */
    private boolean markSeen(long c) {
        if (!seen.add(c)) return false;
        version++;
        touched++;
        if (!cells.contains(c)) return true;
        seenIn++;
        recheck(c);
        int[] p = Sightings.cellOf(c);
        for (int[] o : SIX) {
            long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
            if (cells.contains(n) && seen.contains(n)) recheck(n);              // c may have been its last unseen neighbour
        }
        return true;
    }

    /**
     * The bot stands at x y z: the cave cells within SEE_STEPS through the cave's air are seen, from the feet's cell or
     * the cave cell nearest the feet within the eyes' reach (run EK: the lip 3 x 2 from the entry sees into it); the
     * newly seen count.
     */
    int recStood(int fx, int fy, int fz, long now) {
        if (lastAt != now) { lastAt = now; version++; regroupSpent = 0; }   // 88b28290 review 1: the stand is once a tick too
        long start = Sightings.cell(fx, fy, fz);
        if (!cells.contains(start)) start = cellInReach(fx, fy, fz);
        if (start == Long.MIN_VALUE) return 0;
        int added = 0;
        Map<Long, Integer> depth = new java.util.HashMap<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        depth.put(start, 0);
        q.add(start);
        while (!q.isEmpty()) {
            long c = q.poll();
            touched++;
            if (markSeen(c)) added++;
            int d = depth.get(c);
            if (d >= SEE_STEPS) continue;
            int[] p = Sightings.cellOf(c);
            for (int[] o : SIX) {
                long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                if (cells.contains(n) && !depth.containsKey(n)) { depth.put(n, d + 1); q.add(n); }
            }
        }
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
    void recMined(long cell) {
        Ore o = ores.get(cell);
        if (o != null && !o.mined()) { ores.put(cell, new Ore(o.family(), true)); version++; }
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

    /**
     * Run EK (2): the step's first target - the unseen cave cell nearest the ENTRY through the cave's air (the entry
     * itself while unseen), searched from the entry's cell (or the cave cell nearest it) over {@link #BUDGET} cells at
     * most; null: none within the budget, or the cells not loaded.
     */
    public int[] firstUnseen() {
        long start = Sightings.cell(x, y, z);
        if (!cells.contains(start)) start = cellInReach(x, y, z);
        if (start == Long.MIN_VALUE) return null;
        Set<Long> got = new HashSet<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        got.add(start);
        q.add(start);
        while (!q.isEmpty() && got.size() <= BUDGET) {
            long c = q.poll();
            touched++;
            if (!seen.contains(c)) return Sightings.cellOf(c);
            int[] p = Sightings.cellOf(c);
            for (int[] o : SIX) {
                long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                if (cells.contains(n) && got.add(n)) q.add(n);
            }
        }
        return null;
    }

    // ---- the readers ----

    /**
     * The cells are loaded: given whole, or the index's first feed pass this session ran to its end - frontier and done
     * are measured now. Run EK: while the first pass runs the record is {@link #filling}, not loaded (rule 15).
     */
    public boolean loaded() { return !cells.isEmpty() && (feed == null || feed.complete()); }

    /** Run EK: the index's first feed pass is running (the cells so far: {@link #cellsSoFar}). */
    public boolean filling() { return feed != null && !feed.complete() && feed.filling(); }

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
     * The branches not walked: the frontier cells grouped by connected run, each with the unseen cells behind it (up to
     * BRANCH_MIN + 1); the most behind first, then by cell. Rebuilt only when the frontier changed. Empty while not loaded.
     */
    public List<Group> frontier() {
        if (!loaded()) return List.of();
        if (job == null && frontierChanged) {
            frontierChanged = false;
            job = new Regroup(frontierCells);
        }
        if (job != null) {
            // 88b28290 review 1: a frontier of thousands (a long walk in a 200k cave) is regrouped in steps - all at once
            // when it is small (BUDGET cells), else BUDGET cells a tick (spent counted from the tick's feed or stand)
            int allow = job.size <= BUDGET ? Integer.MAX_VALUE : Math.max(0, BUDGET - regroupSpent);
            long before = touched;
            if (job.step(allow)) {
                groupsNow = job.result();
                groupsSaved = groupsNow.size();
                job = null;
            }
            regroupSpent += (int) Math.min(Integer.MAX_VALUE, touched - before);
        }
        return groupsNow;
    }

    /** 88b28290 review 1: the regroup in progress (null: the groups are current), and the regroup cells spent this tick. */
    private Regroup job;
    private int regroupSpent;

    /** Run EK / 88b28290 review 1: the frontier is being regrouped (its groups lag by a tick or a few; not done meanwhile). */
    public boolean regrouping() { return job != null; }

    /** One regroup of the frontier as it stood (its cells sorted once), resumable cell by cell. */
    private final class Regroup {
        /** The frontier as it stands (kept sorted: a TreeSet), walked from {@code cursor} up - never copied or sorted again. */
        final int size;
        long cursor = Long.MIN_VALUE;
        boolean started;
        final Set<Long> done = new HashSet<>();
        final List<Group> out = new ArrayList<>();
        ArrayDeque<Long> q;
        List<Long> group;

        Regroup(java.util.NavigableSet<Long> frontier) { size = frontier.size(); }

        /** Up to {@code allow} cells touched; true: finished. */
        boolean step(int allow) {
            long start = touched;
            while (touched - start < allow) {
                if (q == null) {
                    Long nx = started ? frontierCells.higher(cursor) : (frontierCells.isEmpty() ? null : frontierCells.first());
                    started = true;
                    while (nx != null && done.contains(nx)) { touched++; cursor = nx; nx = frontierCells.higher(nx); }
                    if (nx == null) {
                        out.sort((a, b) -> b.behind() != a.behind() ? Integer.compare(b.behind(), a.behind()) : Long.compare(a.first(), b.first()));
                        return true;
                    }
                    cursor = nx;
                    done.add(nx);
                    q = new ArrayDeque<>();
                    q.add(nx);
                    group = new ArrayList<>();
                }
                while (!q.isEmpty() && touched - start < allow) {
                    long c = q.poll();
                    group.add(c);
                    touched++;
                    int[] p = Sightings.cellOf(c);
                    for (int[] o : SIX) {
                        long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                        if (frontierCells.contains(n) && done.add(n)) q.add(n);
                    }
                }
                if (q.isEmpty()) {
                    out.add(new Group(group.get(0), group.size(), behind(group), group.subList(0, Math.min(GROUP_CELLS, group.size()))));
                    q = null;
                }
            }
            return false;
        }

        List<Group> result() { return List.copyOf(out); }
    }

    /** The unseen cells connected (through unseen cave air) to the group, counted up to BRANCH_MIN + 1 (run EK: the cap). */
    private int behind(List<Long> group) {
        Set<Long> got = new HashSet<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        for (long c : group) {
            touched++;
            int[] p = Sightings.cellOf(c);
            for (int[] o : SIX) {
                long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                if (cells.contains(n) && !seen.contains(n) && got.add(n)) { q.add(n); if (got.size() > BRANCH_MIN) return got.size(); }
            }
        }
        while (!q.isEmpty()) {
            int[] p = Sightings.cellOf(q.poll());
            touched++;
            for (int[] o : SIX) {
                long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                if (cells.contains(n) && !seen.contains(n) && got.add(n)) { q.add(n); if (got.size() > BRANCH_MIN) return got.size(); }
            }
        }
        return got.size();
    }

    /**
     * THE done reader (rule 23 (2); run EK 02:24:18: the step's own "no branch" fall-through ended a cave it had seen
     * nothing of): loaded - something of the volume seen and no frontier group with more than BRANCH_MIN unseen cells
     * behind; not loaded - the last measure.
     */
    public boolean done() {
        if (!loaded()) return doneSaved;
        List<Group> gs = frontier();
        if (job != null) return false;                                     // 88b28290 review 1: the groups lag the frontier: not known, not done
        boolean d = seenIn > 0;
        for (Group g : gs) if (g.behind() > BRANCH_MIN) d = false;
        doneSaved = d;
        return d;
    }

    /** The frontier groups at the last measure (-1: never measured). */
    public int groups() { return loaded() ? frontier().size() : groupsSaved; }

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

    /** "x y z: explored 60%, 2 branches, iron 3 left 1 mined, walked 4 min" (aa cave list, aa why, the step's end line). */
    public String line() {
        int g = groups();
        int un = unseen();
        List<String> o = new ArrayList<>();
        for (var e : oresByFamily().entrySet()) o.add(e.getKey() + " " + e.getValue()[0] + " left " + e.getValue()[1] + " mined");
        return where() + ": explored " + Math.round(explored() * 100) + "% (" + (cellCount - un) + " of " + cellCount + " cells)"
                + (filling() ? " (reading its cells: " + cells.size() + " so far)" : "") + ", "
                + (g < 0 ? "branches not measured" : g + " branch" + (g == 1 ? "" : "es")) + (done() ? ", done" : "")
                + (o.isEmpty() ? ", no ore seen" : ", " + String.join(", ", o)) + (walkedMinutes > 0 ? String.format(java.util.Locale.ROOT, ", walked %.1f min", walkedMinutes) : "");
    }

    /** Run EK: the cells touched since the last call (the budget's measure), and the count starts again. */
    long takeTouched() {
        long t = touched;
        touched = 0;
        return t;
    }

    /** A copy of what the record says now, for readers off the store's lock (the planner, aa cave list, aa why). */
    public record Facts(int x, int y, int z, int cellCount, int unseen, double explored, int groups, boolean done, Map<String, int[]> ores,
                        double walkedMinutes, double oreSeenPerMinute, long lastAt, boolean loaded, String line, boolean stuck, boolean filling) {
        public Facts(int x, int y, int z, int cellCount, int unseen, double explored, int groups, boolean done, Map<String, int[]> ores,
                     double walkedMinutes, double oreSeenPerMinute, long lastAt, boolean loaded, String line, boolean stuck) {
            this(x, y, z, cellCount, unseen, explored, groups, done, ores, walkedMinutes, oreSeenPerMinute, lastAt, loaded, line, stuck, false);
        }

        public Facts(int x, int y, int z, int cellCount, int unseen, double explored, int groups, boolean done, Map<String, int[]> ores,
                     double walkedMinutes, double oreSeenPerMinute, long lastAt, boolean loaded, String line) {
            this(x, y, z, cellCount, unseen, explored, groups, done, ores, walkedMinutes, oreSeenPerMinute, lastAt, loaded, line, false, false);
        }

        public int left(String family) { int[] v = ores.get(family); return v == null ? 0 : v[0]; }

        /** A way for the family: not stuck (review 4), and not done - or done with its ore seen and left. */
        public boolean offeredFor(String family) { return !stuck && (!done || left(family) > 0); }

        /** The cave way's yield: the one measured here once walked long enough ({@link #measured}, review 2), else {@code prior}. */
        public double rate(double prior) { return measured(prior) ? oreSeenPerMinute : prior; }

        /**
         * Run EO (rule 15; run EO 05:13:44: a 0.5-min walk that saw no ore made the cave's yield 0 and the cave was dropped
         * silently): the yield is MEASURED only once the minutes walked here are at least the minutes the prior expects
         * one ore in ({@link #measureFloorMinutes}); under that floor a walk that saw nothing says nothing - not measured,
         * the prior holds.
         */
        public boolean measured(double prior) { return walkedMinutes > 0 && walkedMinutes >= measureFloorMinutes(prior); }
    }

    /**
     * Run EO: the floor of minutes walked before a cave's yield counts as measured - the time the prior yield
     * ({@code prior} ore a minute) expects ONE ore in (1 / prior): less walked, zero seen is no evidence. Structural: one
     * expected ore, from the prior itself (no knob). A prior of 0 or not known: never measured by a walk of no ore.
     */
    public static double measureFloorMinutes(double prior) {
        return prior > 0 ? 1.0 / prior : Double.POSITIVE_INFINITY;    // structural: the minutes one expected ore takes
    }

    /** Run EK: the facts, cached until a record write (a cell fed or seen, an ore, a walk) - the planner's asks read the cache. */
    Facts facts() {
        if (factsNow != null && factsAt == version && !frontierChanged && job == null) return factsNow;
        factsNow = new Facts(x, y, z, cellCount, unseen(), explored(), groups(), done(), oresByFamily(), walkedMinutes, oreSeenPerMinute(), lastAt, loaded(), line(), stuck(), filling());
        factsAt = version;
        return factsNow;
    }

    // ---- the file (the store's) ----

    com.google.gson.JsonObject json() {
        if (loaded()) done();                                               // the saved measure is the latest
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
        j.addProperty("done", doneSaved);
        j.addProperty("groups", groupsSaved);
        j.addProperty("stuckSeen", stuckSeen); j.addProperty("stuckOre", stuckOre); j.addProperty("stuckCells", stuckCells);
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
        r.doneSaved = j.get("done").getAsBoolean();
        r.groupsSaved = j.get("groups").getAsInt();
        if (j.has("stuckSeen")) { r.stuckSeen = j.get("stuckSeen").getAsInt(); r.stuckOre = j.get("stuckOre").getAsInt(); r.stuckCells = j.get("stuckCells").getAsInt(); }
        return r;
    }
}
