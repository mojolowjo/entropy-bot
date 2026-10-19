package io.github.mojolowjo.entropybot.gather;

import io.github.mojolowjo.entropybot.cave.ExploreRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** aa385: the same failed gather step 3x in a row ends the gather; explore's leash. */
class RepeatFailTest {
    @Test
    void threeSameEndsInARowEndTheGather() {
        RepeatFail r = new RepeatFail();
        assertFalse(r.note("hunt 8 cow", "stopped: hunt: 2/8 - I see no cow", 0));
        assertFalse(r.note("hunt 8 cow", "stopped: hunt: 3/8 - I see no cow", 1200));      // 60 s per hunt step
        assertTrue(r.note("hunt 8 cow", "stopped: hunt: 3/8 - I see no cow", 2400));
        r.reset();
        assertFalse(r.note("hunt 8 cow", "x", 0));
        assertFalse(r.note("hunt 8 cow", "x", 3000), "more than 120 s after the last: afresh");
        assertFalse(r.note("cut 4", "x", 2100), "another step: afresh");
    }

    @Test
    void leash() {
        assertTrue(ExploreRules.nearAreas((x, z) -> x < 0, 60, 0, 64));
        assertFalse(ExploreRules.nearAreas((x, z) -> x < 0, 70, 0, 64));
    }
}
