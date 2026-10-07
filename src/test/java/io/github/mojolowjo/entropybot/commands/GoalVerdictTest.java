package io.github.mojolowjo.entropybot.commands;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 0.23.4 (movement matrix run 9a): the arrival tolerance no longer hides a goal inside a wall or walled in. */
class GoalVerdictTest {
    static final int[] END = {120, -60, 62};

    @Test
    void openGoalKeepsTheTolerance() {
        assertNull(WalkEnd.goalVerdict(new int[]{118, -60, 62}, END, WalkEnd.GoalCell.OPEN));
        assertNull(WalkEnd.goalVerdict(END, END, WalkEnd.GoalCell.OPEN));
    }

    @Test
    void walledInGoalStoppedShortIsAFailure() {
        // run 9a: END in a 1x1 stone cell, the bot stopped 2 blocks off and used to answer "arrived near"
        String v = WalkEnd.goalVerdict(new int[]{118, -60, 62}, END, new WalkEnd.GoalCell(null, true, null));
        assertEquals("error: couldn't get there (goal is walled in at 120 -60 62; stopped 2 blocks away)", v);
    }

    @Test
    void goalInsideAWallStoppedShort() {
        String v = WalkEnd.goalVerdict(new int[]{117, -60, 62}, END, new WalkEnd.GoalCell("stone", false, new int[]{119, -60, 62}));
        assertEquals("error: couldn't get there (goal is inside stone at 120 -60 62; stopped 3 blocks away)", v);
        assertFalse(WalkEnd.goalVerdict(new int[]{118, -60, 62}, END, new WalkEnd.GoalCell("stone", false, null)).startsWith("ok"));
    }

    @Test
    void goalInsideAWallSnapsToAFreeCellWithinOne() {
        String v = WalkEnd.goalVerdict(new int[]{119, -60, 62}, END, new WalkEnd.GoalCell("stone", false, new int[]{119, -60, 62}));
        assertEquals("ok: arrived next to 120 -60 62 (the spot is inside stone)", v);
        // the free cell is on the far side: the bot is not at it
        assertTrue(WalkEnd.goalVerdict(new int[]{118, -60, 63}, END, new WalkEnd.GoalCell("stone", false, new int[]{121, -60, 62})).startsWith("error"));
    }

    @Test
    void walledInButReachedIsArrived() {
        assertNull(WalkEnd.goalVerdict(new int[]{120, -60, 62}, END, new WalkEnd.GoalCell(null, true, null)));
    }
}
