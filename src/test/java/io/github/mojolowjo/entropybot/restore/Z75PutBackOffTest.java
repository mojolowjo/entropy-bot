package io.github.mojolowjo.entropybot.restore;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z75 put-back off (owner 2026-10-25 19:40): Tuning restore.putBack - 0 (the default): a job whose walk broke blocks
 * ends with no put-back run, the end line says how many were left; 1: as before (restore mode auto puts them back).
 */
class Z75PutBackOffTest {
    @AfterEach
    void clear() { Tuning.reset(); }

    @Test
    void offByDefault_noRunAfterAWalkThatBrokeBlocks_theLineSaid() {
        assertEquals(0, Tuning.get("restore.putBack"));
        assertFalse(RestoreRules.putBackOn());
        assertFalse(RestoreRules.startsAfterJob("auto", 3), "a walk broke 3 blocks: no put-back run");
        assertEquals("put-back off (restore.putBack 0): 3 broken blocks left", RestoreRules.putBackOffNote(3));
        assertEquals("put-back off (restore.putBack 0): 1 broken block left", RestoreRules.putBackOffNote(1));
        assertNull(RestoreRules.putBackOffNote(0), "nothing broken: nothing to say");
    }

    @Test
    void onIsTodaysBehaviour() {
        Tuning.set("restore.putBack", 1);
        assertTrue(RestoreRules.putBackOn());
        assertTrue(RestoreRules.startsAfterJob("auto", 3), "mode auto with broken blocks: the run starts, as before");
        assertFalse(RestoreRules.startsAfterJob("manual", 3), "manual: restore now puts them back, as before");
        assertFalse(RestoreRules.startsAfterJob("auto", 0), "nothing broken: no run, as before");
        assertNull(RestoreRules.putBackOffNote(3), "on: the old line (N blocks to put back) stands");
    }
}
