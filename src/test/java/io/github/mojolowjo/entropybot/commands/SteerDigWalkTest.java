package io.github.mojolowjo.entropybot.commands;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** job-steermod: the dig leg's walk(goal, steerDig) by name, its fallback, and the load line's steer count. */
class SteerDigWalkTest {
    public static class Steering {
        public Goal goal;
        public boolean steer;

        public boolean walk(Goal g, boolean steerDig) {
            goal = g;
            steer = steerDig;
            return false;
        }
    }

    public static class Old {
        public boolean walk(Goal g) { return true; }
    }

    @Test
    void theSteeredWalkIsPickedWhenPresent() {
        Steering s = new Steering();
        GoalBlock g = new GoalBlock(1, 16, 3);
        assertEquals(Boolean.FALSE, ProbeLines.steeredWalk(s, g));
        assertSame(g, s.goal);
        assertTrue(s.steer, "steerDig asked");
    }

    @Test
    void aJarWithoutItFallsBack() {
        assertNull(ProbeLines.steeredWalk(new Old(), new GoalBlock(1, 16, 3)));
    }

    @Test
    void theLoadLineSaysTheSteers() {
        String with = new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, new long[]{-1}, new long[]{-1},
                new long[]{1, 2, 3, 40, 5, 60, 70, 9}).line(1);
        assertTrue(with.contains("advisor hits +60 of +70 asks; dig steers +9"), with);
        String without = new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, new long[]{-1}, new long[]{-1},
                new long[]{1, 2, 3, 40, 5, 60, 70}).line(1);
        assertTrue(without.contains("dig steers not counted in this fork jar"), without);
    }
}
