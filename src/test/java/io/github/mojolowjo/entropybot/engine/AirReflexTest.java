package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.33.1: a stopped explore left the bot sinking in the ocean; it drowned (2 hp a second). */
class AirReflexTest {
    @Test void idleUnderWaterSwimsUp() { assertTrue(ReflexRules.swimUp(true, 300, 300, false)); }
    @Test void baritoneSwimsWhileAirLasts() { assertFalse(ReflexRules.swimUp(true, 200, 300, true)); }
    @Test void lowAirOverridesWhoeverDrives() { assertTrue(ReflexRules.swimUp(true, 99, 300, true)); }
    @Test void dryNeverSwims() { assertFalse(ReflexRules.swimUp(false, 0, 300, false)); }
}
