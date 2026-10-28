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

    /**
     * 0.39.z78 J6b run EI (the manager's fix): the surface leg's target - of the columns within {@link #RING} of the feet
     * (a square, the feet's own column in it) whose leaf-free surface is known and whose surface cell a player can stand
     * in ({@code standable}), the HIGHEST surface, the nearest of those to the feet; null: none known. A dig of our own
     * (a one-cell notch, the shaft itself) is lower than the rim round it, so it is never the target. Pure.
     */
    public static int[] ringTarget(int[] feet, java.util.function.IntBinaryOperator surface, Column3 standable) {
        int[] best = null;
        long bd = Long.MAX_VALUE;
        for (int dx = -RING; dx <= RING; dx++)
            for (int dz = -RING; dz <= RING; dz++) {
                int x = feet[0] + dx, z = feet[2] + dz, y = surface.applyAsInt(x, z);
                if (!known(y) || !standable.test(x, y, z)) continue;
                long d = (long) dx * dx + (long) (y - feet[1]) * (y - feet[1]) + (long) dz * dz;
                if (best == null || y > best[1] || (y == best[1] && d < bd)) { best = new int[]{x, y, z}; bd = d; }
            }
        return best;
    }

    /**
     * 857599bc review (cost): the surface leg is needed only where a no-break walk cannot go - the ring's highest surface
     * ({@code target}) cannot be reached from the feet's column by steps of at most one block up between neighbouring
     * known columns of the ring (drops are walked). On a slope of 1-block steps the no-break explore walks up itself, so
     * no hops to the ring's local top before it; in a pit (a 2-block rise all round, a notch of our own inside) the leg.
     * Pure.
     */
    public static boolean legNeeded(int[] feet, java.util.function.IntBinaryOperator surface, int[] target) {
        if (feet == null || target == null) return false;
        int side = 2 * RING + 1;
        int[] h = new int[side * side];
        for (int i = 0; i < h.length; i++) h[i] = surface.applyAsInt(feet[0] - RING + i % side, feet[2] - RING + i / side);
        int start = RING * side + RING;
        if (!known(h[start])) h[start] = feet[1];
        boolean[] seen = new boolean[h.length];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        q.add(start);
        seen[start] = true;
        while (!q.isEmpty()) {
            int c = q.poll();
            if (h[c] >= target[1]) return false;
            int cx = c % side, cz = c / side;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = cx + dx, nz = cz + dz, n = nz * side + nx;
                    if ((dx == 0 && dz == 0) || nx < 0 || nz < 0 || nx >= side || nz >= side || seen[n] || !known(h[n])) continue;
                    if (h[n] - h[c] > 1) continue;                                // structural: a 1-block step walks out
                    seen[n] = true;
                    q.add(n);
                }
        }
        return true;
    }

    /**
     * The ring's half-width: 3 columns each way - past the one-cell cuts our own digs leave round the feet (a shaft, the
     * walk's notch: run EI 9 65 3) to the ground they were cut into, and no farther than a player looks for the rim of his
     * own hole (the walk to it stays a few blocks).
     */
    public static final int RING = 3; // structural: one-cell digs round the feet and the rim past them

    /** A cell test (x y z). */
    @FunctionalInterface
    public interface Column3 { boolean test(int x, int y, int z); }

    /** Run EH (rule 23: the ONE "below the surface" answer): the deeper of rock over the head and the hole round the feet. */
    public static int belowSurface(int rockDepth, int holeDepth) { return Math.max(Math.max(0, rockDepth), Math.max(0, holeDepth)); }
}
