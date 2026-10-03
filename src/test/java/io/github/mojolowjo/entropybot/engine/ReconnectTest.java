package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;

class ReconnectTest {
    @Test
    void waitsOneFiveAndFifteenMinutesThenStops() {
        ArrayDeque<Long> recent = new ArrayDeque<>();
        assertFalse(ReconnectRules.due(1199, 0, recent, 0));
        assertTrue(ReconnectRules.due(1200, 0, recent, 0));
        assertFalse(ReconnectRules.due(5999, 1, recent, 0));
        assertTrue(ReconnectRules.due(6000, 1, recent, 0));
        assertTrue(ReconnectRules.due(18000, 2, recent, 0));
        assertFalse(ReconnectRules.due(100000, 3, recent, 0), "three tries, then it leaves it to the owner");
    }

    @Test
    void atMostThreeAnHour() {
        ArrayDeque<Long> recent = new ArrayDeque<>();
        recent.add(1_000L);
        recent.add(2_000L);
        recent.add(3_000L);
        assertFalse(ReconnectRules.due(1200, 0, recent, 10_000L));
        assertTrue(ReconnectRules.due(1200, 0, recent, 3_700_000L), "an hour later the old ones no longer count");
    }
}
