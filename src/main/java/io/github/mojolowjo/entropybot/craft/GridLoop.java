package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.gui.GuiCore;
import io.github.mojolowjo.entropybot.gui.GuiMenu;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.mojolowjo.entropybot.craft.CraftPlanner.shortId;

/**
 * Package G (TO-LOOK-AT-LATER 16): one recipe crafted in an open crafting grid as a loop that runs on game ticks, a
 * whole batch per fill. Each round: clear the grid (only when something is in it), put {@code k} of each ingredient in
 * its cell ({@code k} = what the bag holds, at most a stack, at most what is still wanted, at most what the bag has room
 * for), wait for the server to show the result, shift-click it once (the game crafts all {@code k} at once), then wait
 * until the bag shows the batch and count what really arrived. Before this, every craft was its own fill, clear and
 * settle (~15 ticks per craft; 64 inferium blocks took ~50 s); now a batch of up to 64 takes ~10 ticks.
 *
 * <p>Game-free ({@link GuiMenu} is the open menu: slot 0 the result, 1..n*n the grid; {@link Items} names an item's
 * stack size), so JUnit drives it with a fake menu and package E's {@code upgrade} can reuse it as is: build one per
 * recipe and call {@link #tick} every step until it says DONE or FAIL.
 */
public final class GridLoop {
    /** A stack per cell at most (and never more than the item's own stack size). */
    public static final int MAX_PER_CELL = 64;
    /** Ticks after a clear before the next fill (the cleared items settle in the bag). */
    public static final int CLEAR_TICKS = 4;
    /** Ticks after the shift-click before the batch is counted when it all arrived; at most {@link #SETTLE_MAX}. */
    public static final int SETTLE_MIN = 2, SETTLE_MAX = 40;

    public enum State { WAIT, DONE, FAIL }

    /** Why a FAIL: the server showed no result, the grid couldn't be laid out or filled, or the bag is full. */
    public enum Why { NO_RESULT, NO_LAYOUT, FILL, FULL }

    public record Out(State state, Why why, String error) {
        static final Out WAIT = new Out(State.WAIT, null, null), DONE = new Out(State.DONE, null, null);

        static Out fail(Why why, String error) { return new Out(State.FAIL, why, error); }
    }

    private final RecipeData recipe;
    private final int size, want;
    private int made, batch, before, batches;
    private String stage;
    private long stageTick;

    /** {@code made}: what this recipe made already (a loop rebuilt after a fight carries on from there). */
    public GridLoop(RecipeData recipe, int size, int want, int made) {
        this.recipe = recipe;
        this.size = size;
        this.want = want;
        this.made = made;
    }

    public int size() { return size; }

    /** Items made so far (counted from the bag, not assumed). */
    public int made() { return made; }

    /** Fills so far (for the log and the tests). */
    public int batches() { return batches; }

    public boolean done() { return made >= want; }

    /** The batch is crafted and not yet counted: keep ticking it (with any menu open) until this turns false. */
    public boolean settling() { return "settle".equals(stage); }

    /**
     * A reflex (a fight, a meal) held the job. A batch being counted keeps being counted (the bag is the bag whatever
     * menu is open, so nothing it made is lost or made twice); any other round starts over, clearing the grid first.
     */
    public void interrupted() {
        if (!settling()) stage = null;
    }

    /** What the loop needs to know about items: the stack size, and whether crafting leaves something in the grid. */
    @FunctionalInterface
    public interface Items {
        int maxStack(String id);

        /** A bucket, a bottle...: the item leaves a remainder in its cell, so a batch is one craft. */
        default boolean remainder(String id) { return false; }
    }

    /** One step with the loop's own grid open. */
    public Out tick(GuiMenu m, Items items, long now) { return tick(m, size, items, now); }

    /**
     * One step; call it every tick or two with the menu as it is now. {@code openGrid} = the open menu's grid size
     * (0 = no crafting grid). Away from its grid the loop only finishes counting a batch, never clicks, and waits.
     */
    public Out tick(GuiMenu m, int openGrid, Items items, long now) {
        if (settling()) return settle(m, openGrid == size, items, now);
        if (done()) return Out.DONE;
        if (openGrid != size) {
            stage = null;                    // the caller opens the grid again; the round starts over
            return Out.WAIT;
        }
        if (stage == null) {
            if (clear(m, size)) {
                stage = "cleared";
                stageTick = now;
                return Out.WAIT;
            }
            stage = "fill";
        }
        if (stage.equals("cleared")) {
            if (now - stageTick < CLEAR_TICKS) return Out.WAIT;
            // what the clear couldn't move back is still in the grid: the bag is full (never fill on top of it)
            if (!gridEmpty(m, size)) {
                stage = null;
                return Out.fail(Why.FULL, "my inventory is full (the crafting grid won't empty)");
            }
            stage = "fill";
        }
        if (stage.equals("fill")) return fill(m, items, now);
        if (stage.equals("result")) {
            if (m.id(0) == null) {
                if (now - stageTick < CraftJob.RESULT_WAIT_TICKS) return Out.WAIT;
                clear(m, size);
                stage = null;
                return Out.fail(Why.NO_RESULT, "the grid did not make " + shortId(recipe.output()));
            }
            before = bag(m).getOrDefault(recipe.output(), 0);
            m.click(0, 0, "QUICK_MOVE");
            stage = "settle";
            stageTick = now;
            return Out.WAIT;
        }
        return Out.WAIT;
    }

    /** The server crafts the whole batch; the client sees it when the bag's slots are synced. Counted from the bag. */
    private Out settle(GuiMenu m, boolean ours, Items items, long now) {
        int gained = bag(m).getOrDefault(recipe.output(), 0) - before;
        long in = now - stageTick;
        if (in < SETTLE_MIN || (gained < batch * recipe.outCount() && in < SETTLE_MAX)) return Out.WAIT;
        stage = null;
        if (gained <= 0) {
            if (ours) clear(m, size);
            return room(m, items) < recipe.outCount()
                    ? Out.fail(Why.FULL, "my inventory is full")
                    : Out.fail(Why.NO_RESULT, "the grid did not make " + shortId(recipe.output()));
        }
        made += gained;
        if (done()) {
            if (ours) clear(m, size);        // a short batch leaves the rest in the grid: back into the bag
            return Out.DONE;
        }
        return Out.WAIT;
    }

    static boolean gridEmpty(GuiMenu m, int size) {
        for (int i = 1; i <= size * size; i++) if (m.id(i) != null) return false;
        return true;
    }

    private Out fill(GuiMenu m, Items items, long now) {
        Map<String, Integer> have = bag(m);
        GridLayout.Layout lay = GridLayout.layout(recipe, size, have);
        if (!lay.ok()) return Out.fail(Why.NO_LAYOUT, lay.error() + (lay.missing() != null ? " (" + lay.missing() + ")" : ""));
        Map<String, Integer> per = new LinkedHashMap<>();
        for (String id : lay.slots().values()) per.merge(id, 1, Integer::sum);
        int k = MAX_PER_CELL;
        for (Map.Entry<String, Integer> e : per.entrySet()) {
            k = Math.min(k, have.getOrDefault(e.getKey(), 0) / e.getValue());
            int max = items.maxStack(e.getKey());
            if (max > 0) k = Math.min(k, max);
            if (items.remainder(e.getKey())) k = Math.min(k, 1);     // the remainder stays in the cell: one craft a fill
        }
        int out = Math.max(1, recipe.outCount());
        k = Math.min(k, (want - made + out - 1) / out);
        int room = room(m, items);
        if (room < out) return Out.fail(Why.FULL, "my inventory is full");
        k = Math.min(k, room / out);
        if (k < 1) return Out.fail(Why.NO_LAYOUT, GridLayout.RAN_OUT);
        int got = Integer.MAX_VALUE;
        for (Map.Entry<Integer, String> e : lay.slots().entrySet()) {
            int cell = GridLayout.menuSlot(e.getKey());
            int n = put(m, e.getValue(), cell, k);
            if (n < 1) {
                clear(m, size);
                stage = null;
                return Out.fail(Why.FILL, "couldn't put " + shortId(e.getValue()) + " in the grid");
            }
            got = Math.min(got, n);
        }
        batch = got;
        batches++;
        stage = "result";
        stageTick = now;
        return Out.WAIT;
    }

    /** Up to n of item into the grid cell, from as many bag stacks as it takes; how many are in the cell then. */
    static int put(GuiMenu m, String item, int cell, int n) {
        for (int i = 0; i < m.size(); i++) {
            int in = m.id(cell) == null ? 0 : m.count(cell);
            if (in >= n) break;
            if (m.mine(i) && item.equals(m.id(i))) GuiCore.move(m, i, cell, n - in);
        }
        return m.id(cell) == null ? 0 : m.count(cell);
    }

    /** Shift-clicks everything in the grid back into the bag; true when there was something to move. */
    public static boolean clear(GuiMenu m, int size) {
        boolean any = false;
        for (int i = 1; i <= size * size; i++) {
            if (m.id(i) != null) {
                m.click(i, 0, "QUICK_MOVE");
                any = true;
            }
        }
        return any;
    }

    /** The bag's items (inventory slots of the open menu: no armor, no grid). */
    static Map<String, Integer> bag(GuiMenu m) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < m.size(); i++) if (m.mine(i) && m.id(i) != null) out.merge(m.id(i), m.count(i), Integer::sum);
        return out;
    }

    /** How many more of the recipe's output the bag takes: empty slots and room on its own stacks. */
    int room(GuiMenu m, Items items) {
        String id = recipe.output();
        int max = Math.max(1, items.maxStack(id));
        long room = 0;
        for (int i = 0; i < m.size(); i++) {
            if (!m.mine(i)) continue;
            if (m.id(i) == null) room += max;
            else if (id.equals(m.id(i))) room += Math.max(0, m.stackMax(i) - m.count(i));
        }
        return (int) Math.min(Integer.MAX_VALUE, room);
    }
}
