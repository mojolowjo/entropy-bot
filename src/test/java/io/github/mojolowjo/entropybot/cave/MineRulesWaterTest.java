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
}
