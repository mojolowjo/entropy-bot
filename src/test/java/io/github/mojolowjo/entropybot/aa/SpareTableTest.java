package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpareTableTest {
    @Test
    void spareTableMadeOnlyWithoutOneAndWithPlanks() {
        assertTrue(AaLadder.spareTableWanted(false, 4));
        assertFalse(AaLadder.spareTableWanted(false, 3));
        assertFalse(AaLadder.spareTableWanted(true, 40));
    }
}
