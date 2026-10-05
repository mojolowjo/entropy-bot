package io.github.mojolowjo.entropybot.routing;

/**
 * The adapter's small decisions, as plain Java so JUnit can test them (the Minecraft side only computes the inputs).
 */
public final class RouteRules {
    private RouteRules() {
    }

    // ---- walkability classes of a block state (computed by RouteRuntime.walkClass) ----

    /** No collision shape and no fluid: air, grass, torches, open trapdoor spaces... */
    public static final int PASSABLE = 0;
    /** A fluid source (water or lava, also waterlogged blocks). */
    public static final int LIQUID_SOURCE = 1;
    /** A flowing fluid. */
    public static final int LIQUID_FLOW = 2;
    /** A full cube collision shape. */
    public static final int SOLID = 3;
    /** A partial shape: slabs, stairs, fences, doors, ladders, carpets... (any state change counts). */
    public static final int PARTIAL = 4;
    /** Baritone's {@code avoidWalkingInto}: fire, cactus, magma, berry bushes, cobwebs... */
    public static final int AVOID = 5;

    /**
     * Review R7 / plan section 3: only a change of walkability marks a box stale. A different class always counts; a
     * state change inside {@link #PARTIAL} counts too (a slab bottom to top, a door opening, a ladder turning). Crop
     * ages, furnace lights, redstone power, water levels and fire ages do not.
     */
    public static boolean walkChanged(int oldClass, int newClass, boolean sameState) {
        if (oldClass != newClass) return true;
        return oldClass == PARTIAL && !sameState;
    }

    /**
     * The contract's standable test ({@code CellMoves#standable}): something to stand on below (Baritone's
     * {@code canWalkOn}), or water or a ladder/vine at the feet, and room for the body (Baritone's {@code canWalkThrough}
     * at the feet and the head).
     */
    public static boolean standable(boolean walkOnBelow, boolean waterAtFeet, boolean climbableAtFeet,
                                    boolean throughFeet, boolean throughHead) {
        if (!throughFeet || !throughHead) return false;
        return walkOnBelow || waterAtFeet || climbableAtFeet;
    }

    /** A move Baritone reported as possible: finite, positive, not NaN, and going somewhere. */
    public static boolean usableMove(double cost, double costInf, int fx, int fy, int fz, int tx, int ty, int tz) {
        if (!(cost > 0) || !(cost < costInf)) return false;
        return fx != tx || fy != ty || fz != tz;
    }

    /**
     * Which terrain a box was built from: live when its chunk column and the 8 around it (moves read up to a block or
     * two past the box's margin) are loaded, coarse when only Baritone's cache knows the box's own column, else none
     * (null: not built, nothing to build from).
     *
     * @param loaded3x3 loaded flags of the columns sx-1..sx+1 x sz-1..sz+1, row-major, centre at index 4.
     */
    public static io.github.mojolowjo.entropybot.route.SectionRecord.Quality quality(boolean[] loaded3x3, boolean centreCached) {
        boolean all = true;
        for (boolean b : loaded3x3) all &= b;
        if (all) return io.github.mojolowjo.entropybot.route.SectionRecord.Quality.LIVE;
        if (loaded3x3[4] || centreCached) return io.github.mojolowjo.entropybot.route.SectionRecord.Quality.COARSE;
        return null;
    }
}
