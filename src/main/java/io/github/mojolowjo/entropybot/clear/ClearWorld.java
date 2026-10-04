package io.github.mojolowjo.entropybot.clear;

/**
 * What the clear engine needs to know about the world, one block at a time. No Minecraft types, so JUnit can
 * drive the engine with a made-up world; {@link McClearWorld} is the adapter over the client's level.
 */
public interface ClearWorld {
    /** The block's registry id ("minecraft:stone", "oritech:nickel_ore"). */
    String id(int x, int y, int z);

    /** The bridge's blockName: "stone", "oritech:nickel_ore" (no "minecraft:"). Messages and the name patterns use it. */
    default String name(int x, int y, int z) {
        return ClearRules.blockName(id(x, y, z));
    }

    boolean air(int x, int y, int z);

    /** Any fluid in the cell: water, lava, a waterlogged block. */
    boolean fluid(int x, int y, int z);

    /**
     * Water plan (2026-10-04): which fluid fills the cell: "water", "lava" (or another fluid's id), null when none. The
     * default goes by the block name; {@link McClearWorld} reads the fluid state.
     */
    default String fluidKind(int x, int y, int z) {
        if (!fluid(x, y, z)) return null;
        return name(x, y, z).contains("lava") ? "lava" : "water";
    }

    /**
     * Water plan: a cell's fluid state. source: a source block; amount: the game's FluidState.getAmount() (8 for a source
     * or falling fluid, 7..1 for flowing fluid farther and farther from its source); falling: fed from above.
     */
    record FluidCell(boolean source, int amount, boolean falling) {
        public static final FluidCell SOURCE = new FluidCell(true, 8, false);

        /** How strongly it feeds its neighbours sideways: 8 for a source or a falling column, else the amount. */
        public int level() {
            return source || falling ? 8 : amount;
        }
    }

    /** The fluid state at x y z, null when no fluid. The default (no states known) calls every fluid cell a source. */
    default FluidCell fluidCell(int x, int y, int z) {
        return fluid(x, y, z) ? FluidCell.SOURCE : null;
    }

    /**
     * A block placed into the cell replaces what is there (air, a fluid block, grass...): the game's canBeReplaced. The
     * default: air, or nothing that collides and no block entity.
     */
    default boolean replaceable(int x, int y, int z) {
        return air(x, y, z) || (noCollision(x, y, z) && !blockEntity(x, y, z));
    }

    /** Chests, barrels, beds, signs, machines... */
    boolean blockEntity(int x, int y, int z);

    /** Destroy speed below 0 (bedrock, barriers). */
    boolean unbreakable(int x, int y, int z);

    /** On the protected list (the guard's floor: block entities and building blocks that don't occur in the wild). */
    boolean builtBlock(int x, int y, int z);

    /** On Baritone's blocksToAvoidBreaking (the clear leaves those too). */
    boolean avoided(int x, int y, int z);

    /** In the c:ores tag. */
    boolean ore(int x, int y, int z);

    /** The collision shape is empty (air, grass, torches, fluids, crops). */
    boolean noCollision(int x, int y, int z);

    /** The collision shape is a full cube (a face against it can't be seen). */
    boolean fullBlock(int x, int y, int z);

    /** The top of the collision shape (0 when empty, 1 for a full block, 0.0625 for a carpet). */
    double collisionHeight(int x, int y, int z);

    /** Block light (0 = monsters may spawn). */
    int blockLight(int x, int y, int z);

    /**
     * The first block whose outline a ray from (fx, fy, fz) to (tx, ty, tz) hits, fluids ignored (the game's
     * level.clip with OUTLINE / Fluid.NONE), or null when the ray hits nothing.
     */
    Hit clip(double fx, double fy, double fz, double tx, double ty, double tz);

    /** Block faces, in the game's Direction order. */
    enum Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }

    record Hit(int x, int y, int z, Face face) {}
}
