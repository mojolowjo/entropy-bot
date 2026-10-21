package io.github.mojolowjo.entropybot.vocab;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KelpRulesTest {
    @Test
    void parsesTheForms() {
        assertEquals(new KelpRules.Parsed(8, false), KelpRules.parse("kelp"));
        assertEquals(new KelpRules.Parsed(5, false), KelpRules.parse("kelp 5"));
        assertEquals(new KelpRules.Parsed(5, true), KelpRules.parse("KELP 5 cook"));
        assertEquals(new KelpRules.Parsed(8, true), KelpRules.parse("kelp cook"));
        assertNull(KelpRules.parse("kelp 0"));
        assertNull(KelpRules.parse("kelp 5 3m"), "the time limit is the steps machine's, no minutes in the verb");
        assertNull(KelpRules.parse("kelp cook 5"));
        assertNull(KelpRules.parse("cobblestone 5"));
        assertTrue(KelpRules.isKelp("kelp 3"));
        assertFalse(KelpRules.isKelp("kelp_block 3"));
        assertFalse(KelpRules.isKelp(""));
    }
}