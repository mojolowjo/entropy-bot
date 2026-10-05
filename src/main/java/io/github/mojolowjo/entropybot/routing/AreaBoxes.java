package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.SectionKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which boxes the map holds (plan section 2): only boxes that touch one of the owner's areas, inside the world's height,
 * in the dims that are built (stage 1: the overworld, dim 0). Protect boxes do not remove boxes: walks never break or
 * place (plan 4.6), and the builder's context has breaking and placing off.
 *
 * <p>An area is {@code {dim, x1, z1, x2, z2, y1, y2}} (inclusive, the same ints {@code RouteHashes.areas} hashes).
 * Immutable; one instance per policy snapshot, read from any thread.
 */
public final class AreaBoxes {
    private final List<int[]> areas;
    private final int minY, maxY;

    /**
     * @param minY the world's lowest block (overworld -64), {@code maxY} its highest (319).
     */
    public AreaBoxes(List<int[]> areas, int minY, int maxY) {
        List<int[]> copy = new ArrayList<>();
        for (int[] a : areas) {
            if (a.length != 7) throw new IllegalArgumentException("an area is {dim, x1, z1, x2, z2, y1, y2}");
            copy.add(new int[]{a[0], Math.min(a[1], a[3]), Math.min(a[2], a[4]), Math.max(a[1], a[3]),
                    Math.max(a[2], a[4]), Math.min(a[5], a[6]), Math.max(a[5], a[6])});
        }
        this.areas = List.copyOf(copy);
        this.minY = minY;
        this.maxY = maxY;
    }

    public List<int[]> areas() {
        return areas;
    }

    public boolean isEmpty() {
        return areas.isEmpty();
    }

    /** True when the box lies in the world's height and touches an area of its dim. */
    public boolean wanted(SectionKey k) {
        int bx1 = k.minX(), by1 = k.minY(), bz1 = k.minZ();
        int bx2 = bx1 + 15, by2 = by1 + 15, bz2 = bz1 + 15;
        if (by2 < minY || by1 > maxY) return false;
        for (int[] a : areas) {
            if (a[0] != k.dim()) continue;
            if (bx1 <= a[3] && bx2 >= a[1] && bz1 <= a[4] && bz2 >= a[2] && by1 <= a[6] && by2 >= a[5]) return true;
        }
        return false;
    }

    /**
     * The most boxes {@link #all(int)} lists (review S5): a huge area (a typo'd corner, a whole world) would otherwise
     * fill memory and the idle queue. The rest is built as walks and chunk loads ask for it; route status says so.
     */
    public static final int MAX_BOXES = 200_000;

    /** Every wanted box of {@code dim}, area by area (no duplicates), at most {@link #MAX_BOXES}. */
    public List<SectionKey> all(int dim) {
        return all(dim, MAX_BOXES);
    }

    /** Every wanted box of {@code dim}, area by area (no duplicates), at most {@code max}. */
    public List<SectionKey> all(int dim, int max) {
        Set<SectionKey> out = new LinkedHashSet<>();
        for (int[] a : areas) {
            if (a[0] != dim) continue;
            int sy1 = Math.max(a[5], minY) >> 4, sy2 = Math.min(a[6], maxY) >> 4;
            for (int sx = a[1] >> 4; sx <= a[3] >> 4; sx++)
                for (int sz = a[2] >> 4; sz <= a[4] >> 4; sz++)
                    for (int sy = sy1; sy <= sy2; sy++) {
                        if (out.size() >= max) return new ArrayList<>(out);
                        out.add(new SectionKey(dim, sx, sy, sz));
                    }
        }
        return new ArrayList<>(out);
    }

    /** How many boxes the areas of {@code dim} cover, without listing them (overlaps counted twice: an upper bound). */
    public long count(int dim) {
        long n = 0;
        for (int[] a : areas) {
            if (a[0] != dim) continue;
            long sy = Math.max(0, (Math.min(a[6], maxY) >> 4) - (Math.max(a[5], minY) >> 4) + 1);
            n += ((long) (a[3] >> 4) - (a[1] >> 4) + 1) * ((long) (a[4] >> 4) - (a[2] >> 4) + 1) * sy;
        }
        return n;
    }

    /** The wanted boxes of one chunk column (for a chunk load). */
    public List<SectionKey> column(int dim, int cx, int cz) {
        List<SectionKey> out = new ArrayList<>();
        for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
            SectionKey k = new SectionKey(dim, cx, sy, cz);
            if (wanted(k)) out.add(k);
        }
        return out;
    }

    /** Wanted boxes within {@code r} boxes sideways and {@code ry} up or down of the block (x, y, z), nearest first. */
    public List<SectionKey> near(int dim, int x, int y, int z, int r, int ry) {
        List<SectionKey> out = new ArrayList<>();
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        for (int d = 0; d <= Math.max(r, ry); d++) {
            for (int dx = -Math.min(d, r); dx <= Math.min(d, r); dx++)
                for (int dz = -Math.min(d, r); dz <= Math.min(d, r); dz++)
                    for (int dy = -Math.min(d, ry); dy <= Math.min(d, ry); dy++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dz), Math.abs(dy))) != d) continue;
                        SectionKey k = new SectionKey(dim, cx + dx, cy + dy, cz + dz);
                        if (wanted(k)) out.add(k);
                    }
        }
        return out;
    }

    /** The areas as the hash input ({@code RouteHashes.areas}). */
    public List<int[]> hashInput() {
        return areas;
    }
}
