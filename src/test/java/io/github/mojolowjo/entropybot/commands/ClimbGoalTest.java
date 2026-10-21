package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.clear.ClearBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Builder placements: the floor fill's climb-out goal keeps to the footprint and the fence. */
class ClimbGoalTest {
    @Test
    void onlyInTheFootprintAtTheLevelAndWhereTheFenceAllows() {
        ClimbRule g = new ClimbRule(61, new ClearBox(0, 60, 0, 4, 64, 4), (x, y, z) -> !(x == 2 && z == 2));
        assertTrue(g.in(1, 61, 1));
        assertTrue(g.in(1, 62, 1), "higher is fine");
        assertFalse(g.in(1, 60, 1), "still below");
        assertFalse(g.in(9, 61, 1), "outside the footprint (the old stand check kept it in)");
        assertFalse(g.in(2, 61, 2), "the fence refuses it");
        assertEquals(5, g.dx(9));
        assertEquals(0, g.dz(3));
    }
}