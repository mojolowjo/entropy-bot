/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Entropy Bot 2026-11-27: the fork's PathCostAnswerTest (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.IPathCost.Answer;
import io.github.mojolowjo.entropybot.nav.price.IPathCost.Stop;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.search.PathNode;
import io.github.mojolowjo.entropybot.nav.search.openset.BinaryHeapOpenSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Entropy path cost: the rule from one budgeted A* search to one answer, and the open set's peek the cost ceiling reads. */
public class PathCostAnswerTest {

    @Test
    public void aPathToTheGoalIsCompleteAtItsCost() {
        Answer a = Answer.of(Stop.GOAL, true, 963.0, 0, 2000);
        assertTrue(a.complete);
        assertFalse(a.noPath);
        assertEquals(963.0, a.ticks, 0);
    }

    @Test
    public void aPartialPathWithinBudgetIsCostPlusHeuristicAndIncomplete() {
        for (Stop s : new Stop[]{Stop.NODES, Stop.TIME, Stop.COST}) {
            Answer a = Answer.of(s, true, 400.0, 150.0, 2000);
            assertFalse(a.complete, s.name());
            assertFalse(a.noPath, s.name());
            assertEquals(550.0, a.ticks, 0, s.name());
        }
    }

    @Test
    public void aSearchThatReachedUnloadedChunksIsNotKnownEvenWithASegment() {
        // Entropy z41: chunks not loaded is its own reason, never a partial price
        Answer a = Answer.of(Stop.CHUNKS, true, 400.0, 150.0, 2000);
        assertTrue(a.noPath);
        assertEquals(Answer.CHUNKS, a.reason);
        assertEquals(Answer.CHUNKS, Answer.of(Stop.CHUNKS, false, Double.NaN, Double.NaN, 2000).reason);
    }

    @Test
    public void aPartialEstimateAboveTheCeilingIsBudget() {
        Answer a = Answer.of(Stop.COST, true, 1900.0, 300.0, 2000);
        assertTrue(a.noPath);
        assertEquals(Answer.BUDGET, a.reason);
    }

    @Test
    public void noSegmentWhenTheBudgetRanOutIsBudget() {
        Answer a = Answer.of(Stop.NODES, false, Double.NaN, Double.NaN, 2000);
        assertTrue(a.noPath);
        assertEquals(Answer.BUDGET, a.reason);
        assertTrue(Double.isNaN(a.ticks));
    }

    @Test
    public void anExhaustedSearchIsUnreachableEvenWithASegment() {
        assertEquals(Answer.UNREACHABLE, Answer.of(Stop.EXHAUSTED, true, 300.0, 40.0, 2000).reason);
        assertEquals(Answer.UNREACHABLE, Answer.of(Stop.EXHAUSTED, false, Double.NaN, Double.NaN, 2000).reason);
    }

    @Test
    public void cancelOrErrorIsNeverAPrice() {
        assertEquals(Answer.ERROR, Answer.of(Stop.CANCELLED, true, 10, 0, 2000).reason);
        assertEquals(Answer.ERROR, Answer.of(Stop.ERROR, false, 10, 0, 2000).reason);
    }

    @Test
    public void theOpenSetPeekIsTheLowestWithoutRemovingIt() {
        BinaryHeapOpenSet set = new BinaryHeapOpenSet();
        Goal goal = new Goal() { // Goal impls read Baritone.settings(); none in a unit test
            @Override public boolean isInGoal(int x, int y, int z) { return false; }
            @Override public double heuristic(int x, int y, int z) { return 0; }
        };
        double[] costs = {50, 10, 30};
        for (int i = 0; i < costs.length; i++) {
            PathNode n = new PathNode(i + 1, 0, 0, goal);
            n.combinedCost = costs[i];
            set.insert(n);
        }
        assertEquals(10, set.peekLowestCombinedCost(), 0);
        assertEquals(3, set.size());
        assertEquals(10, set.removeLowest().combinedCost, 0);
        assertEquals(30, set.peekLowestCombinedCost(), 0);
    }
}
