package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Inputs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** aa-r1fix: the idle stock_iron ore step and the camp chest's cell. */
class R1FixTest {
    // ---- idle stock_iron: the ore step comes first, through the iron options ----

    @Test void idleIronMinesBeforeItSmelts() {
        String[] why = new String[1];
        Options.Option o = IronOptions.idleOption((java.util.function.Supplier<IronOptions.Facts>) () -> WoodOptionsTest.facts(0),
                io.github.mojolowjo.entropybot.aa.TS.s("smelt iron_ingot 16"), 0, why);
        // either an ore way runs (mine before smelt) or nothing runs; never the bare smelt
        if (o == null) assertNotNull(why[0]);
        else {
            String chain = o.command();
            assertTrue(o.typed(), "rm-execution: typed steps");
            assertTrue(chain.indexOf("mine") >= 0 && chain.indexOf("mine") < chain.indexOf("smelt"), chain);
        }
    }

    @Test void idleIronEnoughRawIronKeepsTheSmelt() {
        Options.Option o = IronOptions.idleOption((java.util.function.Supplier<IronOptions.Facts>) () -> WoodOptionsTest.facts(16),
                io.github.mojolowjo.entropybot.aa.TS.s("smelt iron_ingot 16"), 0, new String[1]);
        assertNotNull(o);
        assertTrue(o.steps().stream().anyMatch(s -> s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Smelt), o.command());
    }

    @Test void idleIronNoFactsRunsNothing() {
        String[] why = new String[1];
        assertNull(IronOptions.idleOption(null, io.github.mojolowjo.entropybot.aa.TS.s("smelt iron_ingot 16"), 0, why), "rm-execution: no planner chain text runs bare");
        assertEquals("no iron facts", why[0]);
    }

    // ---- the camp chest's cell ----

    private static boolean freeAll(int[] c) { return true; }

    @Test void chestCellNextToTheTableAndCheapest() {
        int[] base = {10, 64, 10}, table = {12, 64, 10};
        List<ChestSpot.Spot> r = ChestSpot.rank(base, table, new int[]{10, 64, 11}, R1FixTest::freeAll, c -> true);
        assertFalse(r.isEmpty());
        int[] best = r.get(0).cell();
        assertEquals(1, Math.max(Math.abs(best[0] - table[0]), Math.abs(best[2] - table[2])), "next to the table");
        assertFalse(best[0] == 10 && best[2] == 10, "not the base cell");
        assertFalse(best[0] == 10 && best[2] == 11, "not the bot's cell");
        assertFalse(best[0] == 12 && best[2] == 10, "not the table's cell");
    }

    @Test void chestCellSkipsRefusedBlockedAndOutsideCells() {
        int[] base = {0, 64, 0};
        var all = ChestSpot.rank(base, null, new int[]{0, 64, 1}, R1FixTest::freeAll, c -> true);
        var first = all.get(0);
        assertEquals(Set.of(), Set.of());
        ChestSpot.Spot next = ChestSpot.pick(all, Set.of(first.key()));
        assertNotNull(next);
        assertNotEquals(first.key(), next.key(), "the refused cell gives way to the fallback");
        var walled = ChestSpot.rank(base, null, null, c -> c[0] != 1, c -> true);
        assertTrue(walled.stream().noneMatch(s -> s.cell()[0] == 1), "a cell the game cannot take is not offered");
        var fenced = ChestSpot.rank(base, null, null, R1FixTest::freeAll, c -> c[0] <= 0);
        assertTrue(fenced.stream().allMatch(s -> s.cell()[0] <= 0), "inside the areas only");
        assertNull(ChestSpot.pick(ChestSpot.rank(base, null, null, c -> false, c -> true), Set.of()), "no free cell: none");
    }

    @Test void refusedCellIsReadFromTheTaskEnd() {
        Set<String> failed = new java.util.HashSet<>();
        ChestSpot.noteFailure(failed, "stopped at step 4 (place chest 11 64 5): the game refused placing");
        ChestSpot.noteFailure(failed, "stopped at step 2 (craft chest 1): no planks");
        assertEquals(Set.of("11 64 5"), failed);
    }

}
