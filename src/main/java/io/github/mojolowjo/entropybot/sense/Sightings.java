package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import io.github.mojolowjo.entropybot.Quiet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * AA_ROUND3 5: one memory of what the bot has legitimately seen, per world ({@code sightings.json}). Three kinds:
 * <ul>
 * <li>ORE: the seen ore cells of section 3 ({@link Seen}: fair play; mined = removed, no decay).</li>
 * <li>CAVE (0.39.z78 J6, the owner's ruling 2026-10-27 late): the caves known by rays ({@link KnownCave}: a CaveIndex
 *     volume {@link CaveIndex#KNOWN_RAYS} sight rays credited; its entry, size, depth, the hit cells; known stays known;
 *     J6b: each with its {@link CaveRecord}, D12). The dark-cell openings and the heightmap mouths are deleted.</li>
 * <li>TREE (aa-trees): a log column standing on dirt with leaves by it, seen (x y z = its lowest log; no decay; a visit that
 *     finds it gone or unreachable marks it {@code exhausted}, offered no more).</li>
 * <li>ANIMAL: herds by entity type (position, count, time); the confidence halves every {@link #HALF_LIFE_MS} of game
 *     time; a visit that finds none sets it to {@link #MISS_CONF} (kept: herds drift), one that finds some resets it.</li>
 * </ul>
 * One seeing rule for all kinds: within {@link Seen#RANGE} (animals {@link #ANIMAL_RANGE}), in the view cone
 * ({@link Seen#inCone}), one ray (the shared {@link Seen.Budget}); caves and animals count only when the same candidate
 * is seen twice {@link #CONFIRM_MS} apart ({@link #confirm}; one glance is noise). Ore keeps its one ray (a listed ore
 * block does not flicker; its candidates already come from the chunk index). Also learns the chance to see a cave per
 * 100 blocks walked (for the explore option). Pure (JUnit: SightingsTest). Loader notes: none (the rays and the
 * candidates live in SeenRuntime/SightingsRuntime).
 */
public final class Sightings {
    public enum Kind { ORE, CAVE, ANIMAL, TREE }

    public static int animalRange() { return Tuning.i("sightings.animalRange"); }
    public static int merge() { return Tuning.i("sightings.merge"); }
    public static int maxCaves() { return Tuning.i("sightings.maxCaves"); }
    public static int maxAnimals() { return Tuning.i("sightings.maxAnimals"); }
    public static int maxTrees() { return Tuning.i("sightings.maxTrees"); }
    public static int treeMerge() { return Tuning.i("sightings.treeMerge"); }
    public static int treeMinLogs() { return Tuning.i("sightings.treeMinLogs"); }
    public static long confirmMs() { return Tuning.l("sightings.confirmMs"); }
    public static long confirmMaxMs() { return Tuning.l("sightings.confirmMaxMs"); }
    public static long halfLifeMs() { return Tuning.l("sightings.halfLifeMs"); }
    public static double missConf() { return Tuning.get("sightings.missConf"); }
    /** The cave-chance prior: {@link #PRIOR_CAVES} openings per 100 blocks walked, weighed as {@link #PRIOR_HUNDREDS} hundreds. */
    public static double priorCaves() { return Tuning.get("sightings.priorCaves"); }
    public static double priorHundreds() { return Tuning.get("sightings.priorHundreds"); }

    /**
     * One sighting of a cave or a herd. at: when last seen (animals: game time in ms); conf: the confidence at {@code at}
     * (1 when seen, {@link #MISS_CONF} after a visit found none); exhausted: CAVE only, when it was found empty (0 = not).
     */
    public record Rec(Kind kind, String id, int x, int y, int z, long at, int count, double conf, long exhausted) {
        public String where() { return x + " " + y + " " + z; }
    }

    private final Seen ore;
    private final Map<String, List<Rec>> animals = new HashMap<>(), trees = new HashMap<>();
    private final Map<Long, Long> pending = new HashMap<>();
    private double walked, cavesFound;
    private volatile boolean dirty;

    public Sightings() { this(new Seen()); }

    public Sightings(Seen ore) { this.ore = ore == null ? new Seen() : ore; }

    /** Kind ORE: the fair-play cells (section 3). */
    public Seen ore() { return ore; }

    // ---- the rule ----

    /** Within range of the eyes for the kind (animals 32, the rest 24). */
    public static boolean inRange(Kind k, double ex, double ey, double ez, double x, double y, double z) {
        int r = k == Kind.ANIMAL ? animalRange() : Seen.range();
        double dx = x - ex, dy = y - ey, dz = z - ez;
        return dx * dx + dy * dy + dz * dz <= (double) r * r;
    }

    /**
     * Confirm-twice: true when the same candidate key was first seen {@link #CONFIRM_MS}..{@link #CONFIRM_MAX_MS} ago
     * (then it is forgotten here); else its first time is kept (a stale one starts over).
     */
    public synchronized boolean confirm(long key, long now) {
        Long first = pending.get(key);
        if (first == null || now - first > confirmMaxMs()) {
            if (pending.size() > 4096) pending.values().removeIf(t -> now - t > confirmMaxMs());
            pending.put(key, now);
            return false;
        }
        if (now - first < confirmMs()) return false;
        pending.remove(key);
        return true;
    }

    /** A candidate key: kind, id and a coarse cell (4 blocks), so a candidate a block off still matches. */
    public static long key(Kind k, String id, int x, int y, int z) {
        return (((long) k.ordinal()) << 60) ^ ((long) (id == null ? 0 : id.hashCode()) << 32) ^ Seen.key(x >> 2, y >> 2, z >> 2);
    }

    // ---- caves: 0.39.z78 J6 - the dark-cell sightings and the heightmap mouths are deleted; a cave is known by rays ----

    /** The nearest known cave within {@code max} (3D) of x y z, or null (J6b: whether it is worth a way is its record's). */
    public synchronized KnownCave nearestCave(String dim, int x, int y, int z, int max) {
        for (KnownCave c : knownCaves(dim, x, y, z, System.currentTimeMillis())) return d2k(c, x, y, z) <= (long) max * max ? c : null;
        return null;
    }

    // ---- 0.39.z78 J6: the known caves (CaveIndex volumes that KNOWN_RAYS sight rays credited), a stored fact ----

    /**
     * 0.39.z78 J6 (the owner's ruling 2026-10-27 late): a cave known by rays - its entry cell (a cell the rays saw, the
     * seen cell nearest its column's surface), its size bucket and depth band (from the index volume when it became known;
     * never its cells - the manager's ruling: no cell list reaches the planner), when, the hit
     * cells of the rays that credited it ({@link #cell} packed: the cells seen in it). Known stays known in this world.
     * 0.39.z78 J6b (rule 23): its walks are its {@link CaveRecord}'s (the walk fields and the cave.walkedHoldS hold are
     * gone: a cave done with none of the family left is no way, CaveRecord.Facts#offeredFor).
     */
    public record KnownCave(int x, int y, int z, CaveIndex.Size size, CaveIndex.Depth depth, long knownAt, List<Long> hits) {
        public KnownCave {
            hits = List.copyOf(hits);
        }

        public String where() { return x + " " + y + " " + z; }
    }

    private final Map<String, List<KnownCave>> knownCaves = new HashMap<>();

    /** The known cave a CaveIndex credit made: stored (returned) once - a known cave with the same entry or a shared hit cell is that cave (null). */
    public synchronized KnownCave knowCave(String dim, CaveIndex.Known k, long now) {
        if (k == null) return null;
        for (KnownCave c : knownCaves.getOrDefault(dim, List.of())) {
            if (c.x() == k.x() && c.y() == k.y() && c.z() == k.z()) return null;
            for (long h : k.hits()) if (c.hits().contains(h)) return null;
        }
        KnownCave c = new KnownCave(k.x(), k.y(), k.z(), k.size(), k.depth(), now, k.hits());
        List<KnownCave> l = knownCaves.computeIfAbsent(dim, d -> new ArrayList<>());
        l.add(c);
        while (l.size() > maxCaves()) {
            KnownCave gone = l.remove(0);
            Map<String, CaveRecord> rs = caveRecords.get(dim);
            if (rs != null) rs.remove(gone.where());             // J6b: its record goes with it (one store)
        }
        cavesFound++;
        dirty = true;
        return c;
    }

    /** Every known cave of the dimension, walked ones included (aa cave list; the index marks their volumes known). */
    public synchronized List<KnownCave> allKnownCaves(String dim) { return List.copyOf(knownCaves.getOrDefault(dim, List.of())); }

    /** The known caves nearest first from x y z (empty: none known); J6b: which one is a way is its record's. */
    public synchronized List<KnownCave> knownCaves(String dim, int x, int y, int z, long now) {
        List<KnownCave> out = new ArrayList<>(knownCaves.getOrDefault(dim, List.of()));
        out.sort((a, b) -> Long.compare(d2k(a, x, y, z), d2k(b, x, y, z)));
        return out;
    }

    /** The known cave whose entry is x y z (null: none). */
    public synchronized KnownCave knownCave(String dim, int x, int y, int z) {
        for (KnownCave c : knownCaves.getOrDefault(dim, List.of())) if (c.x() == x && c.y() == y && c.z() == z) return c;
        return null;
    }


    // ---- 0.39.z78 J6b (D12): the cave records, beside the known caves - the one store (rule 23); only these methods write ----

    private final Map<String, Map<String, CaveRecord>> caveRecords = new HashMap<>();

    /** The record of the known cave entered at x y z (made on first use); null: no known cave there. */
    private CaveRecord rec(String dim, int x, int y, int z) {
        if (knownCave(dim, x, y, z) == null) return null;
        return caveRecords.computeIfAbsent(dim, d -> new HashMap<>()).computeIfAbsent(x + " " + y + " " + z, k -> new CaveRecord(x, y, z));
    }

    /** The record of the known cave at x y z as it stands (a copy); null: no known cave there. */
    public synchronized CaveRecord.Facts caveFacts(String dim, int x, int y, int z) {
        CaveRecord r = rec(dim, x, y, z);
        return r == null ? null : r.facts();
    }

    /** 0.39.z78 J6c: the air-exposed blocks of the known cave at x y z (CaveRecord.Exposure); null: no known cave there. */
    public synchronized CaveRecord.Exposure caveExposure(String dim, int x, int y, int z) {
        CaveRecord r = rec(dim, x, y, z);
        return r == null ? null : r.exposure();
    }

    /** 0.39.z78 J6c: one step of the known cave's air-exposed count over {@code air} (tests, a replay: the cells given whole). */
    public synchronized int caveExpose(String dim, int x, int y, int z, io.github.mojolowjo.entropybot.aa.Nearby.Open air, int budget) {
        CaveRecord r = rec(dim, x, y, z);
        return r == null ? 0 : r.recExpose(air, budget);
    }

    /** The branches not walked of the known cave at x y z (largest behind first; empty: not loaded, or none). */
    public synchronized List<CaveRecord.Group> caveFrontier(String dim, int x, int y, int z) {
        CaveRecord r = rec(dim, x, y, z);
        return r == null ? List.of() : r.frontier();
    }

    /** The ore cells of {@code family} seen in the known cave at x y z and not mined. */
    public synchronized List<int[]> caveLeft(String dim, int x, int y, int z, String family) {
        CaveRecord r = rec(dim, x, y, z);
        return r == null ? List.of() : r.left(family);
    }

    /** The known cave at x y z: its volume's cells given whole (tests, a replay; in place). */
    public synchronized void caveCells(String dim, int x, int y, int z, java.util.Collection<Long> cells) {
        CaveRecord r = rec(dim, x, y, z);
        if (r == null || cells.isEmpty()) return;
        int was = r.cellCount();
        r.recCells(cells);
        if (r.cellCount() != was) dirty = true;
    }

    /**
     * 0.39.z78 run EK (the manager's fix): one budgeted step of every known cave's record in {@code dim} taking its
     * volume's cells from the cave index (CaveIndex#feed: the chunks put since the last pass, a volume joined), in place;
     * {@code budget} cells touched at most for all of them this tick (CaveRecord.BUDGET). The cells touched.
     */
    public synchronized int caveFeed(String dim, CaveIndex idx, int budget) {
        int used = 0;
        // J6c review: every record's tick starts here, the ones the budget loop does not reach too (their exposure step)
        Map<String, CaveRecord> rs = caveRecords.get(dim);
        if (rs != null) for (CaveRecord r : rs.values()) r.recTick();
        for (KnownCave k : allKnownCaves(dim)) {
            if (used >= budget) break;
            CaveRecord r = rec(dim, k.x(), k.y(), k.z());
            if (r == null) continue;
            int was = r.cellCount();
            used += r.recFeed(idx, budget - used);
            if (r.cellCount() != was) dirty = true;
        }
        return used;
    }

    /** Run EK (2): the known cave's unseen cell nearest its entry (CaveRecord#firstUnseen); null: none, or not loaded. */
    public synchronized int[] caveFirstUnseen(String dim, int x, int y, int z) {
        CaveRecord r = rec(dim, x, y, z);
        return r == null ? null : r.firstUnseen();
    }

    /** Run EK: the cells the known cave's record touched since the last ask (its budget's measure; 0: no record). */
    public synchronized long caveTouched(String dim, int x, int y, int z) {
        CaveRecord r = rec(dim, x, y, z);
        return r == null ? 0 : r.takeTouched();
    }

    /** The bot stands at fx fy fz in the known cave at x y z: the cells round it are seen; the newly seen count. */
    public synchronized int caveStood(String dim, int x, int y, int z, int fx, int fy, int fz, long now) {
        CaveRecord r = rec(dim, x, y, z);
        if (r == null) return 0;
        int n = r.recStood(fx, fy, fz, now);
        if (n > 0) dirty = true;
        return n;
    }

    /** Cells a ray passed in the known cave at x y z (the cave's count). */
    public synchronized int caveSaw(String dim, int x, int y, int z, java.util.Collection<Long> passed) {
        CaveRecord r = rec(dim, x, y, z);
        if (r == null) return 0;
        int n = r.recSee(passed);
        if (n > 0) dirty = true;
        return n;
    }

    /** An ore cell seen in the known cave at x y z (by sight); true: new. */
    public synchronized boolean caveOre(String dim, int x, int y, int z, long cell, String family) {
        CaveRecord r = rec(dim, x, y, z);
        if (r == null || !r.recOre(cell, family)) return false;
        dirty = true;
        return true;
    }

    /**
     * 0.39.z78 J6c run ER (rule 23 (2); 07:41:17-07:41:30: the helmet's vein list was capped at 8 cells, Baritone's mine
     * process broke the 9th iron block -8 -5 62 too, the record heard only the listed cells and kept it "left"; the
     * chestplate's caving step walked to it at 07:44:59 and ended NO_PROGRESS): the ONE reader of mined ore for the cave
     * records - an ore cell the world shows broken (ChunkPass.blockChanged: ore -> no ore; or the caving step reading the
     * seen cell as no ore now) is mined in every record of {@code dim} that holds it, whoever broke it. True: a record
     * changed.
     */
    public synchronized boolean oreBroken(String dim, int x, int y, int z) {
        Map<String, CaveRecord> rs = dim == null ? null : caveRecords.get(dim);
        if (rs == null) return false;
        long cell = cell(x, y, z);
        boolean any = false;
        for (CaveRecord r : rs.values()) any |= r.recMined(cell);
        if (any) dirty = true;
        return any;
    }

    /** A caving walk in the known cave at x y z ended: its minutes and the ore cells it saw (the measured yield). */
    public synchronized void caveWalked(String dim, int x, int y, int z, double minutes, int oreSeen, long now) { caveWalked(dim, x, y, z, minutes, oreSeen, now, true); }

    /**
     * As above; {@code gained} false: the walk saw no cell and mined no ore - the cave is held until something new is known
     * (review 4); null: judge nothing (an owner stop: the mark stays as it was).
     */
    public synchronized void caveWalked(String dim, int x, int y, int z, double minutes, int oreSeen, long now, Boolean gained) {
        CaveRecord r = rec(dim, x, y, z);
        if (r == null) return;
        r.recWalked(minutes, oreSeen, now, gained);
        dirty = true;
    }

    private static JsonObject recordsJson(Map<String, Map<String, CaveRecord>> m) {
        JsonObject o = new JsonObject();
        for (var d : new TreeMap<>(m).entrySet()) {
            JsonArray a = new JsonArray();
            for (CaveRecord r : new TreeMap<>(d.getValue()).values()) a.add(r.json());
            o.add(d.getKey(), a);
        }
        return o;
    }

    private static void readRecords(JsonObject o, Sightings s, int[] skipped) {
        try {
            if (!o.has("caveRecords")) return;
            for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("caveRecords").entrySet())
                for (JsonElement e : d.getValue().getAsJsonArray()) {
                    try {
                        CaveRecord r = CaveRecord.of(e.getAsJsonObject());
                        s.caveRecords.computeIfAbsent(d.getKey(), x -> new HashMap<>()).put(r.where(), r);
                    } catch (RuntimeException ex) {
                        Quiet.warn("Sightings.readRecords: a cave record skipped", ex); skipped[0]++; }
                }
        } catch (RuntimeException ex) {
            Quiet.warn("Sightings.readRecords failed, carrying on with the fallback", ex); skipped[0]++; }
    }

    private static long d2k(KnownCave c, int x, int y, int z) {
        long dx = c.x() - x, dy = c.y() - y, dz = c.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static JsonObject knownJson(Map<String, List<KnownCave>> m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, List<KnownCave>> d : new TreeMap<>(m).entrySet()) {
            JsonArray a = new JsonArray();
            for (KnownCave c : d.getValue()) {
                JsonArray e = new JsonArray();
                e.add(c.x()); e.add(c.y()); e.add(c.z()); e.add(c.size().name()); e.add(c.depth().name()); e.add(c.knownAt());
                JsonArray h = new JsonArray();
                for (Long x : c.hits()) h.add(x);
                e.add(h);
                a.add(e);
            }
            o.add(d.getKey(), a);
        }
        return o;
    }

    private static void readKnown(JsonObject o, Sightings s, int[] skipped) {
        try {
            if (!o.has("knownCaves")) return;
            for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("knownCaves").entrySet())
                for (JsonElement e : d.getValue().getAsJsonArray()) {
                    try {
                        JsonArray a = e.getAsJsonArray();
                        List<Long> h = new ArrayList<>();
                        for (JsonElement c : a.get(6).getAsJsonArray()) h.add(c.getAsLong());
                        s.knownCaves.computeIfAbsent(d.getKey(), x -> new ArrayList<>()).add(new KnownCave(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(),
                                CaveIndex.Size.valueOf(a.get(3).getAsString()), CaveIndex.Depth.valueOf(a.get(4).getAsString()), a.get(5).getAsLong(), h));   // J6b: an older file's walk fields (7, 8) are left unread
                    } catch (RuntimeException ex) {
                        Quiet.warn("Sightings.readKnown failed, carrying on with the fallback", ex); skipped[0]++; }
                }
        } catch (RuntimeException ex) {
            Quiet.warn("Sightings.readKnown failed, carrying on with the fallback", ex); skipped[0]++; }
    }

    /** The radius within which two caves are one feature ({@code cave.mergeM}): the caving step's visit log merges by it. */
    public static int factMerge() { return Tuning.i("cave.mergeM"); }

    /** A cell packed in one long (x, z 26 bits, y 12 bits; BlockPos.asLong's layout). */
    public static long cell(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** {x, y, z} of a {@link #cell}. */
    public static int[] cellOf(long c) {
        return new int[]{(int) (c >> 38), (int) (c << 52 >> 52), (int) (c << 26 >> 38)};
    }

    
    /** Blocks walked on the surface (the explore chance's denominator). */
    public synchronized void walked(double blocks) {
        if (blocks > 0 && blocks < 64) { walked += blocks; dirty = true; }
    }

    /** The learned chance to see a cave opening per 100 blocks walked, the prior blended in (never 0). */
    public synchronized double cavesPer100() {
        return (cavesFound + priorCaves() * priorHundreds()) / (walked / 100 + priorHundreds());
    }

    // ---- trees (aa-trees) ----

    /**
     * The tree candidate rule for a log the ray hit: the column's lowest log stands on dirt (grass, podzol, mud...),
     * {@link #TREE_MIN_LOGS}+ logs stack up, and leaves touch the top part. A log pile or a build rests on another
     * ground or has no leaves (the chopper's own TreeFinder judges the same way); a log the bot placed never counts.
     */
    public static boolean treeRule(boolean groundDirt, int logsInColumn, boolean leavesNear, boolean placedByMe) {
        return groundDirt && logsInColumn >= treeMinLogs() && leavesNear && !placedByMe;
    }

    /** A confirmed tree at its lowest log: a new record (returned) unless one lies within {@link #TREE_MERGE} (null). */
    public synchronized Rec sawTree(String dim, int x, int y, int z, int logs, long now) {
        List<Rec> l = trees.computeIfAbsent(dim, k -> new ArrayList<>());
        for (Rec r : l) if (r.exhausted() == 0 && d2(r, x, y, z) <= (long) treeMerge() * treeMerge()) return null;
        Rec r = new Rec(Kind.TREE, "tree", x, y, z, now, Math.max(1, logs), 1, 0);
        l.add(r);
        while (l.size() > maxTrees()) l.remove(0);
        dirty = true;
        return r;
    }

    /** The seen trees (not exhausted) within {@code max} blocks (horizontal) of x z, nearest first. */
    public synchronized List<Rec> treesNear(String dim, int x, int z, int max) {
        List<Rec> out = new ArrayList<>();
        long m2 = (long) max * max;
        for (Rec r : trees.getOrDefault(dim, List.of())) {
            long dx = r.x() - x, dz = r.z() - z;
            if (r.exhausted() == 0 && dx * dx + dz * dz <= m2) out.add(r);
        }
        out.sort((a, b) -> Long.compare((long) (a.x() - x) * (a.x() - x) + (long) (a.z() - z) * (a.z() - z),
                (long) (b.x() - x) * (b.x() - x) + (long) (b.z() - z) * (b.z() - z)));
        return out;
    }

    /** The tree within {@link #TREE_MERGE} of x z was visited and is gone (or cannot be reached): offered no more. How many. */
    public synchronized int exhaustTree(String dim, int x, int z, long now) {
        int n = 0;
        List<Rec> l = trees.getOrDefault(dim, List.of());
        for (int i = 0; i < l.size(); i++) {
            Rec r = l.get(i);
            long dx = r.x() - x, dz = r.z() - z;
            if (r.exhausted() == 0 && dx * dx + dz * dz <= (long) treeMerge() * treeMerge()) {
                l.set(i, new Rec(r.kind(), r.id(), r.x(), r.y(), r.z(), r.at(), r.count(), r.conf(), now));
                n++;
            }
        }
        if (n > 0) dirty = true;
        return n;
    }

    public synchronized int treeCount(String dim) {
        int n = 0;
        for (Rec r : trees.getOrDefault(dim, List.of())) if (r.exhausted() == 0) n++;
        return n;
    }

    // ---- animals ----

    /**
     * A confirmed herd of {@code count} of a type round x y z: the record of that type within {@link #MERGE} takes the
     * new place, count and time with confidence 1, else a new record. Returns the record and whether it was new
     * (out[0]).
     */
    public synchronized Rec sawHerd(String dim, String type, int x, int y, int z, int count, long now, boolean[] fresh) {
        List<Rec> l = animals.computeIfAbsent(dim, k -> new ArrayList<>());
        Rec r = new Rec(Kind.ANIMAL, type, x, y, z, now, Math.max(1, count), 1, 0);
        boolean isNew = true;
        for (int i = 0; i < l.size(); i++) {
            Rec o = l.get(i);
            if (o.id().equals(type) && d2(o, x, y, z) <= (long) merge() * merge()) {
                l.remove(i);
                isNew = false;
                break;
            }
        }
        l.add(r);                                  // newest last (eviction: the oldest first)
        while (l.size() > maxAnimals()) l.remove(0);
        if (fresh != null && fresh.length > 0) fresh[0] = isNew;
        dirty = true;
        return r;
    }

    /** The decayed confidence of a sighting at game time {@code now}: halves every {@link #HALF_LIFE_MS} (ANIMAL only). */
    public static double confidence(Rec r, long now) {
        if (r.kind() != Kind.ANIMAL) return r.exhausted() > 0 ? 0 : 1;
        double age = Math.max(0, now - r.at());
        return r.conf() * Math.pow(0.5, age / halfLifeMs());
    }

    /** The herds of a type (null: every type) in the dimension. */
    public synchronized List<Rec> animals(String dim, String type) {
        List<Rec> out = new ArrayList<>();
        for (Rec r : animals.getOrDefault(dim, List.of())) if (type == null || r.id().equals(type)) out.add(r);
        return out;
    }

    /**
     * A visit to the sighting of a type nearest x z (within {@link #MERGE}): none found = confidence {@link #MISS_CONF}
     * (kept), some found = confidence 1, the count and the time renewed. The record, or null when none was there.
     */
    public synchronized Rec visit(String dim, String type, int x, int z, boolean found, int count, long now) {
        List<Rec> l = animals.getOrDefault(dim, List.of());
        int bi = -1;
        long bd = (long) merge() * merge();
        for (int i = 0; i < l.size(); i++) {
            Rec r = l.get(i);
            long dx = r.x() - x, dz = r.z() - z, d = dx * dx + dz * dz;
            if (r.id().equals(type) && d <= bd) { bd = d; bi = i; }
        }
        if (bi < 0) return null;
        Rec o = l.get(bi);
        Rec n = found ? new Rec(o.kind(), o.id(), o.x(), o.y(), o.z(), now, Math.max(1, count), 1, 0)
                : new Rec(o.kind(), o.id(), o.x(), o.y(), o.z(), now, o.count(), missConf(), 0);
        l.set(bi, n);
        dirty = true;
        return n;
    }

    public synchronized int animalCount(String dim) { return animals.getOrDefault(dim, List.of()).size(); }

    // ---- status and file ----

    public synchronized String text(String dim, long gameNow) {
        List<String> a = new ArrayList<>();
        for (Rec r : animals.getOrDefault(dim, List.of()))
            a.add(r.id().replaceFirst("^minecraft:", "") + " x" + r.count() + " at " + r.x() + " " + r.z() + String.format(Locale.ROOT, " (%.2f)", confidence(r, gameNow)));
        int open = 0, ex = 0;
        for (KnownCave c : knownCaves.getOrDefault(dim, List.of())) {
            CaveRecord r = caveRecords.getOrDefault(dim, Map.of()).get(c.where());
            if (r != null && r.done()) ex++; else open++;
        }
        return "sightings: " + ore.size(dim) + " ore cells, " + open + " known caves (" + ex + " explored to their end), "
                + String.format(Locale.ROOT, "%.2f caves per 100 blocks walked (%.0f walked)", cavesPer100(), walked) + ", " + treeCount(dim) + " trees"
                + (a.isEmpty() ? ", no animals seen yet" : ", animals: " + String.join(", ", a.subList(Math.max(0, a.size() - 6), a.size())));
    }

    public boolean dirty() { return dirty || ore.dirty(); }

    public synchronized void saved() { dirty = false; ore.saved(); }

    /** review: the file text and the clean mark in one lock (a sighting added in between is not lost). */
    public synchronized String snapshot() {
        String s = toJson();
        saved();
        return s;
    }

    public synchronized String toJson() {
        JsonObject o = new JsonObject();
        o.add("ore", ore.toJson());
        o.add("animals", recs(animals));
        o.add("trees", recs(trees));
        o.addProperty("walked", walked);
        o.addProperty("cavesFound", cavesFound);
        o.add("knownCaves", knownJson(knownCaves));          // 0.39.z78 J6: the caves known by rays
        o.add("caveRecords", recordsJson(caveRecords));      // 0.39.z78 J6b: their records (D12)
        return new Gson().toJson(o);
    }

    private static JsonObject recs(Map<String, List<Rec>> m) {
        JsonObject ds = new JsonObject();
        for (Map.Entry<String, List<Rec>> d : new TreeMap<>(m).entrySet()) {
            JsonArray a = new JsonArray();
            for (Rec r : d.getValue()) {
                JsonArray e = new JsonArray();
                e.add(r.id());
                e.add(r.x());
                e.add(r.y());
                e.add(r.z());
                e.add(r.at());
                e.add(r.count());
                e.add(r.conf());
                e.add(r.exhausted());
                a.add(e);
            }
            ds.add(d.getKey(), a);
        }
        return ds;
    }

    /** Loads the file; bad entries are skipped and counted in skipped[0]. A null or broken text gives an empty memory. */
    public static Sightings fromJson(String json, int[] skipped) {
        JsonObject o;
        try {
            JsonElement e = new Gson().fromJson(json, JsonElement.class);
            if (e == null || !e.isJsonObject()) { skipped[0]++; return new Sightings(); }
            o = e.getAsJsonObject();
        } catch (RuntimeException ex) {
            Quiet.warn("Sightings.fromJson failed, carrying on with the fallback", ex);
            skipped[0]++;
            return new Sightings();
        }
        Sightings s = new Sightings(o.has("ore") ? Seen.fromJson(o.get("ore"), skipped) : new Seen());
        read(o, "animals", Kind.ANIMAL, s.animals, skipped);
        read(o, "trees", Kind.TREE, s.trees, skipped);
        readKnown(o, s, skipped);
        readRecords(o, s, skipped);
        try {
            if (o.has("walked")) s.walked = Math.max(0, o.get("walked").getAsDouble());
            if (o.has("cavesFound")) s.cavesFound = Math.max(0, o.get("cavesFound").getAsDouble());
        } catch (RuntimeException ex) {
            Quiet.warn("Sightings.fromJson failed, carrying on with the fallback", ex); skipped[0]++; }
        s.saved();
        return s;
    }

    private static void read(JsonObject o, String name, Kind k, Map<String, List<Rec>> into, int[] skipped) {
        try {
            if (!o.has(name)) return;
            for (Map.Entry<String, JsonElement> d : o.getAsJsonObject(name).entrySet())
                for (JsonElement e : d.getValue().getAsJsonArray()) {
                    try {
                        JsonArray a = e.getAsJsonArray();
                        into.computeIfAbsent(d.getKey(), x -> new ArrayList<>()).add(new Rec(k, a.get(0).getAsString(), a.get(1).getAsInt(), a.get(2).getAsInt(),
                                a.get(3).getAsInt(), a.get(4).getAsLong(), a.get(5).getAsInt(), a.get(6).getAsDouble(), a.get(7).getAsLong()));
                    } catch (RuntimeException ex) {
            Quiet.warn("Sightings.read failed, carrying on with the fallback", ex); skipped[0]++; }
                }
        } catch (RuntimeException ex) {
            Quiet.warn("Sightings.read failed, carrying on with the fallback", ex); skipped[0]++; }
    }

    private static long d2(Rec r, int x, int y, int z) {
        long dx = r.x() - x, dy = r.y() - y, dz = r.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }
}

