package io.github.mojolowjo.entropybot.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AA_ROUND3 3 (fair play): the ore cells the bot has legitimately seen. A cell counts as seen when it is within
 * {@link #RANGE} blocks of the eyes, has an exposed face (an air or water neighbour), lies in the view cone
 * ({@link #CONE_DEG} from the look direction) and one ray from the eyes reaches it (the adapter, {@code SeenRuntime},
 * casts at most {@link #RAYS_PER_SEC} rays a second). Kept per world and dimension with the time seen, saved in the
 * same file as {@link OreStats}; a mined cell leaves it. With {@link #fair()} on (the default, {@code aa fair on|off})
 * every ore consumer (the iron scorer, ore goals, the mine's known pick, the ore detour) reads {@link #filter} or
 * {@link #has} instead of the whole chunk index. Also learns the cave yield (ores seen per minute of caving) for the
 * discovery options. Pure (JUnit: SeenTest). Loader notes: none (the rays and the hooks live in SeenRuntime/ChunkPass).
 */
public final class Seen {
    public static final int RANGE = 24, RAYS_PER_SEC = 64;
    /** Half-angle of the view cone (degrees): about the default 70 FOV's corners. */
    public static final double CONE_DEG = 60;
    /** Cells kept per dimension (the oldest seen go first past it). */
    public static final int MAX = 20_000;
    /** The cave-yield prior: this many ores of a family per minute, weighed as {@link #PRIOR_MIN} minutes of caving. */
    public static final double PRIOR_RATE = 0.5, PRIOR_MIN = 2.0;

    private static volatile boolean fair = true;

    /** {@code aa fair}: true = consumers read only seen cells; false = today's behaviour (the whole index). */
    public static boolean fair() { return fair; }

    public static void fair(boolean on) { fair = on; }

    /** One seen cell: its family ("iron") and when it was seen (ms). */
    public record Cell(int x, int y, int z, String family, long at) {}

    /** dim -> key -> cell, oldest first (eviction order). */
    private final Map<String, LinkedHashMap<Long, Cell>> dims = new HashMap<>();
    private volatile boolean dirty;
    private double caveMinutes;
    /** family -> ores first seen while caving. */
    private final Map<String, Double> caveOres = new java.util.TreeMap<>();
    /** Set by the adapter while a cave task runs: new sightings count as cave yield. */
    public volatile boolean caving;

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    // ---- the rules (pure) ----

    /** Within {@link #RANGE} of the eyes (to the cell's centre). */
    public static boolean inRange(double ex, double ey, double ez, int x, int y, int z) {
        double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
        return dx * dx + dy * dy + dz * dz <= (double) RANGE * RANGE;
    }

    /** In the view cone: the angle between the look vector (lx ly lz, any length) and the cell's centre is at most {@link #CONE_DEG}. */
    public static boolean inCone(double ex, double ey, double ez, double lx, double ly, double lz, int x, int y, int z) {
        double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz), ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (dl < 1.0) return true;          // the cell I stand in or touch
        if (ll == 0) return false;
        return (dx * lx + dy * ly + dz * lz) / (dl * ll) >= Math.cos(Math.toRadians(CONE_DEG));
    }

    /** The face to aim the ray at: of the open faces (open[i] for {@link #FACES}[i]) the one turned most toward the eyes; -1 none toward them. */
    public static int faceToward(double ex, double ey, double ez, int x, int y, int z, boolean[] open) {
        int best = -1;
        double bestDot = 0;
        for (int i = 0; i < FACES.length; i++) {
            if (!open[i]) continue;
            int[] f = FACES[i];
            double cx = x + 0.5 + f[0] * 0.5, cy = y + 0.5 + f[1] * 0.5, cz = z + 0.5 + f[2] * 0.5;
            double dot = (ex - cx) * f[0] + (ey - cy) * f[1] + (ez - cz) * f[2];
            if (dot > bestDot) { bestDot = dot; best = i; }
        }
        return best;
    }

    public static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** A candidate for a ray: in range, in the cone, an open face turned toward the eyes, not seen yet. */
    public boolean candidate(String dim, double ex, double ey, double ez, double lx, double ly, double lz, int x, int y, int z, boolean[] open) {
        return !has(dim, x, y, z) && inRange(ex, ey, ez, x, y, z) && inCone(ex, ey, ez, lx, ly, lz, x, y, z) && faceToward(ex, ey, ez, x, y, z, open) >= 0;
    }

    /** The ray budget: {@link #RAYS_PER_SEC} a second, at most one second banked. */
    public static final class Budget {
        private double tokens = RAYS_PER_SEC;
        private long last = -1;

        public boolean take(long now) {
            if (last >= 0) tokens = Math.min(RAYS_PER_SEC, tokens + (now - last) * RAYS_PER_SEC / 1000.0);
            last = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }
    }

    // ---- the store ----

    /** Notes a sighting; true when the cell is new (the caller logs "seen iron at ..."). */
    public synchronized boolean saw(String dim, int x, int y, int z, String family, long now) {
        LinkedHashMap<Long, Cell> m = dims.computeIfAbsent(dim, k -> new LinkedHashMap<>());
        long k = key(x, y, z);
        boolean fresh = m.remove(k) == null;          // a re-seen cell moves to the newest end
        m.put(k, new Cell(x, y, z, family, now));
        if (fresh) {
            if (caving && family != null) caveOres.merge(family, 1.0, Double::sum);
            while (m.size() > MAX) m.remove(m.keySet().iterator().next());
        }
        dirty = true;
        return fresh;
    }

    public synchronized boolean has(String dim, int x, int y, int z) {
        LinkedHashMap<Long, Cell> m = dims.get(dim);
        return m != null && m.containsKey(key(x, y, z));
    }

    /** The cell was mined (or is no ore any more): forgotten. */
    public synchronized boolean mined(String dim, int x, int y, int z) {
        LinkedHashMap<Long, Cell> m = dims.get(dim);
        boolean gone = m != null && m.remove(key(x, y, z)) != null;
        if (gone) dirty = true;
        return gone;
    }

    public synchronized int size(String dim) {
        LinkedHashMap<Long, Cell> m = dims.get(dim);
        return m == null ? 0 : m.size();
    }

    /** The hits I have seen (the order kept); the fair consumers' one gate. */
    public synchronized List<OreIndex.Hit> filter(String dim, List<OreIndex.Hit> hits) {
        List<OreIndex.Hit> out = new ArrayList<>();
        for (OreIndex.Hit h : hits) if (has(dim, h.x(), h.y(), h.z())) out.add(h);
        return out;
    }

    /** Seen cells of a family within r (3D) of x y z. */
    public synchronized List<Cell> near(String dim, String family, int x, int y, int z, int r) {
        List<Cell> out = new ArrayList<>();
        LinkedHashMap<Long, Cell> m = dims.get(dim);
        if (m == null) return out;
        long r2 = (long) r * r;
        for (Cell c : m.values()) {
            long dx = c.x() - x, dy = c.y() - y, dz = c.z() - z;
            if ((family == null || family.equals(c.family())) && dx * dx + dy * dy + dz * dz <= r2) out.add(c);
        }
        return out;
    }

    // ---- cave yield ----

    public synchronized void caveTime(double minutes) {
        if (minutes > 0) { caveMinutes += minutes; dirty = true; }
    }

    /** Ores of a family seen per minute of caving, the prior blended in (never 0). */
    public synchronized double caveRate(String family) {
        return (caveOres.getOrDefault(family, 0.0) + PRIOR_RATE * PRIOR_MIN) / (caveMinutes + PRIOR_MIN);
    }

    public synchronized String caveText() {
        return String.format(java.util.Locale.ROOT, "caving %.1f min, seen %s (prior %.1f/min a family)", caveMinutes, caveOres, PRIOR_RATE);
    }

    public boolean dirty() { return dirty; }

    public void saved() { dirty = false; }

    // ---- file (a part of ore-stats.json) ----

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonObject ds = new JsonObject();
        for (Map.Entry<String, LinkedHashMap<Long, Cell>> d : dims.entrySet()) {
            JsonArray a = new JsonArray();
            for (Cell c : d.getValue().values()) {
                JsonArray e = new JsonArray();
                e.add(c.x());
                e.add(c.y());
                e.add(c.z());
                e.add(c.family() == null ? "" : c.family());
                e.add(c.at());
                a.add(e);
            }
            ds.add(d.getKey(), a);
        }
        o.add("cells", ds);
        o.addProperty("caveMinutes", caveMinutes);
        JsonObject co = new JsonObject();
        for (Map.Entry<String, Double> e : caveOres.entrySet()) co.addProperty(e.getKey(), e.getValue());
        o.add("caveOres", co);
        return o;
    }

    /** Loads the part; bad entries are skipped and counted in skipped[0]. */
    public static Seen fromJson(JsonElement j, int[] skipped) {
        Seen s = new Seen();
        if (j == null || !j.isJsonObject()) return s;
        JsonObject o = j.getAsJsonObject();
        try {
            if (o.has("cells")) for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("cells").entrySet())
                for (JsonElement e : d.getValue().getAsJsonArray()) {
                    try {
                        JsonArray a = e.getAsJsonArray();
                        int x = a.get(0).getAsInt(), y = a.get(1).getAsInt(), z = a.get(2).getAsInt();
                        String f = a.get(3).getAsString();
                        s.dims.computeIfAbsent(d.getKey(), k -> new LinkedHashMap<>()).put(key(x, y, z), new Cell(x, y, z, f.isEmpty() ? null : f, a.get(4).getAsLong()));
                    } catch (RuntimeException ex) { skipped[0]++; }
                }
            if (o.has("caveMinutes")) s.caveMinutes = Math.max(0, o.get("caveMinutes").getAsDouble());
            if (o.has("caveOres") && o.get("caveOres").isJsonObject())
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("caveOres").entrySet()) s.caveOres.put(e.getKey(), Math.max(0, e.getValue().getAsDouble()));
        } catch (RuntimeException ex) {
            skipped[0]++;
        }
        return s;
    }
}
