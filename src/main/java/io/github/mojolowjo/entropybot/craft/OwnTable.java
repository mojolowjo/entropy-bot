package io.github.mojolowjo.entropybot.craft;

/**
 * 0.28.2 (trial fix package 2, A): a craft that needs a crafting table (or a smelt that needs a furnace) and has none it
 * can reach puts down the one it carries (or makes a table from its wood first). The craft's own table is always picked
 * up again afterwards (the owner's rule, 2026-10-12); a furnace stays where it went down (it holds the job). The camp's
 * table is never touched: the pickup only ever takes the exact cell the craft itself placed.
 *
 * <p>Pure rules; the wiring is in {@code Crafting.craft} / {@code Crafting.planSteps} and {@code Clearing.ownTableSteps}.
 */
public final class OwnTable {
    private OwnTable() {}

    /** A table this far (eye to block) or nearer is used as it is. */
    public static final double REACH_NEAR = 12;
    /** A table this many blocks or more below the bot's feet sits in a pit (trial 2: "couldn't get close"). */
    public static final int PIT_DEPTH = 3;

    public enum Choice {
        /** a reachable table near: walk there and craft (as before) */
        USE_KNOWN,
        /** put the table it carries down next to itself, craft, take it back */
        PLACE_CARRIED,
        /** craft a table from its wood (4 planks / 1 log) first, then as PLACE_CARRIED */
        CRAFT_AND_PLACE,
        /** no table in the bag and no wood: walk to the far/low table anyway */
        WALK_FAR,
        /** no table anywhere and nothing to make one: the old "needs a crafting table" error */
        NONE
    }

    /**
     * @param dist  eye distance to the table the craft would use (null: none known)
     * @param dy    that table's y minus the bot's feet y (ignored when dist is null)
     * @param carries the bag holds a crafting_table
     * @param wood  the bag holds logs or planks
     */
    public static Choice choose(Double dist, int dy, boolean carries, boolean wood) {
        if (reachable(dist, dy)) return Choice.USE_KNOWN;
        if (carries) return Choice.PLACE_CARRIED;
        if (wood) return Choice.CRAFT_AND_PLACE;
        return dist != null ? Choice.WALK_FAR : Choice.NONE;
    }

    /** Near enough and not down in a pit. */
    public static boolean reachable(Double dist, int dy) {
        return dist != null && dist <= REACH_NEAR && dy > -PIT_DEPTH;
    }

    /** The job's end note once the table was taken back. */
    public static String takenBack(String pos) {
        return "used my own table at " + pos + ", taken back";
    }

    /** The job's end note (and the check finding) when the pickup failed. */
    public static String leftAt(String pos) {
        return "my crafting table is still at " + pos + " - next: dig " + pos + " " + pos;
    }

    /** The started reply's tail when the craft brings its own table. */
    public static String startNote(Choice c) {
        return switch (c) {
            case PLACE_CARRIED -> " (no table in reach: using my own, I take it back after)";
            case CRAFT_AND_PLACE -> " (no table in reach: making one, I take it back after)";
            default -> "";
        };
    }

    /** The smelt's note when it put its own furnace down (the furnace stays: it holds the job). */
    public static String furnacePlaced(String pos) {
        return "put my furnace down at " + pos + " (it stays there)";
    }
}
