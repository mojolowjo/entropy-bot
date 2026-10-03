package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The strip mine's ore step (the bridge's findVeins and holeFills): the ores a dug branch exposed and the veins
 * they belong to, and the cobblestone fills for the holes mined below the walkway.
 */
public final class Veins {
    private Veins() {}

    /** At most this many blocks per vein, and at most this far from the dug boxes. */
    public static final int MAX_VEIN = 24, MAX_GAP = 4;

    /** Where ores may be mined: the owner's areas (the bridge's inMapArea, x and z only). */
    @FunctionalInterface
    public interface Area {
        boolean contains(int x, int z);
    }

    /** boxGap: cells between x y z and the nearest of the boxes (0 = inside one). */
    public static int boxGap(List<ClearBox> boxes, int x, int y, int z) {
        int best = 999;
        for (ClearBox b : boxes) {
            int g = Math.max(Math.max(Math.max(b.x1() - x, 0), Math.max(x - b.x2(), b.y1() - y)),
                    Math.max(Math.max(0, y - b.y2()), Math.max(b.z1() - z, Math.max(0, z - b.z2()))));
            if (g < best) best = g;
        }
        return best;
    }

    /**
     * findVeins: the ores a dug branch exposed (something you can walk or see through next to them: air, or a
     * torch on the tunnel wall) and the ore blocks joined to them face to face: the cells for an "only" clear. A
     * vein is walked whole (so its other exposed blocks don't start a second one) but only its 24 blocks nearest to
     * where it was found are kept, none more than 4 from the boxes; everything inside the area. An ore with sand
     * or gravel right above it is left alone (it would fall into the tunnel).
     *
     * @param wanted the ore list (B4, parseOres): true for a block id to mine; null = every ore
     */
    public static List<Pos> findVeins(ClearWorld w, List<ClearBox> boxes, Predicate<String> wanted, Area area) {
        Set<String> seen = new HashSet<>();
        List<Pos> out = new ArrayList<>();
        for (ClearBox b : boxes) {
            for (int x = b.x1() - 1; x <= b.x2() + 1; x++) {
                for (int y = b.y1() - 1; y <= b.y2() + 1; y++) {
                    for (int z = b.z1() - 1; z <= b.z2() + 1; z++) {
                        String key = Pos.key(x, y, z);
                        if (seen.contains(key) || !area.contains(x, z)) continue;
                        if (!veinBlock(w, x, y, z, wanted)) continue;
                        boolean exposed = false;
                        for (int n = 0; n < 6 && !exposed; n++) {
                            int[] s = ClearEngine.SIDES6[n];
                            exposed = w.noCollision(x + s[0], y + s[1], z + s[2]);
                        }
                        if (!exposed) continue;
                        List<Pos> vein = new ArrayList<>();
                        ArrayDeque<Pos> queue = new ArrayDeque<>();
                        queue.add(new Pos(x, y, z));
                        seen.add(key);
                        while (!queue.isEmpty()) {
                            Pos cur = queue.poll();
                            if (vein.size() < MAX_VEIN) vein.add(cur);
                            for (int[] s : ClearEngine.SIDES6) {
                                int nx = cur.x() + s[0], ny = cur.y() + s[1], nz = cur.z() + s[2];
                                String nk = Pos.key(nx, ny, nz);
                                if (seen.contains(nk) || boxGap(boxes, nx, ny, nz) > MAX_GAP || !area.contains(nx, nz)) continue;
                                if (!veinBlock(w, nx, ny, nz, wanted)) continue;
                                seen.add(nk);
                                queue.add(new Pos(nx, ny, nz));
                            }
                        }
                        out.addAll(vein);
                    }
                }
            }
        }
        return out;
    }

    private static boolean veinBlock(ClearWorld w, int x, int y, int z, Predicate<String> wanted) {
        if (w.air(x, y, z) || !w.ore(x, y, z)) return false;
        if (wanted != null && !wanted.test(w.id(x, y, z))) return false;
        if (!ClearEngine.clearableState(w, x, y, z, false)) return false;
        return !ClearRules.falling(w.name(x, y + 1, z));
    }

    /** A hole fill: place {@code item} at pos, standing at {@code from} (null: from wherever it is); optional. */
    public record Fill(Pos pos, Pos from, String item, boolean optional) {}

    public static final String FILL_ITEM = "minecraft:cobblestone";

    /**
     * holeFills: after a vein clear, seq "place" steps that fill the cells it mined below the walkway with
     * cobblestone, deepest first. A hole in the floor traps the bot (it can't climb 2 blocks with placing off) and
     * anyone walking the tunnel later; a drop lying in a hole gets pushed back up as it's filled. Each fill is placed
     * from a walkway cell beside the hole (not one over another hole); a cell that wasn't mined makes its step fail
     * quietly (optional).
     */
    public static List<Fill> holeFills(List<ClearBox> boxes, List<Pos> veins, int feetY) {
        Set<String> holes = new HashSet<>();
        List<Pos> cells = new ArrayList<>();
        for (Pos v : veins) {
            if (v.y() >= feetY) continue;
            cells.add(v);
            holes.add(v.key());
        }
        cells.sort(Comparator.comparingInt(Pos::y));
        int[][] sides = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        List<Fill> out = new ArrayList<>();
        for (Pos c : cells) {
            Pos f = null;
            for (int n = 0; n < sides.length && f == null; n++) {
                int fx = c.x() + sides[n][0], fz = c.z() + sides[n][1];
                if (onWalk(boxes, feetY, fx, fz) && !holes.contains(Pos.key(fx, feetY - 1, fz))) f = new Pos(fx, feetY, fz);
            }
            out.add(new Fill(c, f, FILL_ITEM, true));
        }
        return out;
    }

    private static boolean onWalk(List<ClearBox> boxes, int feetY, int x, int z) {
        for (ClearBox b : boxes) {
            if (b.y1() == feetY && x >= b.x1() && x <= b.x2() && z >= b.z1() && z <= b.z2()) return true;
        }
        return false;
    }

    /** The oredig step's clear options (startClear in the bridge's "oredig" seq step). */
    public static ClearJob.Options oreDigOptions(List<Pos> veins, int feetY, List<Pos> dump, int branch) {
        return new ClearJob.Options().only(veins).collect(true).soft(true).dump(dump).minStandY(feetY)
                .label("mining " + veins.size() + " ore blocks next to branch " + branch);
    }

    /** The walkway's feet level for a branch's boxes (the lowest y1). */
    public static int feetY(List<ClearBox> boxes) {
        int y = Integer.MAX_VALUE;
        for (ClearBox b : boxes) y = Math.min(y, b.y1());
        return y;
    }
}
