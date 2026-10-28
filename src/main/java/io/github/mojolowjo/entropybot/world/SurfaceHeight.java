package io.github.mojolowjo.entropybot.world;

/**
 * 0.39.z76 surface (runs AK, AL, AO: every walk-up target "surface at x -64 z: unreachable"; SightingsRuntime's roof and
 * cave-side checks read the same -64): the one answer to "how high is the surface over this column, leaves skipped".
 * The client keeps only the MOTION_BLOCKING and WORLD_SURFACE heightmaps of a chunk (the server sends those two);
 * MOTION_BLOCKING_NO_LEAVES is never filled there and reads the world's bottom. So the leaf-free top is walked down from
 * MOTION_BLOCKING past leaves and cells that do not block motion. Rule 15: a column not loaded, or one whose heightmap
 * holds nothing (it reads the bottom of the world), is {@link #NOT_KNOWN} - never a height of -64. Pure (JUnit:
 * Z76SurfaceTest). Loader notes: the game side ({@code commands.Jobs.surfaceNoLeaves}) reads vanilla
 * {@code Level#getHeight(MOTION_BLOCKING)}, {@code BlockTags.LEAVES}, {@code BlockState#blocksMotion}; Fabric: the same
 * heightmaps under Yarn names.
 */
public final class SurfaceHeight {
    private SurfaceHeight() {}

    /** Not known (the column not loaded, or no heightmap data in the client's chunk). */
    public static final int NOT_KNOWN = Integer.MIN_VALUE;

    /** One cell of the column, as the walk down sees it: true = skip it (leaves, or it does not block motion). */
    public interface Column { boolean skip(int y); }

    /**
     * The y of the first cell over the column's top non-leaf block. {@code loaded}: the column's chunk is loaded;
     * {@code motionBlocking}: the client's MOTION_BLOCKING height there (the first cell over its top blocking block);
     * {@code minY}: the level's minimum build height. A heightmap at or under {@code minY} holds nothing: not known.
     */
    public static int noLeaves(boolean loaded, int motionBlocking, int minY, Column col) {
        if (!loaded || motionBlocking <= minY) return NOT_KNOWN;
        int y = motionBlocking - 1;
        while (y > minY && col.skip(y)) y--;
        return y + 1;
    }

    public static boolean known(int surfaceY) { return surfaceY != NOT_KNOWN; }

    /**
     * 0.39.z78 J6b run EH (the owner's ruling; 00:40:19 the bot at the bottom of its own 1x1 shaft, 5 deep, OPEN to the sky,
     * counted "on the surface" by the rock-over-the-head test): how far the feet stand below the ground round them - the
     * LOWEST known surface of the 8 neighbouring columns ({@code neighbours}, {@link #NOT_KNOWN} left out) less the feet's
     * y, when that is more than a step (a player walks out over a 1-block step; 2 or more on every side walls him in
     * without breaking or placing: no stair, no pillar). 0: on the ground, a step or less below it, or no neighbour known
     * (rule 15: no depth claimed). Pure (JUnit: J6bEhTest).
     */
    public static int holeDepth(int feetY, int[] neighbours) {
        int lowest = Integer.MAX_VALUE;
        for (int s : neighbours) if (known(s)) lowest = Math.min(lowest, s);
        if (lowest == Integer.MAX_VALUE) return 0;
        int d = lowest - feetY;
        return d > 1 ? d : 0;                                                     // structural: a 1-block step walks out
    }

    /** Run EH (rule 23: the ONE "below the surface" answer): the deeper of rock over the head and the hole round the feet. */
    public static int belowSurface(int rockDepth, int holeDepth) { return Math.max(Math.max(0, rockDepth), Math.max(0, holeDepth)); }
}
