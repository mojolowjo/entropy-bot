package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import io.github.mojolowjo.entropybot.aa.moves.StandWait;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** job-stand2 (run JAS): the timed-out hold, the stand's end on the set, the line per change, one full stand per order. */
class Stand2Test {
    static final String PREFIX = "not known: the search ran out of its";

    @Test
    void timedOutHoldPricesByEstimateAndDoesNotReAsk() {
        assertTrue(NotKnownHold.timedOut(PREFIX + " 2000 ms", PREFIX));
        assertFalse(NotKnownHold.timedOut("no progress", PREFIX));
        NotKnownHold h = new NotKnownHold(16);
        int[] at = {0, 20, 0};
        h.hold("1,20,5", 100, at);
        PathPrice.Answer a = h.answer("1,20,5", 100 + 10 * GameTimes.TPS, at, 60, 8, 12.0);
        assertNotNull(a, "held: answered without an ask");
        assertEquals(PathPrice.Kind.ESTIMATED, a.kind());
        assertTrue(a.reason().startsWith("not known (timed out 10 s ago): priced by the estimate until 50 s"), a.reason());
        assertNull(h.answer("1,20,5", 100 + 61 * GameTimes.TPS, at, 60, 8, 12.0), "the hold ends after notKnownHoldS");
        h.hold("1,20,5", 100, at);
        assertNull(h.answer("1,20,5", 110, new int[]{9, 20, 0}, 60, 8, 12.0), "the hold ends after a move of notKnownMoveM");
        assertNull(h.answer("2,2,2", 110, at, 60, 8, 12.0), "never held: asked as before");
    }

    /** jbf-3 (run JBF-T2 10:41-10:50): the re-search wait twice in a row holds the walk with its own words; another answer starts the count over. */
    @Test
    void theResearchWaitTwiceHoldsTheWalkByTheEstimate() {
        NotKnownHold h = new NotKnownHold(16);
        assertFalse(h.researchAgain("k", 2), "once: not yet");
        h.researchEnded("k");
        assertFalse(h.researchAgain("k", 2), "a landed answer between: the count starts over");
        assertTrue(h.researchAgain("k", 2), "twice in a row: held");
        assertFalse(h.researchAgain("k", 0), "0: never");
        int[] at = {0, 20, 0};
        h.hold("k", 100, at, NotKnownHold.RESEARCH);
        PathPrice.Answer a = h.answer("k", 100 + 5 * GameTimes.TPS, at, 60, 8, 12.0);
        assertEquals(PathPrice.Kind.ESTIMATED, a.kind());
        assertTrue(a.reason().startsWith("not known (" + NotKnownHold.RESEARCH + " 5 s ago): priced by the estimate until 55 s"), a.reason());
        assertNull(h.answer("k", 100 + 61 * GameTimes.TPS, at, 60, 8, 12.0), "the hold ends after notKnownHoldS");
    }

    @Test
    void standEndsWhenNothingItWaitsOnComputes() {
        StandWait w = new StandWait();
        w.begin(0);
        assertFalse(w.setDone(0), "0 before any walk is counted: the asks not registered yet");
        assertFalse(w.setDone(3));
        assertTrue(w.setDone(0), "every walk landed, timed out or walked");
        w.begin(0);
        w.setDone(2);
        w.walking("walk to 1 2 3");
        assertFalse(w.setDone(0), "a first segment walked: the walk itself runs");
    }

    @Test
    void stillStandingOncePerChange() {
        StandWait w = new StandWait();
        w.begin(0);
        assertNull(w.reasonChanged(1), "the start line said it");
        int lines = 0;
        for (int n = 2; n < 70; n++) if (w.reasonChanged(n) != null) lines++;
        for (int n = 69; n > 0; n--) if (w.reasonChanged(n) != null) lines++;
        assertEquals(0, lines, "a count going 1..69..1 is no new kind");
        assertNotNull(w.reasonChanged(-1));
        assertNull(w.reasonChanged(-1));
    }

    @Test
    void noSecondFullStandInOneOrder() {
        FirstAnswerClock c = new FirstAnswerClock(64);
        PathPrice.Answer computing = PathPrice.Answer.computing(null);
        assertEquals(PathPrice.Kind.COMPUTING, c.answer("k", computing, 0, 30, () -> 9.0).kind());
        c.spendWaiting();                                  // the stand's 30 s served
        // later in the order, after the clock would have started again (not asked for longer than the wait)
        assertEquals(PathPrice.Kind.ESTIMATED, c.answer("k", computing, 100 * GameTimes.TPS, 30, () -> 9.0).kind(),
                "a walk the order's stand waited on: the estimate at once, no new stand");
        assertEquals(PathPrice.Kind.COMPUTING, c.answer("new", computing, 100 * GameTimes.TPS, 30, () -> 9.0).kind(),
                "a new ask still gets its wait");
        c.newOrder();
        assertEquals(PathPrice.Kind.COMPUTING, c.answer("k", computing, 200 * GameTimes.TPS, 30, () -> 9.0).kind(), "a new order waits again");
    }
}
