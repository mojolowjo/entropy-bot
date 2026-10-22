package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaDesires;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** An infeasible desire is parked: pure wool-source logic. */
class ParkedDesireTest {
    @Test
    void woolSourceNeedsWoolStringOrSheep() {
        assertNotNull(AaDesires.woolSource(0, 0, false));
        assertTrue(AaDesires.woolSource(0, 0, false).contains("0/3 wool"));
        assertNull(AaDesires.woolSource(3, 0, false));
        assertNull(AaDesires.woolSource(0, 12, false));
        assertNotNull(AaDesires.woolSource(0, 11, false));
        assertNull(AaDesires.woolSource(0, 0, true));
    }
}
