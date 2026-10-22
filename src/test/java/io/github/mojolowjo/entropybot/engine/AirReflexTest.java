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
    // reflex choosers: the swim-up goal is "the head in open air" (Baritone picks the cell): water below y 63, air from 63,
    // a stone roof at y 60 except one hole
    static io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.Cells sea(boolean roof, int hole) {
        return new io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.Cells() {
            @Override public boolean open(int x, int y, int z) { return !(roof && y == 60 && !(x == hole && z == 0)); }
            @Override public boolean water(int x, int y, int z) { return y < 63 && open(x, y, z); }
        };
    }
    @Test void underWaterIsNotTheGoal() { assertFalse(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.breathes(sea(false, 0), 0, 55, 0)); }
    @Test void feetInTheTopWaterCellBreathe() { assertTrue(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.breathes(sea(false, 0), 3, 62, 4)); }
    @Test void anAirPocketUnderTheRoofIsNotAirWhenTheRoofIsTheHead() { assertFalse(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.breathes(sea(true, 2), 0, 59, 0)); }
    @Test void waterGoalIsFeetInWater() {
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.wet(sea(false, 0), 0, 62, 0));
        assertFalse(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.wet(sea(false, 0), 0, 63, 0));
    }    @Test void dryDoesNothing() { assertEquals(ReflexRules.Air.NONE, ReflexRules.air(false, false, 0, 300, false, false)); }
}
