package io.github.mojolowjo.entropybot.commands;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WalkEndTest {
    @Test
    void freeSpotPrefersColumnUpThenNearest() {
        // the column up: the first free cell above
        assertArrayEquals(new int[]{0, 62, 0}, WalkEnd.freeSpotFor(0, 60, 0, c -> c[0] == 0 && c[2] == 0 && c[1] == 62 || c[0] == 1 && c[1] == 60));
        // no column: the nearest around (a side cell beats a diagonal)
        assertArrayEquals(new int[]{1, 60, 0}, WalkEnd.freeSpotFor(0, 60, 0, c -> (c[0] == 1 && c[1] == 60 && c[2] == 0) || (c[0] == 1 && c[1] == 61 && c[2] == 1)));
        // a tie prefers higher
        assertArrayEquals(new int[]{0, 59, 1}, WalkEnd.freeSpotFor(0, 60, 0, c -> c[1] == 59 && c[0] == 0 && c[2] == 1));
        assertArrayEquals(new int[]{1, 61, 0}, WalkEnd.freeSpotFor(0, 60, 0, c -> (c[0] == 1 && c[1] == 61 && c[2] == 0) || (c[0] == 1 && c[1] == 59 && c[2] == 0)));
        // nothing within reach
        assertNull(WalkEnd.freeSpotFor(0, 60, 0, c -> Math.abs(c[0]) > 3));
    }

    @Test
    void arrivedWithinTolerance() {
        assertTrue(WalkEnd.arrived(new int[]{10, 79, 40}, new int[]{10, 79, 40}));
        assertTrue(WalkEnd.arrived(new int[]{12, 79, 41}, new int[]{10, 79, 40}));
        assertTrue(WalkEnd.arrived(new int[]{1, 2, 3}, null));
    }

    @Test
    void twoNumberGoalChecksXzOnly() {
        assertTrue(WalkEnd.arrivedXZ(new int[]{-56, 120, -398}, new int[]{-56, -400}));
        assertFalse(WalkEnd.arrivedXZ(new int[]{-56, 40, -9}, new int[]{-56, -400}));
        // 0.33.2: on top of a wall column or drifting in water a few blocks off still counts
        assertTrue(WalkEnd.arrivedXZ(new int[]{-52, 70, -397}, new int[]{-56, -400}));
        assertFalse(WalkEnd.arrivedXZ(new int[]{-50, 70, -400}, new int[]{-56, -400}));
        assertTrue(WalkEnd.shortResultXZ(new int[]{-56, 40, -9}, new int[]{-56, -400}).startsWith("error: couldn't get to -56 -400 (391 blocks away"));
    }

    @Test
    void endedShortIsNotArrived() {
        // live check 1 (0.19.6): sealed pit at 0 75 40, goto 10 79 40 said "ok: arrived"
        assertFalse(WalkEnd.arrived(new int[]{0, 75, 40}, new int[]{10, 79, 40}));
        // live check 2: open pit, goto 40 79 41 ended at 21 78 41
        assertFalse(WalkEnd.arrived(new int[]{21, 78, 41}, new int[]{40, 79, 41}));
        assertFalse(WalkEnd.arrived(new int[]{10, 75, 40}, new int[]{10, 79, 40}));   // straight below counts too
    }

    @Test
    void shortResultWording() {
        assertEquals("error: couldn't get there (19 blocks away, stopped at 21 78 41)",
                WalkEnd.shortResult(new int[]{21, 78, 41}, new int[]{40, 79, 41}));
    }

    @Test
    void retriesAreCapped() {
        assertTrue(WalkEnd.retryShort(0));
        assertTrue(WalkEnd.retryShort(WalkEnd.SHORT_TRIES - 1));
        assertFalse(WalkEnd.retryShort(WalkEnd.SHORT_TRIES));
    }

    @Test
    void aStepBackIsNoWayOut() {
        assertTrue(WalkEnd.stepHelps(9.0, 10.0));
        assertFalse(WalkEnd.stepHelps(11.0, 10.0));                 // back into the pocket it came from
        assertFalse(WalkEnd.stepHelps(Double.MAX_VALUE, 10.0));     // no free step at all
    }

    @Test
    void anOpenPitUnderATreeIsNotUnderground() {
        // live 0.19.7: open 3-deep pit at feet 75 (floor 74), birch leaves at 86 and 90
        int noLeaves = 75;                                          // MOTION_BLOCKING_NO_LEAVES height of the column
        assertTrue(io.github.mojolowjo.entropybot.restore.RestoreRules.openAbove(noLeaves, 75));
        boolean sky = false || io.github.mojolowjo.entropybot.restore.RestoreRules.openAbove(noLeaves, 75);
        assertFalse(io.github.mojolowjo.entropybot.restore.RestoreRules.underground(sky, noLeaves, 75));
        // sealed: stone at 77 over a pit at 75: height 78
        assertFalse(io.github.mojolowjo.entropybot.restore.RestoreRules.openAbove(78, 75));
        assertTrue(io.github.mojolowjo.entropybot.restore.RestoreRules.underground(false, 78, 75));
    }

    @Test
    void escapeTrigger() {
        assertTrue(WalkEnd.escapeTrigger(false, true, true, 0, 3, false));
        assertFalse(WalkEnd.escapeTrigger(true, true, true, 0, 3, false));    // a free step: just step
        assertFalse(WalkEnd.escapeTrigger(false, false, true, 0, 3, false));  // open pit / surface: never dig
        assertFalse(WalkEnd.escapeTrigger(false, true, false, 0, 3, false));  // outside the areas
        assertFalse(WalkEnd.escapeTrigger(false, true, true, 3, 3, false));   // escapes used up
        assertFalse(WalkEnd.escapeTrigger(false, true, true, 0, 3, true));    // a reflex walk
    }
}
