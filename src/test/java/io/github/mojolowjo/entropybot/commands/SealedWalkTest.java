package io.github.mojolowjo.entropybot.commands;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 0.27.4 (live run1 pit): a failed underground walk inside the areas toward a goal above digs a staircase up, twice at most. */
class SealedWalkTest {
    static final int[] PIT = {21, 45, 83};
    static final int[] UP = {21, 68, 83};

    @Test void pitWalkUpFailingStartsAStaircase() {
        assertTrue(SealedWalk.want(true, true, UP, PIT, true, 0));
        assertEquals(24, SealedWalk.steps(UP, PIT), "height difference 23 plus one");
    }

    @Test void surfaceOrOutsideTheAreasIsLeftToTheOldReport() {
        assertFalse(SealedWalk.want(false, true, UP, PIT, true, 0), "not underground");
        assertFalse(SealedWalk.want(true, false, UP, PIT, true, 0), "outside the areas");
    }

    @Test void aGoalLevelOrBelowOrTwoAboveIsNoStaircase() {
        assertFalse(SealedWalk.want(true, true, new int[]{21, 47, 83}, PIT, true, 0), "two above is walkable by the old rules");
        assertFalse(SealedWalk.want(true, true, new int[]{21, 40, 83}, PIT, true, 0), "a goal below");
        assertFalse(SealedWalk.want(true, true, null, PIT, true, 0), "no goal");
    }

    @Test void atMostTwoStaircasesPerGoal() {
        assertTrue(SealedWalk.want(true, true, UP, PIT, true, 1));
        assertFalse(SealedWalk.want(true, true, UP, PIT, true, 2), "the third is refused: the normal report");
    }

    @Test void stepsAreCappedAtForty() {
        assertEquals(40, SealedWalk.steps(new int[]{0, 120, 0}, new int[]{0, 40, 0}));
    }
}
