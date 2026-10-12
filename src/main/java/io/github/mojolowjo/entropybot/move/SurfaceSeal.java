package io.github.mojolowjo.entropybot.move;

import io.github.mojolowjo.entropybot.camp.ShelterPlan;

import java.util.Set;
import java.util.function.Predicate;

/**
 * 0.27.2: the sealed start on the surface (live, run1: a shelter of its own planks with oak leaves around it after a
 * restart; Baritone never breaks, so every walk found 0 movements for an hour). Pure rules, JUnit-tested: what a
 * surface dig-out may break (natural leaves, plants, snow layers, the bot's own shelter blocks; never other built
 * blocks, containers, or blocks next to water/lava), the sealed test over a grid, and the shelter recognised from
 * stored cells after a restart. Loader notes: none (the game side is commands/Jobs.startEscape and ShelterJob).
 */
public final class SurfaceSeal {
    private SurfaceSeal() {}

    /** What one cell is for the surface dig-out. */
    public enum Kind { LEAVES, PLANT, OWN, NO }

    /**
     * id: the block id; leaves: in the leaves tag; plantLike: replaceable vegetation (grass, ferns, vines, flowers) or a
     * snow layer; own: in the bot's restore ledger / shelterBlocks; blockEntity: a container or machine; liquidNext: water
     * or lava touches it; persistentLeaves: leaves a player placed (the PERSISTENT state).
     */
    public static Kind kind(String id, boolean leaves, boolean persistentLeaves, boolean plantLike, boolean own, boolean blockEntity, boolean liquidNext) {
        if (id == null || blockEntity || liquidNext) return Kind.NO;
        if (own) return Kind.OWN;
        if (leaves) return persistentLeaves ? Kind.NO : Kind.LEAVES;
        if (plantLike || plantId(id)) return Kind.PLANT;
        return Kind.NO;
    }

    /** Plants, grass, vines and snow layers by id (the tag check's fallback). */
    public static boolean plantId(String id) {
        String p = id.substring(id.indexOf(':') + 1);
        return p.equals("short_grass") || p.equals("grass") || p.equals("tall_grass") || p.equals("fern") || p.equals("large_fern")
                || p.equals("vine") || p.endsWith("_vines") || p.equals("snow") || p.equals("dead_bush") || p.equals("sweet_berry_bush")
                || p.endsWith("_sapling") || p.equals("glow_lichen");
    }

    /** A grid for the sealed test: open = nothing to bump into, floor = a full block to stand on. */
    public interface Grid {
        boolean open(int x, int y, int z);

        boolean floor(int x, int y, int z);
    }

    /** No free step next to me (the 8 around at -1..+1 levels: feet and head open, a floor under): sealed. */
    public static boolean sealed(Grid g, int[] me) {
        for (int dy = 1; dy >= -1; dy--)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int x = me[0] + dx, y = me[1] + dy, z = me[2] + dz;
                    if (dx != 0 && dz != 0 && !diagonalPass(g::open, me, dx, dy, dz)) continue;      // 0.27.3: corners need a side open
                    if (g.open(x, y, z) && g.open(x, y + 1, z) && g.floor(x, y - 1, z)) return false;
                }
        return true;
    }

    /** A cell test (open: nothing to bump into). */
    public interface Open {
        boolean open(int x, int y, int z);
    }

    /**
     * 0.27.3 (d1): a diagonal step (dx, dz both set) from me to dy: the body passes only when one of the two side cells
     * (me+dx or me+dz) is open at the bot's feet and head, plus one more when it steps up (y..max(y, y+dy)+1).
     */
    public static boolean diagonalPass(Open g, int[] me, int dx, int dy, int dz) {
        int top = Math.max(me[1], me[1] + dy) + 1, bot = me[1];
        int[][] sides = {{me[0] + dx, me[2]}, {me[0], me[2] + dz}};
        for (int[] s : sides) {
            boolean ok = true;
            for (int y = bot; y <= top && ok; y++) ok = g.open(s[0], y, s[1]);
            if (ok) return true;
        }
        return false;
    }

    /**
     * The bot stands in a shelter of its own: every shell cell around feet is closed now, and one side's feet and head
     * cells are its own blocks (a way out it may open). own: the stored cells ("x y z").
     */
    public static boolean inOwnShelter(int[] feet, Set<String> own, Predicate<int[]> closed) {
        if (feet == null || own == null || own.isEmpty()) return false;
        for (int[] c : ShelterPlan.allCells(feet)) if (!closed.test(c)) return false;
        return ShelterPlan.exit(feet, own) != null;
    }

    /**
     * 0.27.3 (d3): as above, but a closed shell with at least one own cell counts (an underground pit closed by a roof and
     * one wall of mine); mayDig (natural cells the dig rules allow there) only matters for the way out, not for this test.
     */
    public static boolean inOwnShelterLoose(int[] feet, Set<String> own, Predicate<int[]> closed) {
        if (feet == null || own == null || own.isEmpty()) return false;
        for (int[] c : ShelterPlan.allCells(feet)) if (!closed.test(c)) return false;
        return ShelterPlan.anyOwn(feet, own);
    }

    /**
     * 0.27.3 fix: the escape from inside my own shelter, checked before the underground/surface split (its own roof makes
     * the spot look underground): {feet cell to step to, breaks (head first, own and closed only)}, or null when not in one.
     */
    public static java.util.List<int[]> ownExit(int[] feet, Set<String> own, Predicate<int[]> closed) {
        return ownExit(feet, own, closed, null);
    }

    /**
     * 0.27.4 (live: a pit with my roof and one wall, the rest natural stone): as above, but a closed shell with at least one
     * own cell counts (inOwnShelterLoose), and a side may be one own cell plus one natural cell mayDig allows (the
     * underground pit's head cell). The natural cell is broken too (head first, then the step cell). mayDig null: own only.
     */
    public static java.util.List<int[]> ownExit(int[] feet, Set<String> own, Predicate<int[]> closed, Predicate<int[]> mayDig) {
        if (feet == null || own == null || own.isEmpty()) return null;
        for (int[] c : ShelterPlan.allCells(feet)) if (!closed.test(c)) return null;
        if (!ShelterPlan.anyOwn(feet, own)) return null;
        int[][] ex = ShelterPlan.exit(feet, own, mayDig);
        if (ex == null) return null;
        java.util.List<int[]> out = new java.util.ArrayList<>();
        out.add(ex[0]);
        for (int[] c : new int[][]{ex[1], ex[0]})
            if (closed.test(c) && (own.contains(ShelterPlan.key(c)) || (mayDig != null && mayDig.test(c)))) out.add(c);
        return out;
    }

    /** How many own cells of the shell around feet are there (for the status line). */
    public static int ownCount(int[] feet, Set<String> own) {
        int n = 0;
        for (int[] c : ShelterPlan.allCells(feet)) if (own.contains(ShelterPlan.key(c))) n++;
        return n;
    }

    /** The check finding: more than 3 surface dig-outs in the last hour. */
    public static boolean tooOften(long[] times, long nowMs) {
        int n = 0;
        for (long t : times) if (t > 0 && nowMs - t <= 3_600_000L) n++;
        return n > 3;
    }

    /** "dig-out (surface): N blocks (leaves K, own blocks M)". */
    public static String statusLine(int leaves, int plants, int own) {
        return "dig-out (surface): " + (leaves + plants + own) + " blocks (leaves " + leaves + ", own blocks " + own
                + (plants > 0 ? ", plants " + plants : "") + ")";
    }
}
