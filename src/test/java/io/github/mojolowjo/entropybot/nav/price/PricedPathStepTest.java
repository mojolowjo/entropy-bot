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
 * Entropy Bot 2026-11-27: the fork's PricedPathStepTest (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.IPathCost.Answer;
import io.github.mojolowjo.entropybot.nav.price.IPathCost.Stop;
import io.github.mojolowjo.entropybot.nav.price.IPricedPath;
import io.github.mojolowjo.entropybot.nav.price.IPricedPath.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Entropy z46: the rule from one segment search of a priced path to the next step (S56/S57's fork half). */
public class PricedPathStepTest {

    @Test
    public void aSegmentInTheGoalEndsThePrice() {
        assertTrue(Step.of(true, true, true, Stop.GOAL, 1, 8).done());
    }

    @Test
    public void aTimedOutSegmentThatMovedOnSearchesTheNextOneNotBudget() {
        // S56: the primary timeout gave a segment; Baritone's own pathing would walk it and plan ahead - so does the price
        Step s = Step.of(true, false, true, Stop.TIME, 1, 8);
        assertTrue(s.next());
        assertFalse(s.done());
    }

    @Test
    public void chunkBorderStopsOnALoadedGoalAreNotChunksNotLoaded() {
        // S57: the search touched the loaded edge (z41 said "chunks not loaded" for this); with progress it goes on
        assertTrue(Step.of(true, false, true, Stop.CHUNKS, 2, 8).next());
    }

    @Test
    public void aChunkBorderStopWithNoProgressIsChunksNotLoadedNotNoProgress() {
        // z48 item 7 (runs I/J): the frontier ran into unloaded land before any node got MIN_DIST_PATH from the start:
        // the way is not known (rule 15), never "no progress" (which the mod drops as unreachable)
        assertEquals(Answer.CHUNKS, Step.of(true, false, false, Stop.CHUNKS, 2, 8).reason());
        assertEquals(Answer.CHUNKS, Step.of(false, false, false, Stop.CHUNKS, 0, 8).reason());
        assertEquals(IPricedPath.NO_PROGRESS, Step.of(false, false, false, Stop.TIME, 0, 8).reason());
    }

    @Test
    public void anExhaustedSearchIsUnreachable() {
        assertEquals(Answer.UNREACHABLE, Step.of(false, false, false, Stop.EXHAUSTED, 0, 8).reason());
    }

    @Test
    public void pastMaxSegmentsIsBudget() {
        assertEquals(Answer.BUDGET, Step.of(true, false, true, Stop.TIME, 8, 8).reason());
    }

    @Test
    public void errorsAndCancelsAreErrors() {
        assertEquals(Answer.ERROR, Step.of(true, true, true, Stop.ERROR, 1, 8).reason());
        assertEquals(Answer.ERROR, Step.of(false, false, false, Stop.CANCELLED, 0, 8).reason());
    }
}
