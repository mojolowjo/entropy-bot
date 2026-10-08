package io.github.mojolowjo.entropybot.camp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.24.3: bootstrap's free spots in a 3x3 pit, on open ground and among leaves. */
class FreeSpotsTest {
    static final int[] F = {0, 66, 0}, EAST = {1, 0};

    static boolean inPit(int[] c) { return Math.abs(c[0]) <= 1 && Math.abs(c[2]) <= 1; }

    @Test void pitHasRingOneSpots() {
        // a 3x3 pit: only the 8 cells around the bot are air (the walls are stone), 2 air above, never the bot's own cell
        List<int[]> s = FreeSpots.find(F, EAST, c -> inPit(c) && !(c[0] == 0 && c[2] == 0), c -> inPit(c));
        assertEquals(8, s.size());
        for (int[] c : s) assertFalse(c[0] == 0 && c[2] == 0);
        assertTrue(s.get(0)[0] <= 0, "the quarry's side (east) comes last");
        assertEquals(1, s.get(7)[0]);
    }

    @Test void openGroundPrefersRingTwoBehind() {
        List<int[]> s = FreeSpots.find(F, EAST, c -> !(c[0] == 0 && c[2] == 0), c -> true);
        assertEquals(2, Math.max(Math.abs(s.get(0)[0]), Math.abs(s.get(0)[2])));
        assertEquals(-2, s.get(0)[0]);
    }

    @Test void noAirAboveIsNoSpot() {
        assertTrue(FreeSpots.find(F, EAST, c -> true, c -> false).isEmpty());
    }

    @Test void leavesAreCleared() {
        // dense leaves all around: no free spot, three cells to clear in ring 1
        assertTrue(FreeSpots.find(F, EAST, c -> false, c -> false).isEmpty());
        List<int[]> clear = FreeSpots.toClear(F, c -> true, 3);
        assertEquals(3, clear.size());
        for (int[] c : clear) assertEquals(1, Math.max(Math.abs(c[0]), Math.abs(c[2])));
    }
}
