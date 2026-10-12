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
                    if (g.open(x, y, z) && g.open(x, y + 1, z) && g.floor(x, y - 1, z)) return false;
                }
        return true;
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
