package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 ea (run EA 20:19:51-20:20:48, to-do/ea-eb-ec-spread.md): the leggings' trip re-price waited on the walk to the
 * crafting table at 48 7 8 ("iron_leggings from iron_ingot: walk: path not known yet (computing; re-search waiting (the
 * bot moved off the last path))") while the strip moved the bot about a cell a second; the first-answer clock started
 * again every failures.moved (16) blocks, so the wait never reached pathCost.firstAnswerWaitS (30 s) and had no bound.
 * Rule 22: a leg's path wait has the step's bound - past the wait the walk is Baritone's estimate, wherever the bot is.
 */
class EaLegWaitTest {
    static final int WAIT = 30;
    static final PathPrice.Answer RESEARCH = PathPrice.Answer.computing("re-search waiting (the bot moved off the last path) (pathCost.researchMinS)");

    /** A walk that never lands, asked every 5 ticks while the bot moves: computing until the wait, then the estimate. */
    @Test
    void aLegPathThatNeverLands_isTheEstimateAfterTheWait_whereverTheBotIs() {
        FirstAnswerClock clock = new FirstAnswerClock(1024);
        long t0 = 1000;
        PathPrice.Answer last = null;
        for (long t = t0; t < t0 + (long) WAIT * GameTimes.TPS; t += 5) {   // the bot moves on: no restart any more
            last = clock.answer("48 7 8", RESEARCH, t, WAIT, () -> 12.5);
            assertEquals(PathPrice.Kind.COMPUTING, last.kind(), "inside the wait: not known (rule 15) at tick " + t);
        }
        PathPrice.Answer after = clock.answer("48 7 8", RESEARCH, t0 + (long) WAIT * GameTimes.TPS, WAIT, () -> 12.5);
        assertEquals(PathPrice.Kind.ESTIMATED, after.kind(), "past the wait: Baritone's estimate, ranked, not priced (rule 22)");
        assertEquals(12.5, after.walk().seconds(), 1e-9);
        assertTrue(after.reason().contains("not known after 30 s"), after.reason());
    }

    /** The trip (EA's batch: torch .. boots kit) with every walk past its wait: each leg is priced by the estimates, never "not priced yet". */
    @Test
    void aTripWhoseLegWalksAreEstimated_isPriced() {
        D6WorkerTest.data();
        io.github.mojolowjo.entropybot.aa.PlanNodes.forgetMine();
        io.github.mojolowjo.entropybot.aa.NeedChain.World w = Z78StripHeadingHoldTest.world().withPath((x, y, z, d) ->
                PathPrice.Answer.estimated(new PathPrice.Walk(io.github.mojolowjo.entropybot.aa.FakePaths.walkS(d)), "not known after 30 s from here (computing): Baritone's estimate, ranked, not priced"));
        io.github.mojolowjo.entropybot.aa.NeedChain.Legs l = Z78StripHeadingHoldTest.plan(w);
        assertTrue(l.chain().ok() && !l.chain().explore(), l.chain().why());
        assertFalse(l.chain().why().contains(" waits on "), "no leg waits: " + l.chain().why());
    }

    /** A path that lands ends the wait; the next "not known" is a new wait of its own. */
    @Test
    void aLandingEndsTheWait() {
        FirstAnswerClock clock = new FirstAnswerClock(1024);
        clock.answer("k", RESEARCH, 0, WAIT, () -> 5);
        PathPrice.Answer priced = PathPrice.Answer.priced(new PathPrice.Walk(7));
        assertSame(priced, clock.answer("k", priced, 500, WAIT, () -> 5));
        assertEquals(PathPrice.Kind.COMPUTING, clock.answer("k", RESEARCH, 601, WAIT, () -> 5).kind(), "a new wait from the landing on");
        assertEquals(PathPrice.Kind.ESTIMATED, clock.answer("k", RESEARCH, 601 + (long) WAIT * GameTimes.TPS, WAIT, () -> 5).kind());
    }

    /** A walk not asked for longer than the wait starts its own wait when asked again (never an old clock's estimate). */
    @Test
    void aWalkAskedAgainLaterWaitsItsOwnWait() {
        FirstAnswerClock clock = new FirstAnswerClock(1024);
        clock.answer("k", RESEARCH, 0, WAIT, () -> 5);
        long later = 10L * WAIT * GameTimes.TPS;
        assertEquals(PathPrice.Kind.COMPUTING, clock.answer("k", RESEARCH, later, WAIT, () -> 5).kind());
        // no usable estimate past the wait: the way drops (never another stand, rule 15)
        PathPrice.Answer none = clock.answer("k", RESEARCH, later + (long) WAIT * GameTimes.TPS, WAIT, () -> Double.NaN);
        assertEquals(PathPrice.Kind.NONE, none.kind(), none.toString());
    }
}
