package io.github.mojolowjo.entropybot.guard;

/**
 * AREAS review 1 (0.39.8): outside every area the only built-block protection was "the block's id is not natural", so a
 * cobblestone or dirt hut nobody labelled (and no candidate noted: no named mob, under 2 block entities) was breakable.
 * Now a natural-looking block that sits within reach of a built block or a block entity counts as built too, outside
 * every area: the hut's cobble wall stands next to its planks door, torches, bed or chest. Inside the owner's areas the
 * rule is as before (their own cobble work is theirs to dig). Pure (JUnit: BuiltNearTest); the offsets are the 26
 * neighbours and the two-away cells along the axes (32 lookups), cheap enough for A*'s break candidates.
 */
public final class BuiltNear {
    private BuiltNear() {}

    /** {dx, dy, dz}: the 3x3x3 shell and +-2 along each axis. */
    public static final int[][] OFFSETS;

    static {
        java.util.List<int[]> o = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dy != 0 || dz != 0) o.add(new int[]{dx, dy, dz});
        o.add(new int[]{2, 0, 0}); o.add(new int[]{-2, 0, 0}); o.add(new int[]{0, 2, 0}); o.add(new int[]{0, -2, 0}); o.add(new int[]{0, 0, 2}); o.add(new int[]{0, 0, -2});
        OFFSETS = o.toArray(new int[0][]);
    }

    /** A cell is answered "built" / "block entity" / "neither" / "unknown" by the caller's world. */
    public interface Look {
        /** 1 = a built block or a block entity, 0 = neither (air, natural), -1 = not loaded. */
        int at(int x, int y, int z);
    }

    /**
     * The rule: the block itself is built (as before), or it stands outside every area and a built block or block
     * entity is within the offsets. Ores never count (a vein next to a mineshaft's rails is still an ore).
     */
    public static boolean counts(boolean selfBuilt, boolean ore, boolean outsideEveryArea, Look look, int x, int y, int z) {
        if (selfBuilt) return true;
        if (ore || !outsideEveryArea || look == null) return false;
        for (int[] d : OFFSETS) if (look.at(x + d[0], y + d[1], z + d[2]) == 1) return true;
        return false;
    }
}
