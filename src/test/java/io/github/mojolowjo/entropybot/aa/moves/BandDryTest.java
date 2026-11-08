package io.github.mojolowjo.entropybot.aa.moves;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** job-digpace (run JAT: the band leg dug from a lake): a wet start column steps to a dry cell first; the wet price. */
class BandDryTest {
    @Test void wetStartColumnWalksToTheDryCellFirst() {
        boolean dry = BandDry.dry(false, true, List.of(true, true, false, false));      // afloat, water under the feet
        assertFalse(dry);
        assertEquals(BandDry.Act.STEP_DRY, BandDry.act(dry, 3));
        assertEquals(BandDry.Act.DIG_WET, BandDry.act(dry, 0));
    }

    @Test void fluidInTheFirstLevelsIsWetAndDeeperIsNot() {
        assertFalse(BandDry.dry(true, false, List.of(false, false, false, true)));     // the 4th cell, band.dryLevels 4
        assertTrue(BandDry.dry(true, false, List.of(false, false, false, false, true)));
        assertEquals(BandDry.Act.DIG, BandDry.act(true, 0));
    }

    @Test void theWetDigIsChargedTheUnderWaterBreakFactor() {
        assertEquals(100.0, BandDry.price(20.0, true), 1e-9);
        assertEquals(20.0, BandDry.price(20.0, false), 1e-9);
    }
}
