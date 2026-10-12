package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.memory.Limits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 0.28.0 (the one chunk pass, docs/CHUNKPASS_PLAN.md): what the bot knows of one chunk, kept small. Per ore family
 * (cave/strip OreSpec families: {@code deepslate_iron_ore} -> {@code iron}) and per POI kind: the exact count and up
 * to {@link Limits#SENSE_SPOTS} positions (each with its block id); per section the lava source count (a section with
 * {@link #LAVA_LAKE} or more is a "lava lake"); per column the top block's y (WORLD_SURFACE). Built by
 * {@link SummaryBuilder} at a full read, patched per block from then on.
 *
 * <p>Plain Java (no game classes), JUnit. Loader notes: none. Game thread only (no locking).
 */
public final class ChunkSummary {
    public static final int NO_TOP = Integer.MIN_VALUE;
    /** Lava sources in one section that make a "lava lake" (as PoiScanner had it). */
    public static final int LAVA_LAKE = 12;
    public static final String LAVA_LAKE_KIND = "lava lake";

    public final String dim;
    public final int cx, cz;
    /** When the chunk was last read or patched (ms). */
    public long seenMs;
    /** Loaded from a file (or the chunk left): true until the chunk arrives and is read again. */
    public boolean stale;
    public final Map<String, Group> ores = new TreeMap<>();
    public final Map<String, Group> pois = new TreeMap<>();
    /** Section index (y >> 4) -> {lava source count, x, y, z of one of them}. */
    public final Map<Integer, int[]> lava = new TreeMap<>();
    public final int[] tops = new int[256];

    public ChunkSummary(String dim, int cx, int cz) {
        this.dim = dim;
        this.cx = cx;
        this.cz = cz;
        Arrays.fill(tops, NO_TOP);
    }

    public record Spot(int x, int y, int z, String id) {
        public boolean at(int ox, int oy, int oz) { return x == ox && y == oy && z == oz; }
    }

    /** What a removal found: the spot was listed, counted but not listed (more than the cap), or not there at all. */
    public enum Removed { LISTED, UNLISTED, UNKNOWN }

    /** One family's (or kind's) exact count and up to the cap of its positions. */
    public static final class Group {
        public int n;
        public final List<Spot> spots = new ArrayList<>();

        public void add(Spot s) {
            n++;
            if (spots.size() < Limits.SENSE_SPOTS) spots.add(s);
        }

        public Removed remove(int x, int y, int z) {
            for (int i = 0; i < spots.size(); i++) {
                if (spots.get(i).at(x, y, z)) {
                    spots.remove(i);
                    n--;
                    return Removed.LISTED;
                }
            }
            if (n > spots.size()) {
                n--;
                return Removed.UNLISTED;
            }
            return Removed.UNKNOWN;
        }

        Group copy() {
            Group g = new Group();
            g.n = n;
            g.spots.addAll(spots);
            return g;
        }
    }

    public static int index(int x, int z) { return (z & 15) * 16 + (x & 15); }

    public static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    public static int cx(long k) { return (int) (k >> 32); }

    public static int cz(long k) { return (int) k; }

    public long key() { return key(cx, cz); }

    /** The lava lakes: one spot per section with LAVA_LAKE or more sources. */
    public List<Spot> lavaLakes() {
        List<Spot> out = new ArrayList<>();
        for (int[] v : lava.values()) if (v[0] >= LAVA_LAKE) out.add(new Spot(v[1], v[2], v[3], "minecraft:lava"));
        return out;
    }

    public int orePositions() {
        int n = 0;
        for (Group g : ores.values()) n += g.spots.size();
        return n;
    }

    public ChunkSummary copy() {
        ChunkSummary c = new ChunkSummary(dim, cx, cz);
        c.seenMs = seenMs;
        c.stale = stale;
        for (Map.Entry<String, Group> e : ores.entrySet()) c.ores.put(e.getKey(), e.getValue().copy());
        for (Map.Entry<String, Group> e : pois.entrySet()) c.pois.put(e.getKey(), e.getValue().copy());
        for (Map.Entry<Integer, int[]> e : lava.entrySet()) c.lava.put(e.getKey(), e.getValue().clone());
        System.arraycopy(tops, 0, c.tops, 0, 256);
        return c;
    }
}
