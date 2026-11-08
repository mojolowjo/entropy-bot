package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** job-walkend (run JBD 09:22:47): a walled-in goal from inside the bot's own shaft is a dig, not a walk. */
class WalkEndShaftDigTest {
    static final String WHY = "error: couldn't get there (goal is walled in at -129 63 -26; stopped 2 blocks away)";

    @Test
    void walledInFromAShaftIsADig() {
        assertTrue(WalkEnd.shaftDig(WHY, 3, 0), "rock over the head");
        assertTrue(WalkEnd.shaftDig(WHY, 0, 4), "a hole round the feet");
        assertFalse(WalkEnd.shaftDig(WHY, 0, 0), "on open ground: not a shaft");
        assertFalse(WalkEnd.shaftDig("error: couldn't get there (5 blocks away, stopped at 1 2 3)", 3, 4), "not walled in");
        assertFalse(WalkEnd.shaftDig(null, 3, 4));
    }
}
