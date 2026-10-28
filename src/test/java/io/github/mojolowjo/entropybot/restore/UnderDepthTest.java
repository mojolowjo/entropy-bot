package io.github.mojolowjo.entropybot.restore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 0.34.3: the one under-ground rule (surface = top non-leaf block, so a canopy never counts). */
class UnderDepthTest {
    @Test
    void deepInACave() {
        assertEquals(28, RestoreRules.rockOverHead(false, 67, 40));
    }

    @Test
    void skyOrOpenAboveIsNotUnderGround() {
        assertEquals(0, RestoreRules.rockOverHead(true, 67, 40), "sky seen");
        assertEquals(0, RestoreRules.rockOverHead(false, 64, 64), "standing on the surface (leaves above are not the surface)");
        assertEquals(0, RestoreRules.rockOverHead(false, 65, 64), "open just above");
    }

    @Test
    void aLowRoofIsNotUnderGround() {
        // feet 63, a roof block at 65 or 66: under 3 below that surface, so not under ground
        assertEquals(0, RestoreRules.rockOverHead(false, 65, 63));
        assertEquals(0, RestoreRules.rockOverHead(false, 66, 64));
        assertEquals(4, RestoreRules.rockOverHead(false, 66, 63), "3 below the top block: under ground, surface 4 up");
    }
}
