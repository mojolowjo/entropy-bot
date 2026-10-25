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
 * <li>CAVE: cave openings, ravines and dark holes (no decay: openings do not move; a cave entered and found empty of
 *     ore is marked {@code exhausted} with its time and offered no more).</li>
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
    private final Map<String, List<Rec>> caves = new HashMap<>(), animals = new HashMap<>(), trees = new HashMap<>();
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

    // ---- caves ----

    /**
     * The cave candidate rule for an air cell the ray reached: "dark" = air 2 high, sky light under 8, block light 0,
     * below the surface heightmap (a roof over it); "ravine" = air 2 high, open to the sky, with ground 6+ higher on
     * both sides along one axis (sideMax: the highest surface within 5 blocks on -x, +x, -z, +z). A cell the bot dug
     * itself is never a cave (its own shaft or mine). Null = no cave.
     */
    public static String caveRule(boolean air2, int sky, int block, int y, int surfaceY, int[] sideMax, boolean ownDug) {
        if (!air2 || ownDug) return null;
        if (y < surfaceY - 1) return sky < 8 && block == 0 ? "dark" : null;
        if (sideMax == null || sideMax.length < 4) return null;
        boolean xs = sideMax[0] >= y + 6 && sideMax[1] >= y + 6, zs = sideMax[2] >= y + 6 && sideMax[3] >= y + 6;
        return xs || zs ? "ravine" : null;
    }

    /** A confirmed cave opening: a new record (returned) unless one lies within {@link #MERGE} (null). */
    public synchronized Rec sawCave(String dim, int x, int y, int z, long now) {
        List<Rec> l = caves.computeIfAbsent(dim, k -> new ArrayList<>());
        for (Rec r : l) if (d2(r, x, y, z) <= (long) merge() * merge()) return null;
        Rec r = new Rec(Kind.CAVE, "cave", x, y, z, now, 1, 1, 0);
        l.add(r);
        cavesFound++;
        while (l.size() > maxCaves()) l.remove(0);
        dirty = true;
        return r;
    }

    /** The nearest cave not exhausted within {@code max} (3D) of x y z, or null. */
    public synchronized Rec nearestCave(String dim, int x, int y, int z, int max) {
        Rec best = null;
        long bd = (long) max * max;
        for (Rec r : caves.getOrDefault(dim, List.of())) {
            if (r.exhausted() > 0) continue;
            long d = d2(r, x, y, z);
            if (d <= bd) { bd = d; best = r; }
        }
        return best;
    }

    /** The caves within {@link #MERGE} of x y z were entered and held no ore: offered no more. How many. */
    public synchronized int exhaust(String dim, int x, int y, int z, long now) {
        int n = 0;
        List<Rec> l = caves.getOrDefault(dim, List.of());
        for (int i = 0; i < l.size(); i++) {
            Rec r = l.get(i);
            if (r.exhausted() == 0 && d2(r, x, y, z) <= (long) merge() * merge()) {
                l.set(i, new Rec(r.kind(), r.id(), r.x(), r.y(), r.z(), r.at(), r.count(), r.conf(), now));
                n++;
            }
        }
        if (n > 0) dirty = true;
        return n;
    }

    public synchronized List<Rec> caves(String dim) { return new ArrayList<>(caves.getOrDefault(dim, List.of())); }

    /** 0.39.z44: the id of a cave mouth record ({@link CaveMouths}: a heightmap hole confirmed by sight). */
    public static final String MOUTH = "mouth";

    /**
     * 0.39.z44: a confirmed cave mouth at x y z, seen from {@code seenFrom} blocks (kept in the record's count): a new record
     * (returned) unless a mouth lies within {@link #merge()} (null).
     */
    public synchronized Rec sawMouth(String dim, int x, int y, int z, int seenFrom, long now) {
        List<Rec> l = caves.computeIfAbsent(dim, k -> new ArrayList<>());
        for (Rec r : l) if (MOUTH.equals(r.id()) && d2(r, x, y, z) <= (long) merge() * merge()) return null;
        Rec r = new Rec(Kind.CAVE, MOUTH, x, y, z, now, Math.max(0, seenFrom), 1, 0);
        l.add(r);
        while (l.size() > maxCaves()) l.remove(0);
        dirty = true;
        return r;
    }

    /** 0.39.z44: the known mouths (not exhausted), nearest first from x y z. */
    public synchronized List<Rec> mouths(String dim, int x, int y, int z) {
        List<Rec> out = new ArrayList<>();
        for (Rec r : caves.getOrDefault(dim, List.of())) if (MOUTH.equals(r.id()) && r.exhausted() == 0) out.add(r);
        out.sort((a, b) -> Long.compare(d2(a, x, y, z), d2(b, x, y, z)));
        return out;
    }

    /** 0.39.z44 (rule 15, gone): the mouth record at exactly x y z is forgotten; true when there was one. */
    public synchronized boolean forgetMouth(String dim, int x, int y, int z) {
        boolean gone = caves.getOrDefault(dim, new ArrayList<>()).removeIf(r -> MOUTH.equals(r.id()) && r.x() == x && r.y() == y && r.z() == z);
        if (gone) dirty = true;
        return gone;
    }

    // ---- 0.39.z51 items 1-2: the confirmation of a mouth, a stored fact ----

    /**
     * 0.39.z51: a mouth's confirmation (run L: the "confirmed" line 101 times in 11 min for one mouth): the candidate cell
     * x y z, when it was confirmed, the hole's floor cell (fx fy fz: the first air over its floor) and the dark pocket cells
     * the z50 fill found ({@link #cell} packed); {@code walked}: when a caving step walked its pocket (0 = not yet).
     */
    public record MouthFact(int x, int y, int z, long confirmedAt, int fx, int fy, int fz, List<Long> pocket, long walked, int oreOnWalk) {
        /** z53: oreOnWalk for a mouth closed for good (S1: in a protect box): never offered again. */
        public static final int CLOSED = -1; // structural: a marker, not an ore count

        public MouthFact {
            pocket = List.copyOf(pocket);
        }

        public MouthFact(int x, int y, int z, long confirmedAt, int fx, int fy, int fz, List<Long> pocket, long walked) {
            this(x, y, z, confirmedAt, fx, fy, fz, pocket, walked, 0);
        }

        /**
         * z53 (review of 7b16ad9): offered to the cave way at {@code now}: never walked; walked and it yielded ore on its last
         * walk; walked with 0 ore and {@code holdMs} has passed since. Closed for good: never.
         */
        public boolean offered(long now, long holdMs) {
            if (walked == 0) return true;
            if (oreOnWalk == CLOSED) return false;
            return oreOnWalk > 0 || now - walked >= holdMs;
        }

        public String where() { return x + " " + y + " " + z; }
    }

    private final Map<String, List<MouthFact>> mouthFacts = new HashMap<>();

    /** The radius within which two mouth candidates are one feature ({@code cave.mergeM}): one fact for both. */
    public static int factMerge() { return Tuning.i("cave.mergeM"); }

    /** A cell packed in one long (x, z 26 bits, y 12 bits; BlockPos.asLong's layout). */
    public static long cell(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** {x, y, z} of a {@link #cell}. */
    public static int[] cellOf(long c) {
        return new int[]{(int) (c >> 38), (int) (c << 52 >> 52), (int) (c << 26 >> 38)};
    }

    /**
     * The confirmation of the mouth at x y z: stored (returned) once; a fact within {@link #factMerge()} (horizontal) already
     * holds it (one feature, z50's merge): null, the stored fact is kept as it is.
     */
    public synchronized MouthFact confirmMouth(String dim, int x, int y, int z, long at, int fx, int fy, int fz, List<Long> pocket) {
        if (mouthFact(dim, x, y, z) != null) return null;
        MouthFact f = new MouthFact(x, y, z, at, fx, fy, fz, pocket == null ? List.of() : pocket, 0);
        List<MouthFact> l = mouthFacts.computeIfAbsent(dim, k -> new ArrayList<>());
        l.add(f);
        while (l.size() > maxCaves()) l.remove(0);
        dirty = true;
        return f;
    }

    /** The stored confirmation of the mouth at (or merged with, {@link #factMerge()}) x y z; null: none stored. */
    public synchronized MouthFact mouthFact(String dim, int x, int y, int z) {
        long m = (long) factMerge() * factMerge();
        for (MouthFact f : mouthFacts.getOrDefault(dim, List.of())) {
            long dx = f.x() - x, dz = f.z() - z;
            if (dx * dx + dz * dz <= m) return f;
        }
        return null;
    }

    /** Tuning {@code cave.walkedHoldS} in ms: a mouth walked with no ore is held this long (z53). */
    public static long walkedHoldMs() { return Tuning.l("cave.walkedHoldS") * 1000L; }

    /** The confirmed mouths offered now ({@link MouthFact#offered}), nearest first from x y z (empty: none known). */
    public synchronized List<MouthFact> confirmedMouths(String dim, int x, int y, int z) {
        return confirmedMouths(dim, x, y, z, System.currentTimeMillis());
    }

    public synchronized List<MouthFact> confirmedMouths(String dim, int x, int y, int z, long now) {
        long hold = walkedHoldMs();
        List<MouthFact> out = new ArrayList<>();
        for (MouthFact f : mouthFacts.getOrDefault(dim, List.of())) if (f.offered(now, hold)) out.add(f);
        out.sort((a, b) -> Long.compare(d2f(a, x, y, z), d2f(b, x, y, z)));
        return out;
    }

    /** z51 form: a walk with no ore counted (held {@code cave.walkedHoldS}). */
    public synchronized boolean walkedMouth(String dim, int x, int y, int z, long now) {
        return walkedMouth(dim, x, y, z, now, 0);
    }

    /**
     * A caving step walked the pocket of the mouth at x y z and got {@code ore} there ({@link MouthFact#CLOSED}: closed for
     * good). The fact keeps walkedAt and oreOnWalk; {@link MouthFact#offered} reads them. True when there was one.
     */
    public synchronized boolean walkedMouth(String dim, int x, int y, int z, long now, int ore) {
        List<MouthFact> l = mouthFacts.getOrDefault(dim, List.of());
        for (int i = 0; i < l.size(); i++) {
            MouthFact f = l.get(i);
            if (f.x() == x && f.y() == y && f.z() == z && f.oreOnWalk() != MouthFact.CLOSED) {
                l.set(i, new MouthFact(f.x(), f.y(), f.z(), f.confirmedAt(), f.fx(), f.fy(), f.fz(), f.pocket(), Math.max(1, now), ore));
                dirty = true;
                return true;
            }
        }
        return false;
    }

    private static long d2f(MouthFact f, int x, int y, int z) {
        long dx = f.x() - x, dy = f.y() - y, dz = f.z() - z;
        return dx * dx + dy * dy + dz * dz;
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
        for (Rec r : caves.getOrDefault(dim, List.of())) if (r.exhausted() > 0) ex++; else open++;
        return "sightings: " + ore.size(dim) + " ore cells, " + open + " caves (" + ex + " exhausted), "
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
        o.add("caves", recs(caves));
        o.add("animals", recs(animals));
        o.add("trees", recs(trees));
        o.addProperty("walked", walked);
        o.addProperty("cavesFound", cavesFound);
        JsonObject mf = new JsonObject();                    // 0.39.z51: the mouths' confirmations
        for (Map.Entry<String, List<MouthFact>> d : new TreeMap<>(mouthFacts).entrySet()) {
            JsonArray a = new JsonArray();
            for (MouthFact f : d.getValue()) {
                JsonArray e = new JsonArray();
                e.add(f.x()); e.add(f.y()); e.add(f.z()); e.add(f.confirmedAt()); e.add(f.fx()); e.add(f.fy()); e.add(f.fz()); e.add(f.walked());
                JsonArray pk = new JsonArray();
                for (Long c : f.pocket()) pk.add(c);
                e.add(pk);
                e.add(f.oreOnWalk());                        // z53: index 9 (older files: 0)
                a.add(e);
            }
            mf.add(d.getKey(), a);
        }
        o.add("mouthFacts", mf);
        return new Gson().toJson(o);
    }

    private static void readFacts(JsonObject o, Sightings s, int[] skipped) {
        try {
            if (!o.has("mouthFacts")) return;
            for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("mouthFacts").entrySet())
                for (JsonElement e : d.getValue().getAsJsonArray()) {
                    try {
                        JsonArray a = e.getAsJsonArray();
                        List<Long> pk = new ArrayList<>();
                        for (JsonElement c : a.get(8).getAsJsonArray()) pk.add(c.getAsLong());
                        s.mouthFacts.computeIfAbsent(d.getKey(), x -> new ArrayList<>()).add(new MouthFact(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(),
                                a.get(3).getAsLong(), a.get(4).getAsInt(), a.get(5).getAsInt(), a.get(6).getAsInt(), pk, a.get(7).getAsLong(),
                                a.size() > 9 ? a.get(9).getAsInt() : 0));
                    } catch (RuntimeException ex) {
                        Quiet.warn("Sightings.readFacts failed, carrying on with the fallback", ex); skipped[0]++; }
                }
        } catch (RuntimeException ex) {
            Quiet.warn("Sightings.readFacts failed, carrying on with the fallback", ex); skipped[0]++; }
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
        read(o, "caves", Kind.CAVE, s.caves, skipped);
        read(o, "animals", Kind.ANIMAL, s.animals, skipped);
        read(o, "trees", Kind.TREE, s.trees, skipped);
        readFacts(o, s, skipped);
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

