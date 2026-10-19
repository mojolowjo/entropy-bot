package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.craft.OwnTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.36.a9: the pickup of the furnace "smelt ... here" put down (mining-policy Phase 4 open item). */
class OwnFurnaceTest {
    @Test
    void thePickupIsArmedOnlyByOurClickAndExactlyThatBlock() {
        assertTrue(PlaceRules.armsStation(true, PlaceRules.FURNACE_ID, PlaceRules.FURNACE_ID));
        assertFalse(PlaceRules.armsStation(false, PlaceRules.FURNACE_ID, PlaceRules.FURNACE_ID), "the camp's furnace was there: not ours");
        assertFalse(PlaceRules.armsStation(true, "minecraft:blast_furnace", PlaceRules.FURNACE_ID));
        assertFalse(PlaceRules.armsStation(true, null, PlaceRules.FURNACE_ID));
        assertTrue(PlaceRules.armsStation(true, PlaceRules.TABLE_ID, PlaceRules.TABLE_ID), "the table as before");
    }

    @Test
    void aPickupMayBreakOnlyWhileArmedAndTheBlockIsExactlyTheOne() {
        assertTrue(PlaceRules.pickupMayBreak(true, PlaceRules.FURNACE_ID, PlaceRules.FURNACE_ID));
        assertFalse(PlaceRules.pickupMayBreak(false, PlaceRules.FURNACE_ID, PlaceRules.FURNACE_ID), "disarmed (a camp furnace)");
        assertFalse(PlaceRules.pickupMayBreak(true, PlaceRules.TABLE_ID, PlaceRules.FURNACE_ID), "a table is not the furnace");
        assertFalse(PlaceRules.pickupMayBreak(true, PlaceRules.FURNACE_ID, PlaceRules.TABLE_ID), "a furnace is not the table");
        assertTrue(PlaceRules.pickupMayBreak(true, PlaceRules.TABLE_ID), "the table's own two-argument rule is unchanged");
    }

    @Test
    void aStepWithoutAnIdIsTheTablePickup() {
        assertEquals(PlaceRules.TABLE_ID, PlaceRules.pickupBlock(null));
        assertEquals(PlaceRules.FURNACE_ID, PlaceRules.pickupBlock(PlaceRules.FURNACE_ID));
        assertEquals("crafting table", PlaceRules.pickupName(PlaceRules.TABLE_ID));
        assertEquals("furnace", PlaceRules.pickupName(PlaceRules.FURNACE_ID));
    }

    @Test
    void aFurnaceIsPickedUpOnlyWhenKnownEmpty() {
        // the bot emptied it (not kept as a chest note) at/after the placement: empty
        assertEquals(0, OwnStations.contentsKnown(null, 0, 2000L, 1000));
        // emptied before it was placed (an older cell): unknown
        assertNull(OwnStations.contentsKnown(null, 0, 500L, 1000));
        assertNull(OwnStations.contentsKnown(null, 0, null, 1000));
        // a note read after the emptying says it holds items again: the newer read wins
        assertEquals(3, OwnStations.contentsKnown(3, 3000, 2000L, 1000));
        // the emptying is newer than the note: empty
        assertEquals(0, OwnStations.contentsKnown(3, 1500, 2000L, 1000));
        assertEquals(5, OwnStations.contentsKnown(5, 1500, null, 1000));
        // wired into the existing rule: empty -> may be picked up, unknown -> refused
        assertNull(OwnStations.pickUp("minecraft:furnace", "furnace", false, "furnace", OwnStations.contentsKnown(null, 0, 2000L, 1000), 1, 2, 3));
        assertTrue(OwnStations.pickUp("minecraft:furnace", "furnace", false, "furnace", OwnStations.contentsKnown(null, 0, null, 1000), 1, 2, 3).contains("may hold items"));
    }

    @Test
    void theNotesNameTheFurnaceAndTheNextStep() {
        assertTrue(OwnTable.furnacePlacedHere("1 2 3").contains("take it back"));
        assertTrue(OwnTable.furnaceTakenBack("1 2 3").contains("taken back"));
        assertTrue(OwnTable.furnaceLeftAt("1 2 3").contains("still at 1 2 3"));
        assertTrue(OwnTable.furnaceNotEmpty("1 2 3", 4).contains("4 items"));
        assertTrue(PlaceRules.furnacePickupLabel(new Pos(1, 2, 3)).contains("furnace"));
    }
}
