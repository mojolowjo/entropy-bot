package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.33.0 review 5: a station of mine is picked up only when it is still the one I placed, and a container only when empty. */
class OwnStationPickUpTest {
    @Test
    void matchesKindAndPlacement() {
        assertNull(OwnStations.pickUp("minecraft:crafting_table", "crafting_table", false, "crafting_table", null, 1, 2, 3));
        assertTrue(OwnStations.pickUp("minecraft:crafting_table", "furnace", false, "furnace", 0, 1, 2, 3).contains("not the one I placed"));
        // a player broke mine and put their own crafting table in the cell: same kind, not mine
        assertTrue(OwnStations.pickUp("crafting_table", "crafting_table", true, "crafting_table", null, 1, 2, 3).contains("not mine any more"));
    }

    @Test
    void containersOnlyWhenKnownEmpty() {
        assertTrue(OwnStations.pickUp("chest", "chest", false, "chest", null, 1, 2, 3).contains("may hold items"));
        String full = OwnStations.pickUp("chest", "chest", false, "chest", 12, 1, 2, 3);
        assertTrue(full.contains("holds 12 items") && full.contains("take"), full);
        assertNull(OwnStations.pickUp("chest", "chest", false, "chest", 0, 1, 2, 3));
        assertTrue(OwnStations.container("minecraft:furnace"));
        assertFalse(OwnStations.container("crafting_table"));
    }

    @Test
    void hooksNeverThrowAndDefaultToNothing() {
        var oldC = OwnStations.cells;
        var oldW = OwnStations.why;
        try {
            OwnStations.cells = null;
            assertTrue(OwnStations.cellsIn(0, 0, 0, 1000, 300, 1000).isEmpty());
            OwnStations.cells = (a, b, c, d, e, f) -> { throw new IllegalStateException("x"); };
            assertTrue(OwnStations.cellsIn(0, 0, 0, 1, 1, 1).isEmpty());
            OwnStations.why = null;
            assertNotNull(OwnStations.whyNot(0, 0, 0), "no records: refused, said");
            OwnStations.why = (x, y, z) -> { throw new IllegalStateException("boom"); };
            assertTrue(OwnStations.whyNot(0, 0, 0).contains("failed"));
        } finally {
            OwnStations.cells = oldC;
            OwnStations.why = oldW;
        }
    }
}
