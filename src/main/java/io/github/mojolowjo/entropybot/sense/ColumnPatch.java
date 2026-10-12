package io.github.mojolowjo.entropybot.sense;

/**
 * 0.28.0: the column-top rule of a block patch. The top (WORLD_SURFACE: the highest non-air block) only moves when the
 * change is at or above it: a block placed above raises it to that y, the top block removed lowers it to what the live
 * heightmap says now (the game updates its heightmap in the same set call). A change below the top leaves it alone.
 * Plain Java, JUnit. Loader notes: none.
 */
public final class ColumnPatch {
    private ColumnPatch() {}

    /** Matters to the map/surface readers: a change at or above (top - 1) of the column. */
    public static boolean nearTop(int[] tops, int x, int y, int z) {
        int t = tops[ChunkSummary.index(x, z)];
        return t == ChunkSummary.NO_TOP || y >= t - 1;
    }

    /** Sets column x z's top to freshTop; true when it moved. Only that column is touched. */
    public static boolean apply(int[] tops, int x, int z, int freshTop) {
        int i = ChunkSummary.index(x, z);
        if (tops[i] == freshTop) return false;
        tops[i] = freshTop;
        return true;
    }

    /**
     * The new top without a live heightmap (a fake column for tests, or a guess): a solid block placed at y above the top
     * makes y the top; the top block removed walks down with {@code solidAt} to the next block (NO_TOP below minY).
     */
    public static int next(int top, int y, boolean nowSolid, java.util.function.IntPredicate solidAt, int minY) {
        if (nowSolid) return top == ChunkSummary.NO_TOP || y > top ? y : top;
        if (y != top) return top;
        for (int yy = y - 1; yy >= minY; yy--) if (solidAt.test(yy)) return yy;
        return ChunkSummary.NO_TOP;
    }
}
