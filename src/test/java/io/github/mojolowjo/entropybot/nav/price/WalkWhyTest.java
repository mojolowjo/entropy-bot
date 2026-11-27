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
 * Entropy Bot 2026-11-27: the fork's WalkWhyTest (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import org.junit.jupiter.api.Test;

import io.github.mojolowjo.entropybot.nav.search.goals.DigDownGoal;

import java.util.List;

import static io.github.mojolowjo.entropybot.nav.cost.ActionCosts.COST_INF;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Entropy z55 items 2-3 (run N 21:25:33: the strip leg's walk found "no kept path" 3 s after a complete price from the same
 * feet - the bot had placed a crafting table at -73 90 -99, the first cell of the path's Traverse; "to -56 -64 -9 | no
 * progress": the dig-down heuristic read the floor's unbreakable goal block).
 */
public class WalkWhyTest {

    private static final WalkWhy.Kept FRESH = new WalkWhy.Kept(false, true, false, 52, 1200, "-72 90 -99");

    @Test
    public void runN2125ACellChangedOnTheKeptPathIsSaidWithTheCell() {
        String why = WalkWhy.of(FRESH, "-72 90 -99", 2, "-73 90 -99: Air -> Crafting Table");
        assertEquals("a block on the kept path changed since the price (-73 90 -99: Air -> Crafting Table)", why);
    }

    @Test
    public void eachOtherReasonHasItsWords() {
        assertTrue(WalkWhy.of(null, "-72 90 -99", -1, "").startsWith("none kept"));
        assertEquals("the price found no path", WalkWhy.of(new WalkWhy.Kept(true, false, true, 1, 1200, "a"), "a", -1, ""));
        assertEquals("the price kept no complete path", WalkWhy.of(new WalkWhy.Kept(false, false, false, 1, 1200, "a"), "a", -1, ""));
        assertEquals("the kept path expired (1300 ticks old, kept 1200)", WalkWhy.of(new WalkWhy.Kept(false, true, false, 1300, 1200, "a"), "a", -1, ""));
        assertEquals("the bot stands at b, not at the kept path's start a", WalkWhy.of(new WalkWhy.Kept(false, true, false, 1, 1200, "a"), "b", -1, ""));
        assertEquals("the kept path was not valid (no reason found)", WalkWhy.of(FRESH, "-72 90 -99", -1, ""));
    }

    @Test
    public void theFirstChangedCell() {
        assertEquals(-1, WalkWhy.firstChanged(List.of("a", "b"), List.of("a", "b")));
        assertEquals(1, WalkWhy.firstChanged(List.of("a", "b"), List.of("a", "c")));
        assertEquals(1, WalkWhy.firstChanged(List.of("a", "b"), List.of("a")));
    }

    @Test
    public void runNTheColumnsTypicalDigIgnoresAirAndTheUnbreakableFloor() {
        // the column from y 62 down to the floor: air, stone 30 ticks, deepslate 45, the floor COST_INF
        assertEquals(30, DigDownGoal.typicalDig(new double[]{0, 30, 30, 45, COST_INF}), 0);
        assertEquals(37.5, DigDownGoal.typicalDig(new double[]{30, 45}), 1e-9);
        assertTrue(Double.isNaN(DigDownGoal.typicalDig(new double[]{0, COST_INF})));
        assertTrue(Double.isNaN(DigDownGoal.typicalDig(new double[0])));
    }

    /**
     * z56 review 2 (run O 22:15:04: "none kept for this goal", then NEXT_CALC_FAILED x2 and CALC_FAILED): a goal priced
     * with a DigDownGoal still swaps after its kept price expired (the bot left the price's start) - for the walk's first
     * calc and its next-segment calc alike (both ask searchGoal with the walk's goal object); a goal priced plain does not.
     */
    /** z56 review 2: the steered fact of a goal's price is kept past the kept price (KeptAnswers.SteerMemory; the walk's swap rule moves with the walk, step 9). */
    @Test
    public void theSteeredFactOfAPriceSurvivesTheKeptPathsExpiry() {
        KeptAnswers.SteerMemory mem = new KeptAnswers.SteerMemory();
        io.github.mojolowjo.entropybot.nav.search.goals.Goal deep = new io.github.mojolowjo.entropybot.nav.search.goals.GoalGetToBlock(new net.minecraft.core.BlockPos(-27, 16, -55));
        io.github.mojolowjo.entropybot.nav.search.goals.Goal near = new io.github.mojolowjo.entropybot.nav.search.goals.GoalGetToBlock(new net.minecraft.core.BlockPos(-27, 60, -55));
        mem.record(deep, true);
        mem.record(near, false);
        io.github.mojolowjo.entropybot.nav.search.goals.Goal walked = new io.github.mojolowjo.entropybot.nav.search.goals.GoalGetToBlock(new net.minecraft.core.BlockPos(-27, 16, -55));
        assertEquals(Boolean.TRUE, mem.get(walked), "an equal goal finds the recorded fact");
        assertEquals(Boolean.FALSE, mem.get(near), "priced plain stays plain");
        assertNull(mem.get(new io.github.mojolowjo.entropybot.nav.search.goals.GoalGetToBlock(new net.minecraft.core.BlockPos(0, 5, 0))), "never priced");
        mem.record(deep, false);
        assertEquals(Boolean.FALSE, mem.get(walked), "the goal's next price replaces the fact");
        mem.clear();
        assertNull(mem.get(walked));
    }
}
