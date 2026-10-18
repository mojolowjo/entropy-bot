package io.github.mojolowjo.entropybot.aa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AaSwimTest {
    @Test void holdsForLowAirDanger() { assertTrue(AaSwim.hold(true, true, 50, 300, false, false, false)); }
    @Test void keepsGoingUntilFull() {
        assertTrue(AaSwim.hold(true, true, 200, 300, false, false, true));
        assertFalse(AaSwim.hold(true, true, 200, 300, false, false, false));
        assertFalse(AaSwim.hold(true, true, 300, 300, false, false, true));
    }
    @Test void releasesForOtherDanger() { assertFalse(AaSwim.hold(true, true, 50, 300, false, true, true)); }
    @Test void releasesWhenReflexHoldsOrDangerEndsOrOutOfWater() {
        assertFalse(AaSwim.hold(true, true, 50, 300, true, false, true));
        assertFalse(AaSwim.hold(false, true, 50, 300, false, false, true));
        assertFalse(AaSwim.hold(true, false, 50, 300, false, false, true));
    }
}
