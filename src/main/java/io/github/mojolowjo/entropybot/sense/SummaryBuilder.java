package io.github.mojolowjo.entropybot.sense;

import java.util.List;

/**
 * 0.28.0: a chunk's summary from its sections (the full read) and the per-block patch, over any block-state type: the
 * game side hands in the vanilla sections ({@code McChunkReader}), JUnit a fake chunk. A section the source says to skip
 * (no section, only air, or its palette has nothing wanted: {@code maybeHas}) is not walked. POI rules kept from
 * PoiScanner: rails count as "mineshaft" only below y {@link #MINESHAFT_BELOW}; lava sources are counted per section.
 *
 * <p>Plain Java, JUnit. Loader notes: none. Errors: a source that throws makes {@link #build} throw (the caller leaves
 * the chunk unknown and due again: never a half summary).
 */
public final class SummaryBuilder {
    private SummaryBuilder() {}

    public static final int MINESHAFT_BELOW = 45;
    public static final String MINESHAFT = "mineshaft";

    /**
     * What one block state is to the pass: an ore (family + id), a POI kind, a lava source, any of them, or nothing.
     * {@link #NONE} for the rest.
     */
    public record Cls(String family, String id, String poi, boolean lava) {
        public boolean none() { return family == null && poi == null && !lava; }
    }

    public static final Cls NONE = new Cls(null, null, null, false);

    public interface Classifier<S> {
        Cls of(S state);
    }

    /** One chunk's blocks as the builder reads them. Section i covers y from (minSection + i) * 16. */
    public interface ChunkSource<S> {
        int sections();

        int minSection();

        /** True to skip section i: none, only air, or the palette says nothing wanted is in it. */
        boolean skip(int i);

        S state(int i, int lx, int ly, int lz);

        /** The top block's y of column lx lz (WORLD_SURFACE), or {@link ChunkSummary#NO_TOP}. */
        int top(int lx, int lz);
    }

    public static <S> ChunkSummary build(String dim, int cx, int cz, ChunkSource<S> src, Classifier<S> cls, long now) {
        ChunkSummary s = new ChunkSummary(dim, cx, cz);
        s.seenMs = now;
        int bx = cx << 4, bz = cz << 4;
        for (int i = 0; i < src.sections(); i++) {
            if (src.skip(i)) continue;
            int baseY = (src.minSection() + i) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                Cls c = cls.of(src.state(i, x, y, z));
                if (c == null || c.none()) continue;
                add(s, bx + x, baseY + y, bz + z, c, null);
            }
        }
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) s.tops[ChunkSummary.index(x, z)] = src.top(x, z);
        return s;
    }

    static boolean poiKept(String kind, int y) {
        return kind != null && !(kind.equals(MINESHAFT) && y >= MINESHAFT_BELOW);
    }

    private static void add(ChunkSummary s, int x, int y, int z, Cls c, List<ChunkDiff.Change> out) {
        if (c.family() != null) {
            ChunkSummary.Group g = s.ores.computeIfAbsent(c.family(), k -> new ChunkSummary.Group());
            ChunkSummary.Spot spot = new ChunkSummary.Spot(x, y, z, c.id());
            if (out != null) g.addPatched(spot);          // a patch (placed or uncovered ore): listed even past the cap
            else g.add(spot);
            if (out != null) out.add(new ChunkDiff.Change(ChunkDiff.Type.ORE_ADD, c.family(), c.id(), x, y, z));
        }
        if (poiKept(c.poi(), y)) {
            s.pois.computeIfAbsent(c.poi(), k -> new ChunkSummary.Group()).add(new ChunkSummary.Spot(x, y, z, c.id()));
            if (out != null) out.add(new ChunkDiff.Change(ChunkDiff.Type.POI_ADD, c.poi(), c.id(), x, y, z));
        }
        if (c.lava()) {
            int[] v = s.lava.computeIfAbsent(y >> 4, k -> new int[]{0, x, y, z});
            v[0]++;
            if (out != null && v[0] == ChunkSummary.LAVA_LAKE)
                out.add(new ChunkDiff.Change(ChunkDiff.Type.POI_ADD, ChunkSummary.LAVA_LAKE_KIND, "minecraft:lava", v[1], v[2], v[3]));
        }
    }

    /** False when the summary did not hold what the old state says was there (the patch is unsure: rescan). */
    private static boolean remove(ChunkSummary s, int x, int y, int z, Cls c, List<ChunkDiff.Change> out) {
        boolean ok = true;
        if (c.family() != null) {
            ChunkSummary.Group g = s.ores.get(c.family());
            ChunkSummary.Removed r = g == null ? ChunkSummary.Removed.UNKNOWN : g.remove(x, y, z);
            if (r == ChunkSummary.Removed.UNKNOWN) ok = false;
            else {
                if (g.n <= 0) s.ores.remove(c.family());
                out.add(new ChunkDiff.Change(ChunkDiff.Type.ORE_REMOVE, c.family(), c.id(), x, y, z));
            }
        }
        if (poiKept(c.poi(), y)) {
            ChunkSummary.Group g = s.pois.get(c.poi());
            ChunkSummary.Removed r = g == null ? ChunkSummary.Removed.UNKNOWN : g.remove(x, y, z);
            if (r == ChunkSummary.Removed.UNKNOWN) ok = false;
            else {
                if (g.n <= 0) s.pois.remove(c.poi());
                out.add(new ChunkDiff.Change(ChunkDiff.Type.POI_REMOVE, c.poi(), c.id(), x, y, z));
            }
        }
        if (c.lava()) {
            int[] v = s.lava.get(y >> 4);
            if (v == null || v[0] <= 0) ok = false;
            else {
                if (v[0] == ChunkSummary.LAVA_LAKE)
                    out.add(new ChunkDiff.Change(ChunkDiff.Type.POI_REMOVE, ChunkSummary.LAVA_LAKE_KIND, "minecraft:lava", v[1], v[2], v[3]));
                v[0]--;
                if (v[0] == 0) s.lava.remove(y >> 4);
            }
        }
        return ok;
    }

    /**
     * One block changed from {@code from} to {@code to} (classified; null = NONE); freshTop: the column's top after the
     * change (the live heightmap). The summary is patched and the changes added to out. Returns false when the old state
     * was not where the summary said: the summary is then unsure and the chunk wants a rescan (the "missed" rule).
     */
    public static boolean patch(ChunkSummary s, int x, int y, int z, Cls from, Cls to, int freshTop, List<ChunkDiff.Change> out, long now) {
        boolean ok = true;
        if (from != null && !from.none()) ok = remove(s, x, y, z, from, out);
        if (to != null && !to.none()) add(s, x, y, z, to, out);
        if (ColumnPatch.apply(s.tops, x, z, freshTop)) out.add(new ChunkDiff.Change(ChunkDiff.Type.TOP, null, null, x, freshTop, z));
        s.seenMs = now;
        return ok;
    }
}
