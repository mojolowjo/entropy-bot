package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 0.36.a5: merges the mod's ore index hits with Baritone's live scan hits (loaded chunks) - pure, unit-tested.
 * Deduped by position (the index entry wins), nearest first, at most n. Counts say where the list came from.
 */
public final class OreMerge {
    private OreMerge() {}

    /** hits: the merged list; fromIndex/liveOnly: counts before the cut; liveKeys: positions the live scan also saw. */
    public record Merged(List<OreIndex.Hit> hits, int fromIndex, int liveOnly, Set<Long> liveKeys) {
        /** "index", "live scan" or "index+live" for a chosen spot. */
        public String sourceOf(int x, int y, int z, Set<Long> indexKeys) {
            long k = key(x, y, z);
            boolean live = liveKeys.contains(k), idx = indexKeys.contains(k);
            return live && idx ? "index+live" : live ? "live scan" : "index";
        }
    }

    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static Merged merge(List<OreIndex.Hit> index, List<OreIndex.Hit> live, int n) {
        List<OreIndex.Hit> all = new ArrayList<>(index);
        Set<Long> seen = new HashSet<>();
        for (OreIndex.Hit h : index) seen.add(key(h.x(), h.y(), h.z()));
        Set<Long> liveKeys = new HashSet<>();
        int liveOnly = 0;
        for (OreIndex.Hit h : live) {
            long k = key(h.x(), h.y(), h.z());
            liveKeys.add(k);
            if (seen.add(k)) {
                all.add(h);
                liveOnly++;
            }
        }
        all.sort((a, b) -> Long.compare(a.d2(), b.d2()));
        List<OreIndex.Hit> cut = all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
        return new Merged(cut, index.size(), liveOnly, liveKeys);
    }
}
