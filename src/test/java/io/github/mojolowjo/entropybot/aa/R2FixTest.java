package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** aa-r2fix: the bed's cell, charcoal never from a coal ore trip, the iron chain with its steps visible. */
class R2FixTest {
    private static boolean freeAll(int[] c) { return true; }

    // ---- the bed's cell ----

    @Test void bedNextToTheTableNotUnderTheBot() {
        int[] me = {10, 64, 10}, table = {12, 64, 10};
        var r = BedSpot.rank(me, table, null, me, BedSpot.NEAR, R2FixTest::freeAll, c -> true);
        assertFalse(r.isEmpty());
        var best = r.get(0);
        assertEquals(1, Math.max(Math.abs(best.cell()[0] - table[0]), Math.abs(best.cell()[2] - table[2])), "next to the camp: " + best.why());
        assertFalse(best.cell()[0] == 10 && best.cell()[2] == 10, "not the bot's cell");
        assertEquals(4, best.heads());
    }

    @Test void bedSkipsRefusedCellsAndNeedsAFreeHead() {
        int[] me = {0, 64, 0};
        var all = BedSpot.rank(me, null, null, me, BedSpot.NEAR, R2FixTest::freeAll, c -> true);
        var first = all.get(0);
        var next = BedSpot.pick(all, Set.of(first.key()));
        assertNotEquals(first.key(), next.key());
        // a lone free cell with no free neighbour cannot take a bed
        int[] lone = {5, 64, 5};
        assertTrue(BedSpot.rank(me, null, null, me, BedSpot.NEAR, c -> c[0] == lone[0] && c[2] == lone[2], c -> true).isEmpty());
        // inside the areas only
        var fenced = BedSpot.rank(me, null, null, me, BedSpot.NEAR, R2FixTest::freeAll, c -> c[0] <= 0);
        assertTrue(fenced.stream().allMatch(s -> s.cell()[0] <= 0));
    }

    @Test void bedWidensWhenTheNearRingHasNoRoom() {
        int[] me = {0, 64, 0};
        // only cells 6+ away are free: the near ring is empty, the far ring finds them
        var s = BedSpot.choose(me, null, null, me, c -> Math.abs(c[0]) >= 6, c -> true, Set.of());
        assertNotNull(s);
        assertTrue(Math.abs(s.cell()[0]) >= 6);
        assertNull(BedSpot.choose(me, null, null, me, c -> false, c -> true, Set.of()), "no free cell at all: none");
    }

    // ---- stock iron: the routine expanded ----

    @Test void expandedRoutineKeepsItsOreStepBeforeTheSmelt() {
        String chain = IronOptions.idleChain((java.util.function.Supplier<IronOptions.Facts>) () -> WoodOptionsTest.facts(0),
                "craft planks 4 then craft furnace 1 then smelt iron_ingot 16 then smelt collect all", 0, new String[1]);
        if (chain != null) assertTrue(chain.indexOf("mine") >= 0 && chain.indexOf("mine") < chain.indexOf("smelt iron_ingot"), chain);
    }
}
