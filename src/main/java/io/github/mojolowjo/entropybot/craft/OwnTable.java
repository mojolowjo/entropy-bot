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

    /**
     * Look-ahead at the craft's own table pickup: keep the table down when the very next chain step is a craft that
     * needs a table (back-to-back tool crafts), so it is placed once and taken back after the last craft.
     * @param craftFailed the craft just failed (the table always comes back then)
     * @param nextStep    the chain's next step text (null: none)
     * @param needsTable  whether a craft step text wants a 3x3 table
     */
    public static boolean keepForNext(boolean craftFailed, String nextStep, java.util.function.Predicate<String> needsTable) {
        if (craftFailed || nextStep == null) return false;
        String t = nextStep.trim().toLowerCase(java.util.Locale.ROOT);
        return t.startsWith("craft ") && needsTable.test(nextStep.trim());
    }

    // ---- aa-costs (item 7; was: always taken back unless the next step crafts) ----

    public enum End { KEEP_FOR_NEXT, LEAVE_AT_CAMP, TAKE }

    /** Seconds: pick it up, place it again, remake one (planks / no planks), a stray block left in the world, a table at a camp without one. */
    public static final double PICK_S = 4, PLACE_S = 2, REMAKE_S = 8, REMAKE_NONE_S = 60, LITTER_S = 10, CAMP_VALUE_S = 15;

    /** Taking it back: the pickup, plus placing it again when the next step crafts at a table. */
    public static double takeCost(boolean nextNeedsTable) { return PICK_S + (nextNeedsTable ? PLACE_S : 0); }

    /**
     * Leaving it: nothing now when the next step uses it (it is taken after that); else the table lost (remade later)
     * and a stray block, less its value as the camp's table (near the camp or base, none there yet).
     */
    public static double leaveCost(boolean nextNeedsTable, boolean nearCamp, boolean campHasTable, boolean canRemake) {
        if (nextNeedsTable) return 0;
        return (canRemake ? REMAKE_S : REMAKE_NONE_S) + LITTER_S - (nearCamp && !campHasTable ? CAMP_VALUE_S : 0);
    }

    /** The cheaper end; a failed craft always takes it back (the chain stops, nothing uses it). */
    public static End decide(boolean craftFailed, boolean nextNeedsTable, boolean nearCamp, boolean campHasTable, boolean canRemake) {
        if (craftFailed) return End.TAKE;
        double take = takeCost(nextNeedsTable), leave = leaveCost(nextNeedsTable, nearCamp, campHasTable, canRemake);
        if (leave >= take) return End.TAKE;
        return nextNeedsTable ? End.KEEP_FOR_NEXT : End.LEAVE_AT_CAMP;
    }

    /** A table left at the camp is registered as the camp's (AA's tableAtCamp latch), so no second one is crafted. */
    public static boolean registersAsCamp(End e) { return e == End.LEAVE_AT_CAMP; }

    private static volatile boolean campTableLeft;

    /** Clearing: the table was left as the camp's. */
    public static void noteCampTable() { campTableLeft = true; }

    /** AA's fill: true once after a table was left at the camp (feeds the tableAtCamp latch). */
    public static boolean takeCampTable() {
        boolean v = campTableLeft;
        campTableLeft = false;
        return v;
    }

    public static String terms(boolean craftFailed, boolean nextNeedsTable, boolean nearCamp, boolean campHasTable, boolean canRemake) {
        return "take " + Math.round(takeCost(nextNeedsTable)) + " s vs leave " + Math.round(leaveCost(nextNeedsTable, nearCamp, campHasTable, canRemake)) + " s"
                + (nextNeedsTable ? " (next step crafts)" : "") + (nearCamp ? campHasTable ? " (camp has a table)" : " (camp without a table)" : "")
                + (craftFailed ? " (craft failed)" : "") + " -> " + decide(craftFailed, nextNeedsTable, nearCamp, campHasTable, canRemake).name().toLowerCase(java.util.Locale.ROOT);
    }

    /** A kept table is taken back as soon as nothing runs (the chain stopped or was preempted), or by the next craft that does not use it. */
    public static boolean reapKept(boolean tableKept, boolean busy) {
        return tableKept && !busy;
    }

    /** Log line when the pickup is skipped. */
    public static final String KEPT_LOG = "table kept for the next craft";
    /** Log line when no further craft needs the table. */
    public static final String PICKED_LOG = "table picked up: no more crafts";

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

    /** "smelt ... here": the furnace it put down goes back in the bag after the smelt (the camp's own never does). */
    public static String furnacePlacedHere(String pos) {
        return "put my furnace down at " + pos + " (I take it back after the smelt)";
    }

    public static String furnaceTakenBack(String pos) {
        return "smelted in my own furnace at " + pos + ", taken back";
    }

    public static String furnaceLeftAt(String pos) {
        return "my furnace is still at " + pos + " - next: dig " + pos + " " + pos + " force";
    }

    /** The note when the furnace could not be emptied, so it stays (breaking it would throw its items out). */
    public static String furnaceNotEmpty(String pos, int items) {
        return "my furnace at " + pos + " still holds " + items + " items (bag full?), so it stays - next: take all " + pos;
    }

    /** The smelt's note when it put its own furnace down (the furnace stays: it holds the job). */
    public static String furnacePlaced(String pos) {
        return "put my furnace down at " + pos + " (it stays there)";
    }
}
