package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.PathMemory.Cell;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Entropy D11: the join search's goal - any remaining cell of the kept path, a cheap sampled heuristic. */
public class GoalOnPathTest {

    private static List<Cell> line(int n) {
        List<Cell> out = new ArrayList<>();
        for (int x = 0; x < n; x++) out.add(new Cell(x, 64, 0));
        return out;
    }

    @Test
    public void anyRemainingCellIsTheGoal() {
        GoalOnPath g = new GoalOnPath(line(5));
        for (int x = 0; x < 5; x++) assertTrue(g.isInGoal(x, 64, 0));
        assertFalse(g.isInGoal(5, 64, 0));
        assertFalse(g.isInGoal(2, 65, 0));
        assertEquals(5, g.size());
    }

    @Test
    public void theHeuristicIsTheLeastOverTheSample_andExactAtASampledCell() {
        GoalOnPath g = new GoalOnPath(line(100));         // 100 cells: every 6th sampled plus the last
        assertEquals(0, g.heuristic(0, 64, 0), 1e-9);
        assertEquals(0, g.heuristic(99, 64, 0), 1e-9);
        double near = g.heuristic(50, 64, 3);              // 3 blocks beside the line: at most the walk of a few cells + 3
        assertTrue(near <= GoalBlock.calculate(5, 0, 3) + 1e-9, String.valueOf(near));
        assertTrue(g.heuristic(50, 64, 30) > near);
    }

    @Test
    public void noCellsIsNoGoal() {
        assertThrows(IllegalArgumentException.class, () -> new GoalOnPath(List.of()));
    }
}
