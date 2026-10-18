package io.github.mojolowjo.entropybot.craft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnTableTest {
    @Test
    void tableKeptOnlyWhenTheNextStepIsATableCraft() {
        java.util.function.Predicate<String> yes = s -> true, no = s -> false;
        assertTrue(OwnTable.keepForNext(false, "craft stone_axe 1", yes));
        assertTrue(OwnTable.keepForNext(false, "  Craft stone_hoe 1 ", yes));
        assertFalse(OwnTable.keepForNext(false, "craft stone_axe 1", no));
        assertFalse(OwnTable.keepForNext(false, "goto 10 64 10", yes));
        assertFalse(OwnTable.keepForNext(false, null, yes));
        assertFalse(OwnTable.keepForNext(true, "craft stone_axe 1", yes));
    }

    @Test
    void nearReachableTableIsUsed() {
        assertEquals(OwnTable.Choice.USE_KNOWN, OwnTable.choose(5.0, 0, true, true));
        assertEquals(OwnTable.Choice.USE_KNOWN, OwnTable.choose(12.0, -2, false, false));
    }

    @Test
    void tableInAPitOrFarUsesTheCarriedOne() {
        assertEquals(OwnTable.Choice.PLACE_CARRIED, OwnTable.choose(6.0, -4, true, true));
        assertEquals(OwnTable.Choice.PLACE_CARRIED, OwnTable.choose(30.0, 0, true, false));
        assertEquals(OwnTable.Choice.PLACE_CARRIED, OwnTable.choose(null, 0, true, false));
    }

    @Test
    void woodMakesOne() {
        assertEquals(OwnTable.Choice.CRAFT_AND_PLACE, OwnTable.choose(null, 0, false, true));
        assertEquals(OwnTable.Choice.CRAFT_AND_PLACE, OwnTable.choose(20.0, 0, false, true));
    }

    @Test
    void nothingWalksOrRefuses() {
        assertEquals(OwnTable.Choice.WALK_FAR, OwnTable.choose(20.0, 0, false, false));
        assertEquals(OwnTable.Choice.NONE, OwnTable.choose(null, 0, false, false));
    }

    @Test
    void texts() {
        assertEquals("used my own table at 1 2 3, taken back", OwnTable.takenBack("1 2 3"));
        assertEquals("my crafting table is still at 1 2 3 - next: dig 1 2 3 1 2 3", OwnTable.leftAt("1 2 3"));
        assertTrue(OwnTable.startNote(OwnTable.Choice.PLACE_CARRIED).contains("take it back"));
        assertEquals("", OwnTable.startNote(OwnTable.Choice.USE_KNOWN));
    }
}
