package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.33.1: a stopped explore left the bot sinking in the ocean; it drowned (2 hp a second). */
class AirReflexTest {
    @Test void idleUnderWaterSwimsUp() { assertTrue(ReflexRules.swimUp(true, 300, 300, false)); }
    @Test void baritoneSwimsWhileAirLasts() { assertFalse(ReflexRules.swimUp(true, 200, 300, true)); }
    @Test void lowAirOverridesWhoeverDrives() { assertTrue(ReflexRules.swimUp(true, 99, 300, true)); }
    @Test void dryNeverSwims() { assertFalse(ReflexRules.swimUp(false, 0, 300, false)); }

    // 0.36.a1: defer to Baritone while it drives, take over below a third, keep it until the air is full
    @Test void idleSwims() { assertEquals(ReflexRules.Air.SWIM, ReflexRules.air(true, true, 300, 300, false, false)); }
    @Test void driverKeepsJumpWhileAirLasts() { assertEquals(ReflexRules.Air.DEFER, ReflexRules.air(true, true, 100, 300, true, false)); }
    @Test void lowAirTakesOver() { assertEquals(ReflexRules.Air.TAKEOVER, ReflexRules.air(true, true, 99, 300, true, false)); }
    @Test void takeoverHoldsAtSurfaceUntilFull() { assertEquals(ReflexRules.Air.TAKEOVER, ReflexRules.air(false, true, 200, 300, true, true)); }
    @Test void takeoverEndsWhenFull() { assertEquals(ReflexRules.Air.NONE, ReflexRules.air(false, true, 300, 300, true, true)); }
    @Test void takeoverEndsOutOfWater() { assertEquals(ReflexRules.Air.NONE, ReflexRules.air(false, false, 200, 300, true, true)); }
    @Test void dryDoesNothing() { assertEquals(ReflexRules.Air.NONE, ReflexRules.air(false, false, 0, 300, false, false)); }
}
