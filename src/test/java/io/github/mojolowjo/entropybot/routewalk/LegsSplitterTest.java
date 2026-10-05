package io.github.mojolowjo.entropybot.routewalk;

import io.github.mojolowjo.entropybot.route.Cell;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegsSplitterTest {
    private static List<Cell> line(int from, int to, int step) {
        List<Cell> out = new ArrayList<>();
        for (int x = from; x <= to; x += step) out.add(new Cell(x, 64, 0));
        return out;
    }

    @Test
    void lastLegIsTheGoalAndNoLegIsLongerThan40() {
        Cell start = new Cell(0, 64, 0), goal = new Cell(200, 64, 0);
        List<Cell> legs = LegsSplitter.split(start, line(16, 192, 16), goal);
        assertEquals(goal, legs.get(legs.size() - 1));
        Cell cur = start;
        for (Cell c : legs) {
            assertTrue(cur.dist(c) <= 40, "leg " + cur + " -> " + c);
            cur = c;
        }
        // furthest door within reach each time: 32, 64, ... so 6 waypoints + the goal
        assertEquals(new Cell(32, 64, 0), legs.get(0));
        assertTrue(legs.size() <= 8);
    }

    @Test
    void noDoorsMeansOneLeg() {
        Cell goal = new Cell(100, 64, 0);
        assertEquals(List.of(goal), LegsSplitter.split(new Cell(0, 64, 0), List.of(), goal));
        assertEquals(List.of(goal), LegsSplitter.split(new Cell(0, 64, 0), null, goal));
    }

    @Test
    void goalWithinReachSkipsTheDoors() {
        Cell goal = new Cell(30, 64, 0);
        assertEquals(List.of(goal), LegsSplitter.split(new Cell(0, 64, 0), line(10, 20, 10), goal));
    }

    @Test
    void doorTooFarIsTakenAnyway() {
        // the map has nothing in between: the next door is the leg
        Cell start = new Cell(0, 64, 0), far = new Cell(55, 64, 0), goal = new Cell(100, 64, 0);
        List<Cell> legs = LegsSplitter.split(start, List.of(far), goal);
        assertEquals(List.of(far, goal), legs);
    }

    @Test
    void doorNextToTheGoalIsDropped() {
        Cell start = new Cell(0, 64, 0), goal = new Cell(80, 64, 0);
        List<Cell> legs = LegsSplitter.split(start, List.of(new Cell(40, 64, 0), new Cell(75, 64, 0)), goal);
        assertEquals(List.of(new Cell(40, 64, 0), goal), legs);
        assertFalse(legs.contains(new Cell(75, 64, 0)));
    }

    @Test
    void followsADetour() {
        // around a lake: up north, east, back south; the straight line would cut through
        Cell start = new Cell(0, 64, 0), goal = new Cell(100, 64, 0);
        List<Cell> doors = List.of(new Cell(0, 64, -30), new Cell(0, 64, -60), new Cell(40, 64, -60), new Cell(80, 64, -60),
                new Cell(100, 64, -30));
        List<Cell> legs = LegsSplitter.split(start, doors, goal);
        assertTrue(legs.contains(new Cell(0, 64, -30)) || legs.contains(new Cell(0, 64, -60)));
        assertEquals(goal, legs.get(legs.size() - 1));
        Cell cur = start;
        for (Cell c : legs.subList(0, legs.size() - 1)) {
            assertTrue(cur.dist(c) <= 40);
            cur = c;
        }
    }
}
