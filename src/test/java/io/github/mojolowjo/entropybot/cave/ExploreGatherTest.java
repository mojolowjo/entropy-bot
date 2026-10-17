package io.github.mojolowjo.entropybot.cave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.33.1: explore gathers only as a side effect of the walk (a 1-minute explore once chopped and explored 0 chunks). */
class ExploreGatherTest {
    @Test void noGatherBeforeWalking() {
        assertFalse(ExploreRules.gatherAllowed(40, 1160, 0, 0, 0));
        assertFalse(ExploreRules.gatherAllowed(200, 1000, 0, 1, 0));
        assertTrue(ExploreRules.gatherAllowed(300, 900, 0, 2, 0));
    }

    @Test void needsNewChunksSinceTheLastGather() {
        assertFalse(ExploreRules.gatherAllowed(3000, 3000, 500, 5, 4));
        assertTrue(ExploreRules.gatherAllowed(3000, 3000, 500, 6, 4));
    }

    @Test void atMostAThirdOfTheRun() {
        assertFalse(ExploreRules.gatherAllowed(1200, 3000, 401, 10, 2));
        assertTrue(ExploreRules.gatherAllowed(1200, 3000, 400, 10, 2));
    }

    @Test void notNearTheEnd() {
        assertFalse(ExploreRules.gatherAllowed(3000, 599, 0, 10, 0));
    }
}
