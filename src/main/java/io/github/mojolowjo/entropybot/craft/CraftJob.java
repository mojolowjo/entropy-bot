package io.github.mojolowjo.entropybot.craft;

import java.util.ArrayList;
import java.util.List;

import static io.github.mojolowjo.entropybot.craft.CraftPlanner.shortId;

/**
 * The pure decisions and texts of the bridge's craft job (beginCraftJob / stepCraft / clearGrid) and of the furnace
 * steps of a craft plan (craftPlanSteps / smeltStep), for the session that wires them to the GUI toolkit.
 *
 * <p>The bridge's craft job, per plan step: clear the grid (Visual Workbench tables keep leftovers), fill it with ONE
 * craft (EMI's performFill with amount 1; here {@link GridLayout} plus cursor clicks), wait for the server to show the
 * result (up to {@link #RESULT_WAIT_TICKS}), shift-click the result (one batch, since the grid holds exactly one set),
 * {@code made += outCount}, and go on with the step until {@code made >= want}, then the next step. A fill that fails
 * in the inventory's 2x2 grid sends it to a crafting table once ({@link #onFillFailed}); with {@link Crafter.Craft#needsTable()}
 * known up front the wiring can go to the table directly.
 */
public final class CraftJob {
    private CraftJob() {}

    /** The bridge's crafts per grid fill: one. The mod's craft step fills a whole batch since package G ({@link GridLoop}). */
    public static final int CRAFTS_PER_FILL = 1;
    /** Ticks to wait for the result slot after a fill before giving up ("the grid did not make ..."). */
    public static final int RESULT_WAIT_TICKS = 30;
    /** Ticks the walk to a table runs at least before the bot checks Baritone is idle. */
    public static final int TABLE_WALK_MIN_TICKS = 40;
    /** Ticks to wait for the table's screen to open. */
    public static final int TABLE_OPEN_TICKS = 60;
    /** A vanilla furnace: 10 s an item. */
    public static final int SMELT_TICKS = 200;
    /** Collect rounds at a furnace before "made only N of M". */
    public static final int SMELT_CHECK_TRIES = 4;

    // ---------------------------------------------------------------------------------------------------------------
    // the crafting job
    // ---------------------------------------------------------------------------------------------------------------

    /** The job's first status ("crafting 2 oak_planks -> 1 stick -> 4 torch"); the verb replies "started: " + it. */
    public static String startStatus(List<? extends Crafter.Step> steps) {
        return "crafting " + CraftPlanner.describeSteps(steps);
    }

    public static String started(List<? extends Crafter.Step> steps) {
        return "started: " + startStatus(steps);
    }

    /** The status after a result was taken: "crafting stick 4/8 (step 2/3)". */
    public static String progress(String item, int made, int want, int stepIdx, int stepCount) {
        return "crafting " + shortId(item) + " " + made + "/" + want + (stepCount > 1 ? " (step " + (stepIdx + 1) + "/" + stepCount + ")" : "");
    }

    /** made after taking one result. */
    public static int madeAfterTake(int made, int outCount) {
        return made + outCount * CRAFTS_PER_FILL;
    }

    /** The step is done once this much is made. */
    public static boolean stepDone(int made, int want) {
        return made >= want;
    }

    /** The job's reply when every step is done. */
    public static String done(String label) {
        return "ok: made " + label;
    }

    /** "partial: " when something was made already (an earlier step, or this one), else "error: ". */
    public static String failPrefix(int stepIdx, int made) {
        return stepIdx > 0 || made > 0 ? "partial: " : "error: ";
    }

    /** The result slot stayed empty for {@link #RESULT_WAIT_TICKS} (the grid is cleared first). */
    public static String gridDidNotMake(int stepIdx, int made, String item) {
        return failPrefix(stepIdx, made) + "the grid did not make " + shortId(item);
    }

    /** The grid could not be filled (and the table was tried, or this is the table). */
    public static String couldNotCraft(int stepIdx, int made, String item, String lastError) {
        return failPrefix(stepIdx, made) + "could not craft " + shortId(item) + " - ingredients ran out" + (lastError != null && !lastError.isEmpty() ? " [" + lastError + "]" : "");
    }

    /** What to do when a fill fails. */
    public enum FillFailed { GO_TO_TABLE, GIVE_UP }

    /** A failed fill in the inventory's grid goes to a table once; anything else gives up ({@link #couldNotCraft}). */
    public static FillFailed onFillFailed(boolean inInventoryGrid, boolean triedTable) {
        return inInventoryGrid && !triedTable ? FillFailed.GO_TO_TABLE : FillFailed.GIVE_UP;
    }

    public static String noTable(String item) {
        return "error: " + shortId(item) + " needs a crafting table and there is none near me or the base";
    }

    public static String walkingToTable(String pos) {
        return "walking to the crafting table at " + pos;
    }

    /** The walk's refusal (callers wrap it with fenceError). */
    public static String tableUnreachable(String why, String pos) {
        return why + " (the crafting table at " + pos + ")";
    }

    public static String couldNotOpenTable(String reason) {
        return "error: could not open the crafting table: " + reason;
    }

    public static final String TABLE_DID_NOT_OPEN = "error: the crafting table did not open";
    public static final String SCREEN_CLOSED = "error: crafting screen was closed";

    // ---------------------------------------------------------------------------------------------------------------
    // a plan as job segments, and the furnace steps
    // ---------------------------------------------------------------------------------------------------------------

    /** A run of crafting steps (one craft job), or one furnace step. */
    public record Segment(List<Crafter.Craft> crafts, Crafter.Smelt smelt) {
        public boolean isSmelt() {
            return smelt != null;
        }
    }

    /**
     * craftPlanSteps' grouping: consecutive crafting steps become one craft job, each furnace step its own segment
     * (walk to the furnace, put input and fuel, close, wait {@link #smeltWaitTicks}, walk, collect, check).
     */
    public static List<Segment> segments(List<? extends Crafter.Step> steps) {
        List<Segment> out = new ArrayList<>();
        List<Crafter.Craft> group = new ArrayList<>();
        for (Crafter.Step s : steps) {
            if (s instanceof Crafter.Craft c) {
                group.add(c);
                continue;
            }
            if (!group.isEmpty()) out.add(new Segment(List.copyOf(group), null));
            group.clear();
            out.add(new Segment(List.of(), (Crafter.Smelt) s));
        }
        if (!group.isEmpty()) out.add(new Segment(List.copyOf(group), null));
        return out;
    }

    /** craftPlanSteps' error for a furnace step with no furnace near the bot or the base. */
    public static String noFurnace(String item) {
        return "smelting " + shortId(item) + " needs a furnace, and there is none near me or the base";
    }

    /** How long to wait for {@code n} items in a furnace (plus 2 s). */
    public static int smeltWaitTicks(int n) {
        return n * SMELT_TICKS + 40;
    }

    /** The status while waiting: "&lt;label&gt; - waiting for the furnace (iron_ingot, 12 s)". */
    public static String smeltWaitStatus(String jobLabel, String item, int ticks, int elapsed) {
        return jobLabel + " - waiting for the furnace (" + shortId(item) + ", " + Math.max(0, (int) Math.ceil((ticks - elapsed) / 20.0)) + " s)";
    }

    /** smeltcheck's error once {@link #smeltCheck} gives up ({@code have} = made since the furnace step started). */
    public static String furnaceShort(String pos, int have, int want, String item) {
        return "the furnace at " + pos + " made only " + have + " of " + want + " " + shortId(item);
    }

    /** smeltcheck's decision: done, wait and collect again, or give up ({@link #furnaceShort}). */
    public enum SmeltCheck { DONE, RETRY, GIVE_UP }

    /** {@code tries}: 1 at the first check after the wait, +1 for each retry (the bridge's st.tries). */
    public static SmeltCheck smeltCheck(int have, int want, int tries) {
        if (have >= want) return SmeltCheck.DONE;
        return tries >= SMELT_CHECK_TRIES ? SmeltCheck.GIVE_UP : SmeltCheck.RETRY;
    }

    /** The wait before collecting the rest. */
    public static int smeltRetryTicks(int have, int want) {
        return (want - have) * SMELT_TICKS + 40;
    }

    /** The job's note after a plan with furnace steps. */
    public static String smeltNote(String label) {
        return "made " + label + " (with the furnace)";
    }
}
