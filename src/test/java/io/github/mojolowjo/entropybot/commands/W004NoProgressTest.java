package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-deposit-park-wake (tester pilot on c606aca8, w004 09:44:05-09:47:38): the wooden pickaxe broke 25 blocks down (y 44);
 * the bot stood 213 s - "stand while paths compute (up to 30 s): stood 30 s" and "walk to 93 65 74: COMPUTING (no progress:
 * Baritone's search stopped on its budget with no step past the start ...)" 25 times from the same cell, the plan flipping
 * between the tree walk and the stone-pickaxe plan whose walk sat "queued: pathCost.maxPending 4 searches in flight".
 * A search that ends no progress from a cell is the answer from that cell: the walk from there is not known to be possible
 * - a NONE (the way drops, no stand on it again) while the bot stands there; another cell asks the fork again.
 */
class W004NoProgressTest {
    static final int WAIT = 30;
    static final int[] Y44 = {39, 44, 21}, NEXT = {39, 44, 22};
    static final PathPrice.Answer QUEUED = PathPrice.Answer.computing("queued: pathCost.maxPending 4 searches in flight (the fork's priority queue starts it when a slot frees)");
    static final PathPrice.Answer NO_PROGRESS = PricedAnswers.noPath("no progress", false, true, "chunks not loaded");
    static final PathPrice.Answer RESEARCH = PathPrice.Answer.computing("re-search waiting (the bot moved off the last path) (pathCost.researchMinS)");

    @Test
    void w004_noProgressFromTheCell_isNoneThere_andAskedAgainFromTheNextCell() {
        FirstAnswerClock clock = new FirstAnswerClock(1024);
        assertEquals(PathPrice.Kind.COMPUTING, NO_PROGRESS.kind(), "the fork's answer itself: not the search's end (rule 15)");
        // the queued ask while the four slots are taken: still the wait (an answer will come)
        assertEquals(PathPrice.Kind.COMPUTING, clock.answer("93,65,74", QUEUED, 0, WAIT, () -> 40, Y44).kind());
        // 09:44:07.403: the search landed no progress from y 44 - the answer from here
        PathPrice.Answer a = clock.answer("93,65,74", NO_PROGRESS, 40, WAIT, () -> 40, Y44);
        assertEquals(PathPrice.Kind.NONE, a.kind(), a.toString());
        assertTrue(a.reason().startsWith(PricedAnswers.NO_PROGRESS_HERE), a.reason());
        assertFalse(a.reason().startsWith(PathPrice.NOT_KNOWN), "never a path wait: the chain never stands for it");
        // each re-price of the 25 from the same cell: the same answer, never a second stand (the fork re-searching, or its
        // kept no-progress again)
        for (long t = 100; t < 213L * GameTimes.TPS; t += 600) {
            assertEquals(PathPrice.Kind.NONE, clock.answer("93,65,74", RESEARCH, t, WAIT, () -> 40, Y44).kind(), "tick " + t);
            assertEquals(PathPrice.Kind.NONE, clock.answer("93,65,74", NO_PROGRESS, t + 1, WAIT, () -> 40, Y44).kind(), "tick " + t);
        }
        // another walk is not touched
        assertEquals(PathPrice.Kind.COMPUTING, clock.answer("38,21,11", QUEUED, 50, WAIT, () -> 40, Y44).kind());
        // the bot stands on another cell: the fork is asked again (its answer as it is)
        assertEquals(PathPrice.Kind.COMPUTING, clock.answer("93,65,74", RESEARCH, 5000, WAIT, () -> 40, NEXT).kind(), "searched again from the next cell");
        PathPrice.Answer priced = PathPrice.Answer.priced(new PathPrice.Walk(70));
        assertSame(priced, clock.answer("93,65,74", priced, 5001, WAIT, () -> 40, NEXT));
    }

    @Test
    void aLandedPathFromTheSameCell_endsTheHold() {
        FirstAnswerClock clock = new FirstAnswerClock(1024);
        assertEquals(PathPrice.Kind.NONE, clock.answer("k", NO_PROGRESS, 0, WAIT, () -> 5, Y44).kind());
        PathPrice.Answer priced = PathPrice.Answer.priced(new PathPrice.Walk(9));
        assertSame(priced, clock.answer("k", priced, 10, WAIT, () -> 5, Y44), "a path from here is an answer too");
        assertEquals(PathPrice.Kind.COMPUTING, clock.answer("k", RESEARCH, 20, WAIT, () -> 5, Y44).kind(), "no hold left after it");
    }

    @Test
    void feetNotKnown_theAnswerIsSaid_nothingHeld() {
        FirstAnswerClock clock = new FirstAnswerClock(1024);
        PathPrice.Answer a = clock.answer("k", NO_PROGRESS, 0, WAIT, () -> 5);
        assertEquals(PathPrice.Kind.NONE, a.kind());
        assertTrue(a.reason().contains("(here)"), a.reason());
        assertEquals(PathPrice.Kind.COMPUTING, clock.answer("k", RESEARCH, 1, WAIT, () -> 5).kind(), "no cell: nothing held (rule 15)");
    }
}
