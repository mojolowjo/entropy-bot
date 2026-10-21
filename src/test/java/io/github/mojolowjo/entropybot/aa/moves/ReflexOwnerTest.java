package io.github.mojolowjo.entropybot.aa.moves;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.39.s6 review: reflex ownership of Baritone's breaking/placing is per car and goes stale for a dropped car. */
class ReflexOwnerTest {
    final Object a = new Object(), b = new Object();

    @AfterEach
    void clear() {
        ReflexOwners.release(a);
        ReflexOwners.release(b);
    }

    @Test
    void oneCarsRestoreKeepsTheOthersOwnership() {
        long now = 1_000_000;
        ReflexOwners.beat(a, now);
        ReflexOwners.beat(b, now);
        ReflexOwners.release(a);
        assertTrue(ReflexOwners.any(now + 100));
        ReflexOwners.release(b);
        assertFalse(ReflexOwners.any(now + 100));
    }

    @Test
    void aDroppedCarGoesStale() {
        long now = 1_000_000;
        ReflexOwners.beat(a, now);
        assertTrue(ReflexOwners.any(now + 500));
        assertFalse(ReflexOwners.any(now + 60_000), "no heartbeat: the SafetyNet may put the settings back");
    }
}
