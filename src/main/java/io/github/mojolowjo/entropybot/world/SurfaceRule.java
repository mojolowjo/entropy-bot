package io.github.mojolowjo.entropybot.world;

/**
 * 0.28.1 (trial fix A, docs/TRIAL_FIX_PLAN.md): the ground under a spot. In trial 1 the bot stood on a spruce top, and
 * bootstrap saved the camp, the base and the table spot 8 blocks up in the air after it chopped the tree. This rule
 * walks a column down from a start height to the highest cell a player can stand on, skipping leaves, snow layers,
 * plants and (when the start is in a tree: the cell under it is a log or leaves) logs. Pure (JUnit: {@code TrialFixTest}).
 *
 * <p>Loader notes: none. The game side ({@code commands.Jobs.groundUnder}) classifies cells with vanilla
 * {@code BlockState}/{@code BlockTags}; a Fabric port reuses this class unchanged.
 */
public final class SurfaceRule {
    private SurfaceRule() {}

    /** One cell, as the rule sees it. FLUID stops the walk (the feet go on top of it). */
    public enum Cell { AIR, LEAVES, LOG, SNOW_LAYER, PLANT, SOLID, FLUID }

    /** A column of cells at fixed x z. */
    public interface Column { Cell at(int y); }

    /** How far down it looks at most. */
    public static final int MAX_DROP = 64;

    /**
     * The feet height of the ground at or below {@code y} (the cell over the highest SOLID or FLUID cell under the
     * skipped ones), or {@code y} itself when nothing is found within {@link #MAX_DROP} or above minY.
     */
    public static int groundFeet(Column c, int y, int minY) {
        // in a tree: on (or in) leaves, or on a trunk (two logs stacked); one log under the feet is a floor (a cabin)
        Cell under = c.at(y - 1);
        boolean inTree = under == Cell.LEAVES || c.at(y) == Cell.LEAVES || c.at(y) == Cell.LOG
                || (under == Cell.LOG && c.at(y - 2) == Cell.LOG);
        int lo = Math.max(minY, y - MAX_DROP);
        for (int k = y - 1; k >= lo; k--) {
            Cell s = c.at(k);
            if (s == Cell.SOLID || s == Cell.FLUID) return k + 1;
            if (s == Cell.LOG && !inTree) return k + 1;
        }
        return y;
    }

    /** The target cell and the one under it are open (AIR/plant/snow layer/leaves don't hold a walker): a mark in the air. */
    public static boolean inAir(Column c, int y) {
        Cell feet = c.at(y), below = c.at(y - 1);
        return feet != Cell.SOLID && (below == Cell.AIR || below == Cell.PLANT);
    }

    /** The reply note for a walk sent to the ground instead: "the mark was in the air, walking to the ground at x y z". */
    public static String airNote(int x, int y, int z) {
        return "the mark was in the air, walking to the ground at " + x + " " + y + " " + z;
    }
}
