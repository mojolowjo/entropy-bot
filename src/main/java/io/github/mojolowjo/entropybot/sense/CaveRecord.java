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
 * refreshed when its chunks load; only their count is persisted (the cells come back with the chunks).</li>
 * <li>{@code seen}: the cells the bot stood in or saw round it - the cave cells within {@link #SEE_STEPS} steps of the
 * feet through the cave's own air (a walker sees the tunnel round it; the rays do not fire at a known cave).</li>
 * <li>{@code frontier}: the seen cells with an unseen neighbour in the volume, grouped by connected run - a branch not
 * walked; each group with the unseen cells behind it (connected to it through unseen air).</li>
 * <li>{@code ores}: the ore cells seen in the cave (the ore index, by sight: rule 17), by family, left or mined.</li>
 * <li>{@code done}: no frontier group has more than {@link #BRANCH_MIN} unseen cells behind it.</li>
 * <li>{@code lastAt}, {@code walkedMinutes}, {@link #oreSeenPerMinute} (the measured yield the cave way prices with
 * once the cave has been walked).</li>
 * </ul>
 * Pure (JUnit: J6bCaveRecordTest). Loader notes: none.
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

    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** An ore cell seen in the cave: its family and whether it was mined. */
    public record Ore(String family, boolean mined) {}

    /** A branch: its seen frontier cells (first: the one the walk goes to), their count, the unseen cells behind. */
    public record Group(long first, int size, int behind) {}

    public final int x, y, z;
    private final Set<Long> seen = new HashSet<>();
    private Set<Long> cells = Set.of();
    private int cellCount;
    private final Map<Long, Ore> ores = new LinkedHashMap<>();
    private long lastAt;
    private double walkedMinutes;
    private int oreSeenWalking;
    private boolean doneSaved;
    private int groupsSaved = -1;
    /** J6b review 4: a walk that gained nothing (no cell seen, no ore mined) - the counts it left; -1: none. */
    private int stuckSeen = -1, stuckOre = -1, stuckCells = -1;

    CaveRecord(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

    public String where() { return x + " " + y + " " + z; }

    // ---- the store's writers (Sightings only) ----

    /** The volume's cells from the index (refreshed when its chunks load; never shrinks in a session - unloaded parts stay). */
    void recCells(Collection<Long> now) {
        Set<Long> all = new HashSet<>(cells);
        all.addAll(now);
        cells = all;
        cellCount = Math.max(cellCount, all.size());
        summarise();
    }

    /** The bot stands at x y z: the cave cells within SEE_STEPS through the cave's air are seen; the newly seen count. */
    int recStood(int fx, int fy, int fz, long now) {
        lastAt = now;
        long start = Sightings.cell(fx, fy, fz);
        if (!cells.contains(start)) start = nearestCell(fx, fy, fz);
        if (start == Long.MIN_VALUE) return 0;
        int added = 0;
        Map<Long, Integer> depth = new java.util.HashMap<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        depth.put(start, 0);
        q.add(start);
        while (!q.isEmpty()) {
            long c = q.poll();
            if (seen.add(c)) added++;
            int d = depth.get(c);
            if (d >= SEE_STEPS) continue;
            int[] p = Sightings.cellOf(c);
            for (int[] o : SIX) {
                long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                if (cells.contains(n) && !depth.containsKey(n)) { depth.put(n, d + 1); q.add(n); }
            }
        }
        if (added > 0) summarise();
        return added;
    }

    /** Cells seen otherwise (a ray passed them): those of the cave count. */
    int recSee(Collection<Long> passed) {
        int added = 0;
        for (long c : passed) if (cells.contains(c) && seen.add(c)) added++;
        if (added > 0) summarise();
        return added;
    }

    /** An ore cell seen in the cave (by sight: the ore index); true: new. A mined one stays mined. */
    boolean recOre(long cell, String family) {
        if (ores.containsKey(cell)) return false;
        ores.put(cell, new Ore(family, false));
        return true;
    }

    /** An ore cell of the cave mined. */
    void recMined(long cell) {
        Ore o = ores.get(cell);
        if (o != null && !o.mined()) ores.put(cell, new Ore(o.family(), true));
    }

    /**
     * A caving walk ended: its minutes inside and the ore cells it saw. {@code gained} false (J6b review 4: no cell seen
     * and no ore mined): the cave is not offered again until something new is known of it - a cell seen, an ore seen, more
     * cells from the index ({@link #stuck}); so a walk that cannot get on never loops.
     */
    void recWalked(double minutes, int oreSeen, long now, Boolean gained) {
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

    private void summarise() {
        if (cells.isEmpty()) return;
        List<Group> g = frontier();
        groupsSaved = g.size();
        boolean d = true;
        for (Group x : g) if (x.behind() > BRANCH_MIN) d = false;
        doneSaved = d && !seen.isEmpty();
    }

    private long nearestCell(int fx, int fy, int fz) {
        long best = Long.MIN_VALUE, bd = Long.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++)                                        // structural: the feet, a step either way
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    long c = Sightings.cell(fx + dx, fy + dy, fz + dz);
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (cells.contains(c) && d < bd) { bd = d; best = c; }
                }
        return best;
    }

    // ---- the readers ----

    /** The cells are loaded (refreshed from the index this session): frontier and done are measured now. */
    public boolean loaded() { return !cells.isEmpty(); }

    public int cellCount() { return cellCount; }

    public int seenCount() { return seen.size(); }

    /** The cells not seen yet (the cells' count less the seen ones, rough while not loaded). */
    public int unseen() {
        if (!loaded()) return Math.max(0, cellCount - seen.size());
        int in = 0;
        for (long c : seen) if (cells.contains(c)) in++;
        return Math.max(0, cellCount - in);
    }

    /** Seen cells / cells (0: no cells known). */
    public double explored() { return cellCount == 0 ? 0 : (cellCount - unseen()) / (double) cellCount; }

    /**
     * The branches not walked: the seen cells with an unseen neighbour in the volume, grouped by connected run, each with
     * the unseen cells behind it; largest behind first. Empty while not loaded.
     */
    public List<Group> frontier() {
        if (!loaded()) return List.of();
        Set<Long> front = new HashSet<>();
        for (long c : seen) {
            if (!cells.contains(c)) continue;
            int[] p = Sightings.cellOf(c);
            for (int[] o : SIX) {
                long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                if (cells.contains(n) && !seen.contains(n)) { front.add(c); break; }
            }
        }
        List<Group> out = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        for (long f : new java.util.TreeSet<>(front)) {
            if (!done.add(f)) continue;
            List<Long> group = new ArrayList<>();
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(f);
            while (!q.isEmpty()) {
                long c = q.poll();
                group.add(c);
                int[] p = Sightings.cellOf(c);
                for (int[] o : SIX) {
                    long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                    if (front.contains(n) && done.add(n)) q.add(n);
                }
            }
            out.add(new Group(group.get(0), group.size(), behind(group)));
        }
        out.sort((a, b) -> Integer.compare(b.behind(), a.behind()));
        return out;
    }

    /** The unseen cells connected (through unseen cave air) to the group. */
    private int behind(List<Long> group) {
        Set<Long> got = new HashSet<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        for (long c : group) {
            int[] p = Sightings.cellOf(c);
            for (int[] o : SIX) {
                long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                if (cells.contains(n) && !seen.contains(n) && got.add(n)) q.add(n);
            }
        }
        while (!q.isEmpty()) {
            int[] p = Sightings.cellOf(q.poll());
            for (int[] o : SIX) {
                long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                if (cells.contains(n) && !seen.contains(n) && got.add(n)) q.add(n);
            }
        }
        return got.size();
    }

    /** Explored to its end: no frontier group leads to more than BRANCH_MIN unseen cells (the last measure when not loaded). */
    public boolean done() { return doneSaved; }

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
        List<String> o = new ArrayList<>();
        for (var e : oresByFamily().entrySet()) o.add(e.getKey() + " " + e.getValue()[0] + " left " + e.getValue()[1] + " mined");
        return where() + ": explored " + Math.round(explored() * 100) + "% (" + (cellCount - unseen()) + " of " + cellCount + " cells), "
                + (groups() < 0 ? "branches not measured" : groups() + " branch" + (groups() == 1 ? "" : "es")) + (done() ? ", done" : "")
                + (o.isEmpty() ? ", no ore seen" : ", " + String.join(", ", o)) + (walkedMinutes > 0 ? String.format(java.util.Locale.ROOT, ", walked %.1f min", walkedMinutes) : "");
    }

    /** A copy of what the record says now, for readers off the store's lock (the planner, aa cave list, aa why). */
    public record Facts(int x, int y, int z, int cellCount, int unseen, double explored, int groups, boolean done, Map<String, int[]> ores,
                        double walkedMinutes, double oreSeenPerMinute, long lastAt, boolean loaded, String line, boolean stuck) {
        public Facts(int x, int y, int z, int cellCount, int unseen, double explored, int groups, boolean done, Map<String, int[]> ores,
                     double walkedMinutes, double oreSeenPerMinute, long lastAt, boolean loaded, String line) {
            this(x, y, z, cellCount, unseen, explored, groups, done, ores, walkedMinutes, oreSeenPerMinute, lastAt, loaded, line, false);
        }

        public int left(String family) { int[] v = ores.get(family); return v == null ? 0 : v[0]; }

        /** A way for the family: not stuck (review 4), and not done - or done with its ore seen and left. */
        public boolean offeredFor(String family) { return !stuck && (!done || left(family) > 0); }

        /** The cave way's yield: the one measured here once walked (review 2), else {@code prior}. */
        public double rate(double prior) { return walkedMinutes > 0 ? oreSeenPerMinute : prior; }
    }

    Facts facts() {
        return new Facts(x, y, z, cellCount, unseen(), explored(), groups(), done(), oresByFamily(), walkedMinutes, oreSeenPerMinute(), lastAt, loaded(), line(), stuck());
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
