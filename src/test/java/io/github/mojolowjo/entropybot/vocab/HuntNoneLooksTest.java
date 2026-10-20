package io.github.mojolowjo.entropybot.vocab;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** aa395: three "I see no cow" looks in a row end the hunt step. */
class HuntNoneLooksTest {
    @Test
    void threeLooksEnd() {
        assertFalse(HuntRules.noneEnds(2));
        assertTrue(HuntRules.noneEnds(HuntRules.NONE_LOOKS));
    }
}
