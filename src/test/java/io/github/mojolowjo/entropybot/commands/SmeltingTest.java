package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND3 2: the smelt verb's nowait form (load and leave). */
class SmeltingTest {
    @Test
    void nowaitAndHereInAnyOrder() {
        assertEquals(new SmeltTexts.Form("iron_ingot 5", false, true), SmeltTexts.form("iron_ingot 5 nowait"));
        assertEquals(new SmeltTexts.Form("iron_ingot 5", true, true), SmeltTexts.form("iron_ingot 5 here NOWAIT"));
        assertEquals(new SmeltTexts.Form("iron_ingot 5", true, false), SmeltTexts.form("iron_ingot 5 here"));
        assertEquals(new SmeltTexts.Form("iron_ingot", false, false), SmeltTexts.form(" iron_ingot "));
    }

    @Test
    void nowaitLabelSaysItLeaves() {
        assertTrue(SmeltTexts.nowaitLabel(false, "5 iron_ingot").contains("nowait"));
    }
}
