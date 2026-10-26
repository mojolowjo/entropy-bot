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
}
