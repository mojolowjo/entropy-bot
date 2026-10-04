package io.github.mojolowjo.entropybot.baritone;

/**
 * Which modded blocks Baritone must not plan to walk through (owner's report 2026-10-03: the bot got stuck against the
 * Mystical Agriculture infusion pedestals). Baritone falls back to {@code isPathfindable(LAND)} for blocks it doesn't
 * know, and a modded block that is solid but slimmer than a full cube (a pedestal: collision 1 high, pathfindable
 * true) then counts as walk-through, so paths run into it. Pure rule; the shape is read by
 * {@code mixin.FarmlandMixinPrecomputedData} once per block state when Baritone fills its table.
 */
public final class ModdedSolids {
    private ModdedSolids() {}

    /** Collision shapes up to this high (carpets, plates, modded farmland edges) stay walk-through. */
    public static final double LOW = 0.2;

    /**
     * True when Baritone's "yes, walk through" must become "no": the block is from a mod, it isn't a door, gate or
     * trapdoor (those open; Baritone handles them), and its collision shape is real and taller than {@link #LOW}.
     */
    public static boolean solid(String namespace, boolean opens, boolean shapeEmpty, double shapeMaxY) {
        if (namespace == null || namespace.equals("minecraft") || opens || shapeEmpty) return false;
        return shapeMaxY > LOW;
    }
}
