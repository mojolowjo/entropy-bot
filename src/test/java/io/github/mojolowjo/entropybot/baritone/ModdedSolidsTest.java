package io.github.mojolowjo.entropybot.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModdedSolidsTest {
    @Test
    void anInfusionPedestalIsNotWalkThrough() {
        assertTrue(ModdedSolids.solid("mysticalagriculture", false, false, 1.0));
    }

    @Test
    void vanillaBlocksAreLeftToBaritone() {
        assertFalse(ModdedSolids.solid("minecraft", false, false, 1.0));
    }

    @Test
    void lowEmptyAndOpeningModdedBlocksStayWalkThrough() {
        assertFalse(ModdedSolids.solid("somemod", false, false, 0.0625));   // a modded carpet
        assertFalse(ModdedSolids.solid("mysticalagriculture", false, true, 0)); // a crop: no collision
        assertFalse(ModdedSolids.solid("somemod", true, false, 1.0));       // a modded door
        assertFalse(ModdedSolids.solid(null, false, false, 1.0));
    }
}
