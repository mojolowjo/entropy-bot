package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.gui.GuiMenu;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Package G: the batch crafting loop against a fake table whose server answers a few ticks late. */
class GridLoopTest {
    static final String ESS = "mysticalagriculture:inferium_essence", BLOCK = "mysticalagriculture:inferium_block";
    static final RecipeData COMPACT = RecipeData.shaped("ma:block", BLOCK, 1, 3, 3, Collections.nCopies(9, List.of(ESS)));
    static final RecipeData UNCOMPACT = RecipeData.shapeless("ma:ess", ESS, 9, List.of(List.of(BLOCK)));
    static final RecipeData TORCH = RecipeData.shaped("mc:torch", "minecraft:torch", 4, 1, 2, List.of(List.of("minecraft:coal"), List.of("minecraft:stick")));

    /** Slot 0 = the result, 1..n*n = the grid, then 36 bag slots; the "server" shows results and crafts `lag` ticks late. */
    static final class Table implements GuiMenu {
        final int size, lag;
        final RecipeData r;
        final String[] id;
        final int[] n;
        String cid;
        int cn, clicks;
        long now;
        long resultAt = -1, craftAt = -1;
        boolean serverMakesNothing;
        /** Package E: a catalyst the server hands back into its cell (the infusion crystal) and its uses left. */
        String keeps;
        int keepUses = Integer.MAX_VALUE, crafted;

        Table(int size, RecipeData r, int lag) {
            this.size = size;
            this.r = r;
            this.lag = lag;
            int total = 1 + size * size + 36;
            id = new String[total];
            n = new int[total];
        }

        int bag0() { return 1 + size * size; }

        Table give(String item, int count) {
            for (int i = bag0(); i < id.length && count > 0; i++) {
                if (n[i] == 0) {
                    int put = Math.min(count, max(item));
                    id[i] = item;
                    n[i] = put;
                    count -= put;
                }
            }
            return this;
        }

        Table fillBag(String item) {
            for (int i = bag0(); i < id.length; i++) if (n[i] == 0) { id[i] = item; n[i] = max(item); }
            return this;
        }

        static int max(String item) { return item.endsWith("ender_pearl") ? 16 : item.endsWith("_crystal") ? 1 : 64; }

        int has(String item) {
            int t = 0;
            for (int i = bag0(); i < id.length; i++) if (n[i] > 0 && item.equals(id[i])) t += n[i];
            return t;
        }

        int inGrid() {
            int t = 0;
            for (int i = 1; i <= size * size; i++) t += n[i];
            return t;
        }

        /** What the grid makes now (one craft), or null: the recipe's cells laid out exactly. */
        boolean matches() {
            if (r.shapeless()) {
                List<String> want = new ArrayList<>();
                for (List<String> c : r.cells()) want.add(c.get(0));
                List<String> got = new ArrayList<>();
                for (int i = 1; i <= size * size; i++) if (n[i] > 0) got.add(id[i]);
                Collections.sort(want);
                Collections.sort(got);
                return want.equals(got);
            }
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    int s = 1 + row * size + col;
                    String need = row < r.height() && col < r.width() && !r.cells().get(row * r.width() + col).isEmpty() ? r.cells().get(row * r.width() + col).get(0) : null;
                    if (need == null ? n[s] > 0 : n[s] == 0 || !need.equals(id[s])) return false;
                }
            }
            return true;
        }

        void gridChanged() {
            if (n[0] > 0) { n[0] = 0; id[0] = null; }
            resultAt = now + lag;
        }

        /** The server's tick: the result shows `lag` ticks after a change; a shift-click crafts all it can. */
        void serverTick() {
            if (craftAt >= 0 && now >= craftAt) {
                craftAt = -1;
                while (matches() && room(r.output()) >= r.outCount() && !serverMakesNothing) {
                    for (int i = 1; i <= size * size; i++) {
                        if (n[i] > 0 && id[i].equals(keeps)) {
                            // package E: the crystal comes back into its cell, one use worn off (gone when used up)
                            if (--keepUses <= 0) { n[i] = 0; id[i] = null; }
                            continue;
                        }
                        if (n[i] > 0 && --n[i] == 0) id[i] = null;
                    }
                    add(r.output(), r.outCount());
                    crafted++;
                }
                gridChanged();
            }
            if (resultAt >= 0 && now >= resultAt) {
                resultAt = -1;
                if (matches() && !serverMakesNothing) {
                    id[0] = r.output();
                    n[0] = r.outCount();
                } else {
                    id[0] = null;
                    n[0] = 0;
                }
            }
        }

        int room(String item) {
            int t = 0;
            for (int i = bag0(); i < id.length; i++) t += n[i] == 0 ? max(item) : item.equals(id[i]) ? max(item) - n[i] : 0;
            return t;
        }

        void add(String item, int count) {
            for (int pass = 0; pass < 2; pass++) {
                for (int i = bag0(); i < id.length && count > 0; i++) {
                    if (pass == 0 ? n[i] == 0 || !item.equals(id[i]) : n[i] > 0) continue;
                    int put = Math.min(count, max(item) - n[i]);
                    id[i] = item;
                    n[i] += put;
                    count -= put;
                }
            }
            if (count > 0) throw new IllegalStateException("fake bag overflow");
        }

        boolean grid(int i) { return i >= 1 && i <= size * size; }

        @Override public int size() { return id.length; }
        @Override public String id(int i) { return n[i] > 0 ? id[i] : null; }
        @Override public int count(int i) { return n[i]; }
        @Override public int stackMax(int i) { return id(i) == null ? 0 : max(id(i)); }
        @Override public boolean mine(int i) { return i >= bag0(); }
        @Override public boolean armor(int i) { return false; }
        @Override public String probe(int i) { return i == 0 ? "output" : "plain"; }
        @Override public boolean mayPlace(int to, int from) { return to != 0; }
        @Override public int slotLimit(int to, int from) { return max(id(from)); }
        @Override public boolean same(int a, int b) { return id(a) != null && id(a).equals(id(b)); }
        @Override public Boolean cursorHas() { return cn > 0; }
        @Override public boolean cursorSame(int i) { return cn > 0 && cid.equals(id(i)); }

        @Override public void click(int i, int button, String type) {
            clicks++;
            switch (type) {
                case "QUICK_MOVE" -> {
                    if (i == 0) {
                        if (n[0] > 0) craftAt = now + lag;            // the server crafts the batch
                        return;
                    }
                    if (n[i] == 0) return;
                    int c = Math.min(n[i], room(id[i]));
                    String it = id[i];
                    n[i] -= c;
                    if (n[i] == 0) id[i] = null;
                    add(it, c);
                    if (grid(i)) gridChanged();
                }
                case "PICKUP" -> {
                    if (i == 0) return;
                    if (cn == 0) {
                        if (n[i] == 0) return;
                        int take = button == 0 ? n[i] : (n[i] + 1) / 2;
                        cid = id[i];
                        cn = take;
                        n[i] -= take;
                        if (n[i] == 0) id[i] = null;
                    } else if (n[i] == 0 || cid.equals(id[i])) {
                        int put = Math.min(button == 0 ? cn : 1, max(cid) - n[i]);
                        if (put <= 0) return;
                        id[i] = cid;
                        n[i] += put;
                        cn -= put;
                    } else return;
                    if (grid(i)) gridChanged();
                }
                default -> throw new IllegalArgumentException(type);
            }
        }
    }

    /** Runs the loop every 2 ticks (as Seq does) until it ends; the ticks it took. */
    static long run(GridLoop loop, Table t, int maxTicks, GridLoop.Out[] last) {
        for (long tick = 1; tick <= maxTicks; tick++) {
            t.now = tick;
            t.serverTick();
            if (tick % 2 != 0) continue;
            GridLoop.Out o = loop.tick(t, Table::max, tick);
            if (o.state() != GridLoop.State.WAIT) {
                last[0] = o;
                return tick;
            }
        }
        last[0] = null;
        return maxTicks;
    }

    @Test
    void sixtyFourBlocksInOneBatch() {
        Table t = new Table(3, COMPACT, 3).give(ESS, 576);
        GridLoop loop = new GridLoop(COMPACT, 3, 64, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        long ticks = run(loop, t, 2000, o);
        assertEquals(GridLoop.State.DONE, o[0].state());
        assertEquals(64, t.has(BLOCK));
        assertEquals(0, t.has(ESS));
        assertEquals(64, loop.made());
        assertEquals(1, loop.batches(), "one fill for all 64");
        assertTrue(ticks < 30, "took " + ticks + " ticks (one craft per fill took ~15 a block)");
        assertEquals(0, t.inGrid());
    }

    @Test
    void stopsAtWhatIsWanted() {
        Table t = new Table(3, COMPACT, 2).give(ESS, 300);
        GridLoop loop = new GridLoop(COMPACT, 3, 10, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 2000, o);
        assertEquals(GridLoop.State.DONE, o[0].state());
        assertEquals(10, t.has(BLOCK));
        assertEquals(300 - 90, t.has(ESS));
        assertEquals(0, t.inGrid(), "nothing left in the table");
    }

    @Test
    void manyBatchesWhenThereIsMoreThanAStack() {
        // 1000 essence: 111 blocks, two batches (64 + 47), 1 essence left
        Table t = new Table(3, COMPACT, 3).give(ESS, 1000);
        GridLoop loop = new GridLoop(COMPACT, 3, 1000, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 4000, o);
        assertEquals(GridLoop.State.FAIL, o[0].state(), "runs out of essence before 1000 blocks");
        assertEquals(GridLoop.Why.NO_LAYOUT, o[0].why());
        assertEquals(111, t.has(BLOCK));
        assertEquals(1, t.has(ESS));
        assertEquals(2, loop.batches());
    }

    @Test
    void outputCountsAndTheInventoryGrid() {
        // 2x2 grid, 4 torches a craft: 20 coal + 20 sticks, want 32 -> 8 crafts in one batch
        Table t = new Table(2, TORCH, 2).give("minecraft:coal", 20).give("minecraft:stick", 20);
        GridLoop loop = new GridLoop(TORCH, 2, 32, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 2000, o);
        assertEquals(GridLoop.State.DONE, o[0].state());
        assertEquals(32, t.has("minecraft:torch"));
        assertEquals(12, t.has("minecraft:coal"));
        assertEquals(1, loop.batches());
    }

    @Test
    void aBatchNeverOverflowsTheBag() {
        // uncrafting blocks: 9 essence each; a bag with 3 free slots takes 192 essence = 21 blocks' worth
        Table t = new Table(3, UNCOMPACT, 2).give(BLOCK, 64);
        for (int i = 0; i < 32; i++) t.give("minecraft:cobblestone", 64);
        GridLoop loop = new GridLoop(UNCOMPACT, 3, 64 * 9, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 4000, o);
        assertEquals(GridLoop.State.FAIL, o[0].state());
        assertEquals(GridLoop.Why.FULL, o[0].why());
        assertTrue(t.has(ESS) > 0);
        assertEquals(64 * 9, t.has(ESS) + 9 * t.has(BLOCK), "nothing lost");
        assertEquals(0, t.inGrid());
    }

    @Test
    void noResultFails() {
        Table t = new Table(3, COMPACT, 2).give(ESS, 90);
        t.serverMakesNothing = true;
        GridLoop loop = new GridLoop(COMPACT, 3, 10, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 2000, o);
        assertEquals(GridLoop.State.FAIL, o[0].state());
        assertEquals(GridLoop.Why.NO_RESULT, o[0].why());
        assertEquals(90, t.has(ESS), "the grid went back into the bag");
    }

    @Test
    void leftoversInTheGridAreClearedFirst() {
        Table t = new Table(3, COMPACT, 2).give(ESS, 81);
        // a Visual Workbench left a stick in the corner
        t.id[9] = "minecraft:stick";
        t.n[9] = 1;
        GridLoop loop = new GridLoop(COMPACT, 3, 9, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 2000, o);
        assertEquals(GridLoop.State.DONE, o[0].state());
        assertEquals(9, t.has(BLOCK));
        assertEquals(1, t.has("minecraft:stick"));
    }

    /** Ticks (every 2, as Seq) until the loop has shift-clicked a batch and is counting it. */
    static long untilSettling(GridLoop loop, Table t, long from) {
        for (long tick = from; tick < from + 500; tick++) {
            t.now = tick;
            t.serverTick();
            if (tick % 2 != 0) continue;
            loop.tick(t, Table::max, tick);
            if (loop.settling()) return tick;
        }
        throw new AssertionError("never settled");
    }

    @Test
    void aHoldDuringSettleNeverChangesWhatIsMade() {
        // a fight closes the table right after the shift-click; the server's batch lands while no grid is open
        Table t = new Table(3, COMPACT, 6).give(ESS, 300);
        GridLoop loop = new GridLoop(COMPACT, 3, 10, 0);
        long tick = untilSettling(loop, t, 1);
        loop.interrupted();
        for (long k = tick + 1; k < tick + 60; k++) {
            t.now = k;
            t.serverTick();
            if (k % 2 == 0) {
                GridLoop.Out o = loop.tick(t, 0, Table::max, k);       // no grid open: it only counts
                if (o.state() == GridLoop.State.DONE) break;
                assertNotEquals(GridLoop.State.FAIL, o.state());
            }
        }
        assertEquals(10, loop.made(), "the batch is booked although the table closed");
        assertTrue(loop.done());
        assertEquals(10, t.has(BLOCK));
        assertEquals(300 - 90, t.has(ESS), "nothing crafted twice from the bot's own stock");
    }

    @Test
    void aHoldBeforeTheClickStartsTheRoundOver() {
        Table t = new Table(3, COMPACT, 4).give(ESS, 300);
        GridLoop loop = new GridLoop(COMPACT, 3, 20, 0);
        // the fill, then a meal before the result shows: the round starts over (the grid is cleared first)
        for (long tick = 1; tick <= 2; tick++) {
            t.now = tick;
            t.serverTick();
            if (tick % 2 == 0) loop.tick(t, Table::max, tick);
        }
        assertFalse(loop.settling());
        assertTrue(t.inGrid() > 0, "filled");
        loop.interrupted();
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 2000, o);
        assertEquals(GridLoop.State.DONE, o[0].state());
        assertEquals(20, t.has(BLOCK));
        assertEquals(300 - 180, t.has(ESS));
    }

    @Test
    void aRemainderMeansOneCraftAFill() {
        RecipeData sweet = RecipeData.shapeless("x:sweet", "x:sweet", 1, List.of(List.of("minecraft:milk_bucket"), List.of("minecraft:sugar")));
        Table t = new Table(3, sweet, 2).give("minecraft:milk_bucket", 4).give("minecraft:sugar", 4);
        GridLoop loop = new GridLoop(sweet, 3, 3, 0);
        GridLoop.Items items = new GridLoop.Items() {
            @Override public int maxStack(String id) { return Table.max(id); }
            @Override public boolean remainder(String id) { return id.endsWith("_bucket"); }
        };
        GridLoop.Out o = null;
        for (long tick = 1; tick < 2000; tick++) {
            t.now = tick;
            t.serverTick();
            if (tick % 2 != 0) continue;
            o = loop.tick(t, items, tick);
            if (o.state() != GridLoop.State.WAIT) break;
        }
        assertEquals(GridLoop.State.DONE, o.state());
        assertEquals(3, loop.batches(), "one craft per fill");
        assertEquals(3, t.has("x:sweet"));
    }

    @Test
    void aGridThatWontEmptyIsNeverFilledOnTop() {
        // a full bag: the stick left in the grid can't go back, so the loop stops instead of filling around it
        Table t = new Table(3, COMPACT, 2).give(ESS, 81).fillBag("minecraft:cobblestone");
        t.id[9] = "minecraft:stick";
        t.n[9] = 1;
        GridLoop loop = new GridLoop(COMPACT, 3, 9, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 200, o);
        assertEquals(GridLoop.State.FAIL, o[0].state());
        assertEquals(GridLoop.Why.FULL, o[0].why());
        assertEquals(1, t.inGrid(), "only the stick: nothing was put in on top of it");
        assertEquals(81, t.has(ESS));
    }

    @Test
    void aSlowServerIsWaitedFor() {
        // the batch shows up 12 ticks after the click: the loop waits for it instead of counting too few
        Table t = new Table(3, COMPACT, 12).give(ESS, 576);
        GridLoop loop = new GridLoop(COMPACT, 3, 64, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 2000, o);
        assertEquals(GridLoop.State.DONE, o[0].state());
        assertEquals(64, t.has(BLOCK));
        assertEquals(1, loop.batches());
    }

    // ---- package E: the infusion crystal batches (one in its cell, handed back by every craft) ----

    static final String CRYSTAL = "mysticalagriculture:infusion_crystal", PRUD = "mysticalagriculture:prudentium_essence";
    /** MA's tier-up: 4 essence in cells 2, 4, 6, 8 and the crystal in cell 5. */
    static final RecipeData TIER_UP = RecipeData.shaped("ma:essence/prudentium", PRUD, 1, 3, 3,
            List.of(List.of(), List.of(ESS), List.of(), List.of(ESS), List.of(CRYSTAL), List.of(ESS), List.of(), List.of(ESS), List.of()));

    /** The crystal has a crafting remainder (itself, worn by one): a catalyst. */
    static GridLoop.Items crystalItems(boolean catalystRule) {
        return new GridLoop.Items() {
            @Override public int maxStack(String id) { return Table.max(id); }
            @Override public boolean remainder(String id) { return id.endsWith("_crystal"); }
            @Override public boolean catalyst(String id) { return catalystRule && id.endsWith("_crystal"); }
        };
    }

    static long run(GridLoop loop, Table t, int maxTicks, GridLoop.Out[] last, GridLoop.Items items) {
        for (long tick = 1; tick <= maxTicks; tick++) {
            t.now = tick;
            t.serverTick();
            if (tick % 2 != 0) continue;
            GridLoop.Out o = loop.tick(t, items, tick);
            if (o.state() != GridLoop.State.WAIT) {
                last[0] = o;
                return tick;
            }
        }
        last[0] = null;
        return maxTicks;
    }

    @Test
    void theCrystalBatchesSixtyFourCraftsInOneFill() {
        // TO-LOOK-AT-LATER 15: "n = 16 gave 16 crafts at once" - 64 a cell and the crystal in the middle: 64 crafts, one click
        Table t = new Table(3, TIER_UP, 3).give(ESS, 256).give(CRYSTAL, 1);
        t.keeps = CRYSTAL;
        GridLoop loop = new GridLoop(TIER_UP, 3, 64, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        long ticks = run(loop, t, 2000, o, crystalItems(true));
        assertEquals(GridLoop.State.DONE, o[0].state());
        assertEquals(64, t.has(PRUD));
        assertEquals(0, t.has(ESS));
        assertEquals(1, t.has(CRYSTAL), "the crystal is back in the bag, kept");
        assertEquals(1, loop.batches(), "one fill, one shift-click for 64 crafts");
        assertEquals(64, t.crafted);
        assertEquals(0, t.inGrid());
        assertTrue(ticks < 40, "took " + ticks + " ticks");
    }

    @Test
    void moreThanAStackIsAFewFillsNotOneACraft() {
        // 1024 inferium -> 256 prudentium: 4 fills (it was 256 fills: the crystal limited every batch to 1)
        Table t = new Table(3, TIER_UP, 2).give(ESS, 1024).give(CRYSTAL, 1);
        t.keeps = CRYSTAL;
        GridLoop loop = new GridLoop(TIER_UP, 3, 256, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 4000, o, crystalItems(true));
        assertEquals(GridLoop.State.DONE, o[0].state());
        assertEquals(256, t.has(PRUD));
        assertEquals(4, loop.batches());
        // the old rule (a remainder = one craft a fill) for comparison
        Table old = new Table(3, TIER_UP, 2).give(ESS, 32).give(CRYSTAL, 1);
        old.keeps = CRYSTAL;
        GridLoop slow = new GridLoop(TIER_UP, 3, 8, 0);
        run(slow, old, 4000, o, crystalItems(false));
        assertEquals(GridLoop.State.DONE, o[0].state());
        assertEquals(8, slow.batches(), "without the catalyst rule: one craft a fill");
    }

    @Test
    void aWornOutCrystalEndsTheBatchAndSaysSo() {
        // 10 uses left: the batch of 64 stops at 10, the rest of the essence goes back into the bag
        Table t = new Table(3, TIER_UP, 2).give(ESS, 256).give(CRYSTAL, 1);
        t.keeps = CRYSTAL;
        t.keepUses = 10;
        GridLoop loop = new GridLoop(TIER_UP, 3, 64, 0);
        GridLoop.Out[] o = new GridLoop.Out[1];
        run(loop, t, 4000, o, crystalItems(true));
        assertEquals(GridLoop.State.FAIL, o[0].state());
        assertEquals(GridLoop.Why.NO_LAYOUT, o[0].why());
        assertTrue(o[0].error().contains("infusion_crystal"), o[0].error());
        assertEquals(10, loop.made(), "counted from the bag");
        assertEquals(10, t.has(PRUD));
        assertEquals(256 - 40, t.has(ESS), "nothing lost");
        assertEquals(0, t.inGrid());
    }
}
