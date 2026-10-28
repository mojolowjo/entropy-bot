package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.brain.GameTimes;

import java.util.HashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * 0.39.z78 ea (run EA 20:19:51-20:20:48, to-do/ea-eb-ec-spread.md; rule 22: the plan picks by the estimate after
 * pathCost.firstAnswerWaitS, and a leg's path wait has the same bound as a step's): the first-answer clock of the walks the
 * planner asks. A walk answered "not known yet" (computing, queued, chunks: {@link PricedAnswers#waits}) for
 * firstAnswerWaitS of asks is answered by Baritone's estimate from where the bot stands ({@link PricedAnswers#afterWait}).
 * <p>Was (0.39.z76 send-back 3, LivePathPrice.ask): the clock started again whenever the bot stood more than failures.moved
 * from where it was first asked - so while a step moved the bot (EA: a strip at about a cell a second) the walks the trip's
 * re-price asked never reached the wait, and the leg stayed "not priced yet" with no bound. Now the clock is the walk's,
 * wherever the bot stands: it ends when the walk answers (a path, no path) or is not asked for longer than the wait itself
 * (a walk asked again later waits its own wait). The estimate is the one from the feet now (ranked, not priced, rule 18).
 * Pure (JUnit: EaLegWaitTest). Loader notes: none (game ticks are passed in).
 */
final class FirstAnswerClock {
    /** Per ask key: {first waiting answer's tick, last ask's tick}. */
    private final Map<String, long[]> clocks = new HashMap<>();
    private final int max;

    /** {@code max}: clocks kept (past it all start again: a chain asks a few dozen). */
    FirstAnswerClock(int max) { this.max = max; }

    /**
     * The answer for {@code key} asked at game tick {@code now}: {@code a} as it is, or past {@code waitS} of waiting asks
     * Baritone's estimate ({@code estimateS}, read only then).
     */
    PathPrice.Answer answer(String key, PathPrice.Answer a, long now, int waitS, DoubleSupplier estimateS) {
        if (!PricedAnswers.waits(a)) { clocks.remove(key); return a; }
        // 0.39.z78 J6c run EU (1a; 10:39:58-10:40:37: two lip walks "chunks not loaded" stood 30 s on nothing - 0 walks
        // computing): a walk into chunks not loaded has no search to wait on - it is ranked at once by Baritone's own goal
        // heuristic (the fork's IPathEstimate: no world read, valid without the chunks), "estimate, chunks not loaded",
        // never a price (rule 18 as amended); no estimate: the wait as before
        if (a.kind() == PathPrice.Kind.CHUNKS) {
            PathPrice.Answer e = PricedAnswers.chunksEstimate(a, estimateS.getAsDouble());
            if (e != null) { clocks.remove(key); return e; }
        }
        long wait = (long) Math.max(0, waitS) * GameTimes.TPS;
        long[] c = clocks.get(key);
        if (c == null || now - c[1] > wait) {              // a new wait: first asked, or not asked for longer than the wait
            if (clocks.size() >= max) clocks.clear();
            clocks.put(key, new long[]{now, now});
            return a;
        }
        c[1] = now;
        if (now - c[0] < wait) return a;
        return PricedAnswers.afterWait(a, now - c[0], waitS, estimateS.getAsDouble());
    }
}
