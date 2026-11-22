package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Test tooling: the game-play clock is the wall clock by default and 50 ms a client tick with clock.gameTicks 1. */
class GameClockTest {
    @AfterEach
    void clear() {
        GameClock.testWall(null);
        Tuning.reset();
    }

    @Test
    void theWallClockByDefault_ticksWhenSwitched_neverBackwards() {
        long[] wall = {1_000_000};
        GameClock.testWall(() -> wall[0]);
        GameClock.tick();
        assertFalse(GameClock.ticking());
        wall[0] += 700;
        assertEquals(1_000_700, GameClock.ms(), "off: the wall clock");

        Tuning.set("clock.gameTicks", 1);
        GameClock.tick();                                  // the switch, and its first tick
        assertTrue(GameClock.ticking());
        assertEquals(1_000_700 + 50, GameClock.ms());
        for (int i = 0; i < 199; i++) GameClock.tick();   // 200 ticks in all: 10 s of game play
        wall[0] += 1000;                                   // in one wall second (tick rate 200)
        assertEquals(1_000_700 + 10_000, GameClock.ms(), "10 s of game play, whatever the wall says");

        Tuning.set("clock.gameTicks", 0);
        GameClock.tick();
        assertFalse(GameClock.ticking());
        assertEquals(1_000_700 + 10_000, GameClock.ms(), "switched off: continuous, the 9 s gained are kept");
        wall[0] += 500;
        assertEquals(1_000_700 + 10_500, GameClock.ms(), "and the wall clock's pace again");
        assertTrue(GameClock.line().contains("kept from a ticking spell"), GameClock.line());
    }
}
