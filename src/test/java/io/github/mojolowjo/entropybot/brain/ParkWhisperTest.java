package io.github.mojolowjo.entropybot.brain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 0.24.3: a parked need whispers at most once an hour. */
class ParkWhisperTest {
    @Test void oncePerHour() {
        assertTrue(Brain.parkWhisperDue(0, 1000));
        long t = 10_000_000L;
        assertFalse(Brain.parkWhisperDue(t, t + 30 * 60_000L));
        assertFalse(Brain.parkWhisperDue(t, t + 59 * 60_000L));
        assertTrue(Brain.parkWhisperDue(t, t + 60 * 60_000L));
    }
}
