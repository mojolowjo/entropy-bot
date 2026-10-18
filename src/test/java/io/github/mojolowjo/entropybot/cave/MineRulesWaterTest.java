package io.github.mojolowjo.entropybot.cave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MineRulesWaterTest {
    @Test
    void waterDownSkip() {
        assertTrue(MineRules.waterDownSkip(true, 60, 45, false));    // dig down from the pool: skip
        assertFalse(MineRules.waterDownSkip(true, 60, 45, true));    // the water touches it: sink to it
        assertFalse(MineRules.waterDownSkip(false, 60, 45, false));  // dry: as before
        assertFalse(MineRules.waterDownSkip(true, 60, 59, false));   // one below: no digging down
        assertFalse(MineRules.waterDownSkip(true, 60, 62, false));   // above
        assertTrue(MineRules.waterDownSkip(true, 60, 58, false));
    }

    @Test
    void waterDigDown() {
        assertTrue(MineRules.waterDigDown(true, true, 59, 60));     // in water, breaking under the feet
        assertFalse(MineRules.waterDigDown(true, true, 60, 60));    // breaking beside: a tunnel, fine
        assertFalse(MineRules.waterDigDown(false, true, 59, 60));   // dry: digging down is fine
        assertFalse(MineRules.waterDigDown(true, false, 59, 60));   // not breaking
    }

    @Test
    void skipHolds() {
        assertFalse(MineRules.skipHolds(null, 100));
        assertTrue(MineRules.skipHolds(200L, 100));
        assertFalse(MineRules.skipHolds(200L, 200));                // expired: a candidate again
    }
}
