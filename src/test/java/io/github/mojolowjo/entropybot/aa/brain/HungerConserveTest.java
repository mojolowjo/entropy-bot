package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HungerConserveTest {
    @Test void decision() {
        assertTrue(HungerConserve.decide(true, true, 5, 30));
        assertFalse(HungerConserve.decide(true, false, 5, 30), "a source is known");
        assertFalse(HungerConserve.decide(true, true, 30, 30), "the bag covers the need");
        assertFalse(HungerConserve.decide(false, true, 5, 30), "creative: no drain");
        assertTrue(HungerConserve.covered(40, 30));
    }


}
