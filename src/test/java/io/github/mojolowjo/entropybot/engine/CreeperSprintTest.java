package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.2: the owner's creeper heuristic as a state machine. */
class CreeperSprintTest {
    @Test
    void sprintingAwayKeepsGoing() {
        assertTrue(CreeperSprint.sprintingAway(true, 0.28, 0, 1, 0.2));
        assertFalse(CreeperSprint.sprintingAway(false, 0.28, 0, 1, 0), "walking, not sprinting");
        assertFalse(CreeperSprint.sprintingAway(true, -0.28, 0, 1, 0), "sprinting towards it");
        assertFalse(CreeperSprint.sprintingAway(true, 0.05, 0, 1, 0), "barely moving");
        CreeperSprint s = new CreeperSprint();
        assertEquals(CreeperSprint.Act.SPRINT, s.start(0, 7, true, true, 0.28, 0, 0, 1));
        assertEquals(1, s.dirX(), 1e-9, "its own heading, not the grid's");
        assertEquals(0, s.dirZ(), 1e-9);
        assertTrue(s.how().contains("kept going"));
    }

    @Test
    void standingIsHitThenSprint() {
        CreeperSprint s = new CreeperSprint();
        assertEquals(CreeperSprint.Act.HIT, s.start(10, 7, false, true, 0, 0, 0, -1));
        assertEquals(0, s.dirX(), 1e-9);
        assertEquals(-1, s.dirZ(), 1e-9, "the grid's heading");
        assertEquals(CreeperSprint.Act.SPRINT, s.step(11, 3));
        assertEquals(CreeperSprint.Act.SPRINT, s.step(12, 5));
        assertEquals(CreeperSprint.Act.DONE, s.step(13, 7.1));
        assertFalse(s.active());
        assertTrue(s.endWhy().startsWith("got 7.1"));
    }

    @Test
    void outOfReachOrFleeModeJustSprints() {
        CreeperSprint s = new CreeperSprint();
        assertEquals(CreeperSprint.Act.SPRINT, s.start(0, 7, false, false, 0, 0, 1, 0));
        assertEquals("sprint", s.how());
    }

    @Test
    void endsWhenGoneOrAfterTheLimit() {
        CreeperSprint s = new CreeperSprint();
        s.start(0, 7, false, false, 0, 0, 1, 0);
        assertEquals(CreeperSprint.Act.DONE, s.step(1, Double.NaN));
        assertEquals("it is gone", s.endWhy());
        s.start(100, 7, false, false, 0, 0, 1, 0);
        assertEquals(CreeperSprint.Act.SPRINT, s.step(150, 4));
        assertEquals(CreeperSprint.Act.DONE, s.step(100 + CreeperSprint.MAX_TICKS, 4));
        assertEquals(CreeperSprint.Act.DONE, s.step(300, 4), "inactive stays done");
    }

    @Test
    void steerAndAbort() {
        CreeperSprint s = new CreeperSprint();
        s.start(0, 7, false, false, 0, 0, 1, 0);
        s.steer(0, 1);
        assertEquals(1, s.dirZ(), 1e-9);
        s.abort("self-defence off");
        assertFalse(s.active());
        assertEquals("self-defence off", s.endWhy());
    }

    @Test
    void aChasingCreeperIsRunFurtherAndWatched() {
        CreeperSprint s = new CreeperSprint();
        s.start(0, 7, false, true, 0, 0, 1, 0);
        assertEquals(7, s.safe());
        assertEquals(CreeperSprint.Act.DONE, s.step(10, 7.2));
        assertTrue(s.watching(20, 7), "hold still and watch it");
        assertFalse(s.watching(20, 8), "another creeper");
        assertFalse(s.watching(10 + CreeperSprint.WATCH_TICKS, 7), "watched long enough");
        s.start(30, 7, false, false, 0, 0, 1, 0);         // it came back within 3 s
        assertEquals(12, s.safe());
        assertEquals(CreeperSprint.Act.SPRINT, s.step(40, 8), "7 isn't far enough now");
        assertEquals(CreeperSprint.Act.DONE, s.step(50, 12.1));
        s.start(60, 7, false, false, 0, 0, 1, 0);
        assertEquals(17, s.safe());
        s.step(70, 17.5);
        s.start(70 + CreeperSprint.CHASE_TICKS, 7, false, false, 0, 0, 1, 0);
        assertEquals(7, s.safe(), "a while later: back to 7");
    }

    @Test
    void duelAfterTheSprint() {
        assertTrue(CreeperSprint.duelAfter(CreeperRules.Mode.BOW, false), "bow: always re-evaluated as a duel");
        assertFalse(CreeperSprint.duelAfter(CreeperRules.Mode.MELEE, false), "melee = hit-then-sprint by default");
        assertTrue(CreeperSprint.duelAfter(CreeperRules.Mode.MELEE, true), "melee with a big margin");
        assertFalse(CreeperSprint.duelAfter(CreeperRules.Mode.FLEE, true));
    }
}
