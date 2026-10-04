package io.github.mojolowjo.entropybot.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** B7e: the sim's "storage" run (rs take / rs put) as pure tests: exact counts, a part stack, "put all" keeps. */
class RsCountsTest {
    static final String COBBLE = "minecraft:cobblestone", DIRT = "minecraft:dirt", BREAD = "minecraft:bread";

    @Test
    void takeIsExactAndSaysWhenTheNetworkHadLess() {
        RsCounts.Take t = RsCounts.take(70, 100, 64);
        assertEquals(70, t.want());
        assertNull(t.had());
        assertFalse(RsCounts.takeDone(64, t.want(), 0), "a whole stack came, 6 to go");
        assertTrue(RsCounts.takeDone(70, t.want(), 0));
        assertEquals("took 70 cobblestone", RsCounts.takeNote(70, t, "cobblestone"));
        RsCounts.Take less = RsCounts.take(70, 30, 64);
        assertEquals(30, less.want());
        assertEquals("took 30 cobblestone - the network had only 30", RsCounts.takeNote(30, less, "cobblestone"));
        assertEquals(64, RsCounts.take(null, 100, 64).want(), "no count: one stack");
        assertEquals(16, RsCounts.take(null, 100, 16).want(), "of the item's stack size");
        assertEquals(5, RsCounts.take(null, 5, 64).want());
    }

    @Test
    void aTakeThatStallsEndsWithWhatItGot() {
        RsCounts.Take t = RsCounts.take(70, 100, 64);
        assertFalse(RsCounts.takeDone(40, t.want(), RsCounts.QUIET_END - 1));
        assertTrue(RsCounts.takeDone(40, t.want(), RsCounts.QUIET_END), "three bursts without progress");
        assertEquals("only took 40 of 70 cobblestone (my inventory is full?)", RsCounts.takeNote(40, t, "cobblestone"));
    }

    @Test
    void wholeStacksThenSingles() {
        assertEquals(0, RsCounts.singles(70, 64), "a stack or more: one whole-stack extract");
        assertEquals(0, RsCounts.singles(64, 64));
        assertEquals(6, RsCounts.singles(6, 64), "the rest one by one");
        assertEquals(RsCounts.SINGLES_PER_BURST, RsCounts.singles(40, 64), "at most 16 a burst");
    }

    @Test
    void putIsExactWithAPartStackThroughTheCursor() {
        // the sim: 111 cobblestone in two stacks (64 + 47), "rs put cobblestone 45"
        Map<String, Integer> before = Map.of(COBBLE, 111, DIRT, 21, BREAD, 5);
        Map<String, Integer> want = RsCounts.putWant(before, null, COBBLE, 45);
        assertEquals(Map.of(COBBLE, 45), want);
        List<RsCounts.Slot> slots = List.of(new RsCounts.Slot(30, COBBLE, 64), new RsCounts.Slot(31, COBBLE, 47), new RsCounts.Slot(32, DIRT, 21));
        RsCounts.Plan plan = RsCounts.putPlan(slots, RsCounts.putProgress(want, Map.of()).left());
        assertEquals(List.of(), plan.quickMoves(), "no whole stack fits in 45");
        assertTrue(plan.hasPart());
        assertEquals(30, plan.partSlot());
        assertEquals(45, plan.partCount(), "45 single inserts from the cursor, the rest back in its slot");
        RsCounts.Progress done = RsCounts.putProgress(want, Map.of(COBBLE, 45));
        assertEquals(45, done.moved());
        assertTrue(done.left().isEmpty());
        assertEquals("put 45 cobblestone into the RS network", RsCounts.putNote(45, RsCounts.total(want), false, "cobblestone", 45));
    }

    @Test
    void wholeStacksGoByShiftClickAndOnlyOnePartPerBurst() {
        List<RsCounts.Slot> slots = List.of(new RsCounts.Slot(1, COBBLE, 64), new RsCounts.Slot(2, COBBLE, 64), new RsCounts.Slot(3, COBBLE, 30),
                new RsCounts.Slot(4, COBBLE, 64));
        RsCounts.Plan p = RsCounts.putPlan(slots, Map.of(COBBLE, 150));
        assertEquals(List.of(1, 2), p.quickMoves(), "64 + 64 fit in 150; then 22 are left");
        assertEquals(3, p.partSlot(), "the 30 stack gives 22 of its items");
        assertEquals(22, p.partCount());
        RsCounts.Plan none = RsCounts.putPlan(slots, Map.of(DIRT, 5));
        assertTrue(none.quickMoves().isEmpty() && !none.hasPart(), "nothing of it in the bag");
    }

    @Test
    void putWithoutACountOrMoreThanItHas() {
        Map<String, Integer> before = Map.of(COBBLE, 66, DIRT, 21);
        assertEquals(Map.of(DIRT, 21), RsCounts.putWant(before, null, DIRT, null), "put <item>: all of it");
        Map<String, Integer> want = RsCounts.putWant(before, null, COBBLE, 500);
        assertEquals(Map.of(COBBLE, 66), want, "more than it has: all it has");
        assertEquals("put 66 cobblestone into the RS network (all I had)", RsCounts.putNote(66, RsCounts.total(want), false, "cobblestone", 500));
        assertEquals("only put 30 of 66 cobblestone into the RS network (is it full?)", RsCounts.putNote(30, 66, false, "cobblestone", 500));
        RsCounts.Progress over = RsCounts.putProgress(Map.of(COBBLE, 10), Map.of(COBBLE, 12));
        assertEquals(10, over.moved(), "counted only up to what was wanted");
    }

    @Test
    void putAllKeepsTheKeepAmountsAndFood() {
        // the sim: "rs put all" with 66 cobblestone, 2 dirt and 5 bread leaves 64 cobblestone and the bread
        List<StorageRules.Held> inv = List.of(new StorageRules.Held(COBBLE, 64, false, 0), new StorageRules.Held(COBBLE, 2, false, 1),
                new StorageRules.Held(DIRT, 2, false, 2), new StorageRules.Held(BREAD, 5, true, 3));
        Map<String, Integer> keep = StorageRules.depositables(inv, "", false, null);
        assertFalse(keep.containsKey(BREAD), "food stays: " + keep);
        Map<String, Integer> want = RsCounts.putWant(Map.of(COBBLE, 66, DIRT, 2, BREAD, 5), keep, "all", null);
        assertEquals(Map.of(COBBLE, 2, DIRT, 2), want, "64 cobblestone kept, the dirt goes: " + keep);
        assertEquals(4, RsCounts.total(want));
        assertEquals("put 4 items into the RS network", RsCounts.putNote(4, 4, true, "all", null));
    }
}
