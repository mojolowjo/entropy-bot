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
    // backbone step 4: the swim-up's GoalBlock cell (water below y 63, air from 63; a stone roof at x 0 z 0 y 66 when asked)
    static ReflexRules.Cells sea(boolean roof, int roofHole) {
        return (x, y, z) -> {
            if (roof && y == 60 && !(x == roofHole && z == 0)) return ReflexRules.Cell.SOLID;
            return y < 63 ? ReflexRules.Cell.WATER : ReflexRules.Cell.OPEN;
        };
    }
    @Test void surfaceStraightUp() { assertArrayEquals(new int[]{0, 62, 0}, ReflexRules.surface(0, 55, 0, sea(false, 0))); }
    @Test void aRoofSendsItToTheNearestOpenColumn() { assertArrayEquals(new int[]{2, 62, 0}, ReflexRules.surface(0, 58, 0, sea(true, 2))); }
    @Test void noOpeningInReachIsNull() { assertNull(ReflexRules.surface(0, 58, 0, sea(true, 9))); }
    @Test void tooDeepIsNull() { assertNull(ReflexRules.surface(0, 62 - ReflexRules.SURFACE_UP - 1, 0, sea(false, 0))); }
    // 0.39.m2: a stalled column is skipped; the jump stays while the air is low unless the bot rises
    @Test void aBadColumnIsSkipped() {
        int[] s = ReflexRules.surface(0, 55, 0, sea(false, 0), (x, z) -> x == 0 && z == 0);
        assertEquals(62, s[1]);
        assertEquals(1, Math.abs(s[0]) + Math.abs(s[2]), "the nearest good column");
    }
    @Test void noPathHoldsJump() { assertTrue(ReflexRules.airJump(true, false, 300, 300, false)); }
    @Test void aPathWithAirLeftLetsBaritoneSwim() { assertFalse(ReflexRules.airJump(true, true, 200, 300, false)); }
    @Test void lowAirAndNotRisingHoldsJumpDespiteThePath() { assertTrue(ReflexRules.airJump(true, true, 90, 300, false)); }
    @Test void lowAirButRisingLetsBaritoneSwim() { assertFalse(ReflexRules.airJump(true, true, 90, 300, true)); }
    @Test void headOutNoJump() { assertFalse(ReflexRules.airJump(false, false, 0, 300, false)); }
    @Test void dryDoesNothing() { assertEquals(ReflexRules.Air.NONE, ReflexRules.air(false, false, 0, 300, false, false)); }
}
