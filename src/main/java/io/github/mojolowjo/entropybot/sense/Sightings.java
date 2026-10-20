package io.github.mojolowjo.entropybot.sense;

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
    public enum Kind { ORE, CAVE, ANIMAL }

    public static final int ANIMAL_RANGE = 32, MERGE = 16, MAX_CAVES = 400, MAX_ANIMALS = 400;
    public static final long CONFIRM_MS = 2000, CONFIRM_MAX_MS = 30_000, HALF_LIFE_MS = 10 * 60_000;
    public static final double MISS_CONF = 0.1;
    /** The cave-chance prior: {@link #PRIOR_CAVES} openings per 100 blocks walked, weighed as {@link #PRIOR_HUNDREDS} hundreds. */
    public static final double PRIOR_CAVES = 0.3, PRIOR_HUNDREDS = 5;

    /**
     * One sighting of a cave or a herd. at: when last seen (animals: game time in ms); conf: the confidence at {@code at}
     * (1 when seen, {@link #MISS_CONF} after a visit found none); exhausted: CAVE only, when it was found empty (0 = not).
     */
    public record Rec(Kind kind, String id, int x, int y, int z, long at, int count, double conf, long exhausted) {
        public String where() { return x + " " + y + " " + z; }
    }

    private final Seen ore;
    private final Map<String, List<Rec>> caves = new HashMap<>(), animals = new HashMap<>();
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
        int r = k == Kind.ANIMAL ? ANIMAL_RANGE : Seen.RANGE;
        double dx = x - ex, dy = y - ey, dz = z - ez;
        return dx * dx + dy * dy + dz * dz <= (double) r * r;
    }

    /**
     * Confirm-twice: true when the same candidate key was first seen {@link #CONFIRM_MS}..{@link #CONFIRM_MAX_MS} ago
     * (then it is forgotten here); else its first time is kept (a stale one starts over).
     */
    public synchronized boolean confirm(long key, long now) {
        Long first = pending.get(key);
        if (first == null || now - first > CONFIRM_MAX_MS) {
            if (pending.size() > 4096) pending.values().removeIf(t -> now - t > CONFIRM_MAX_MS);
            pending.put(key, now);
            return false;
        }
        if (now - first < CONFIRM_MS) return false;
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
        for (Rec r : l) if (d2(r, x, y, z) <= (long) MERGE * MERGE) return null;
        Rec r = new Rec(Kind.CAVE, "cave", x, y, z, now, 1, 1, 0);
        l.add(r);
        cavesFound++;
        while (l.size() > MAX_CAVES) l.remove(0);
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
            if (r.exhausted() == 0 && d2(r, x, y, z) <= (long) MERGE * MERGE) {
                l.set(i, new Rec(r.kind(), r.id(), r.x(), r.y(), r.z(), r.at(), r.count(), r.conf(), now));
                n++;
            }
        }
        if (n > 0) dirty = true;
        return n;
    }

    public synchronized List<Rec> caves(String dim) { return new ArrayList<>(caves.getOrDefault(dim, List.of())); }

    /** Blocks walked on the surface (the explore chance's denominator). */
    public synchronized void walked(double blocks) {
        if (blocks > 0 && blocks < 64) { walked += blocks; dirty = true; }
    }

    /** The learned chance to see a cave opening per 100 blocks walked, the prior blended in (never 0). */
    public synchronized double cavesPer100() {
        return (cavesFound + PRIOR_CAVES * PRIOR_HUNDREDS) / (walked / 100 + PRIOR_HUNDREDS);
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
            if (o.id().equals(type) && d2(o, x, y, z) <= (long) MERGE * MERGE) {
                l.remove(i);
                isNew = false;
                break;
            }
        }
        l.add(r);                                  // newest last (eviction: the oldest first)
        while (l.size() > MAX_ANIMALS) l.remove(0);
        if (fresh != null && fresh.length > 0) fresh[0] = isNew;
        dirty = true;
        return r;
    }

    /** The decayed confidence of a sighting at game time {@code now}: halves every {@link #HALF_LIFE_MS} (ANIMAL only). */
    public static double confidence(Rec r, long now) {
        if (r.kind() != Kind.ANIMAL) return r.exhausted() > 0 ? 0 : 1;
        double age = Math.max(0, now - r.at());
        return r.conf() * Math.pow(0.5, age / HALF_LIFE_MS);
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
        long bd = (long) MERGE * MERGE;
        for (int i = 0; i < l.size(); i++) {
            Rec r = l.get(i);
            long dx = r.x() - x, dz = r.z() - z, d = dx * dx + dz * dz;
            if (r.id().equals(type) && d <= bd) { bd = d; bi = i; }
        }
        if (bi < 0) return null;
        Rec o = l.get(bi);
        Rec n = found ? new Rec(o.kind(), o.id(), o.x(), o.y(), o.z(), now, Math.max(1, count), 1, 0)
                : new Rec(o.kind(), o.id(), o.x(), o.y(), o.z(), now, o.count(), MISS_CONF, 0);
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
                + String.format(Locale.ROOT, "%.2f caves per 100 blocks walked (%.0f walked)", cavesPer100(), walked)
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
        o.addProperty("walked", walked);
        o.addProperty("cavesFound", cavesFound);
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
            skipped[0]++;
            return new Sightings();
        }
        Sightings s = new Sightings(o.has("ore") ? Seen.fromJson(o.get("ore"), skipped) : new Seen());
        read(o, "caves", Kind.CAVE, s.caves, skipped);
        read(o, "animals", Kind.ANIMAL, s.animals, skipped);
        try {
            if (o.has("walked")) s.walked = Math.max(0, o.get("walked").getAsDouble());
            if (o.has("cavesFound")) s.cavesFound = Math.max(0, o.get("cavesFound").getAsDouble());
        } catch (RuntimeException ex) { skipped[0]++; }
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
                    } catch (RuntimeException ex) { skipped[0]++; }
                }
        } catch (RuntimeException ex) { skipped[0]++; }
    }

    private static long d2(Rec r, int x, int y, int z) {
        long dx = r.x() - x, dy = r.y() - y, dz = r.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
