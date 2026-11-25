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

    /** job-stand2 item 3: the walks waited on by this order's full stand (cleared on a new order). */
    private final java.util.Set<String> spent = new java.util.HashSet<>();

    /**
     * job-deposit-park-wake review 1 (pilot c606aca8 w004: the plan flipped between the tree walk and the stone-pickaxe plan,
     * every flip a new run id, so {@link #newOrder} cleared {@link #spent} and the stand waited its 30 s again on the same
     * walks from the same cell): the walks a served stand waited on, key -&gt; the cell it was served on. Kept over a new
     * order while the bot stands on that cell (the same question from the same place: the estimate at once); any other cell
     * drops it (a new order there waits again, job-stand2).
     */
    private final Map<String, int[]> spentAt = new HashMap<>();

    /** job-stand2 item 3: the order's stand ran its full wait: every walk on a clock now is spent for the order. */
    void spendWaiting() { spendWaiting(null); }

    /** As above; {@code feet}: the cell the stand was served on (null: not known - spent for this order only). */
    void spendWaiting(int[] feet) {
        if (spent.size() + clocks.size() > max) spent.clear();
        spent.addAll(clocks.keySet());
        if (feet == null) return;
        if (spentAt.size() + clocks.size() > max) spentAt.clear();
        for (String k : clocks.keySet()) spentAt.put(k, feet.clone());
    }

    /** job-stand2 item 3: a new order - its stand waits again (review 1: not on a walk spent from the cell the bot still stands on). */
    void newOrder() { spent.clear(); }

    boolean spent(String key) { return spent.contains(key); }

    /**
     * The answer for {@code key} asked at game tick {@code now}: {@code a} as it is, or past {@code waitS} of waiting asks
     * Baritone's estimate ({@code estimateS}, read only then).
     */
    PathPrice.Answer answer(String key, PathPrice.Answer a, long now, int waitS, DoubleSupplier estimateS) {
        return answer(key, a, now, waitS, estimateS, null);
    }

    /**
     * job-deposit-park-wake (pilot c606aca8 w004 09:44-09:47: "walk to 93 65 74: COMPUTING (no progress ...)" 25 times from one
     * cell, the bot standing 30 s each time, 213 s in all): the walks answered "no progress" from a cell, key -&gt; that cell.
     * Review 1 (the reviewer: the branch's first fix made it a NONE, so a walk only not known dropped - rule 15, rule 23(1),
     * the 0.39.z77 noprogress / J6b ruling, run DH's descent): a search that stopped on its budget with no step past its start
     * is not known, and a wait from that cell gives no more - Baritone's estimate at once ({@link PricedAnswers#noProgressEstimate},
     * ranked, not priced, the walk computes its full path on the way), and again while the bot stands on that cell and the
     * fork has no answer yet; any other cell asks the fork again. No usable estimate: the wait as before.
     */
    private final Map<String, int[]> noProgress = new HashMap<>();

    /** As above, {@code feet}: the cell the bot stands on (null: not known - the answer at once, nothing kept). */
    PathPrice.Answer answer(String key, PathPrice.Answer a, long now, int waitS, DoubleSupplier estimateS, int[] feet) {
        int[] np = noProgress.get(key);
        if (np != null && (feet == null || !java.util.Arrays.equals(np, feet) || !PricedAnswers.waits(a))) { noProgress.remove(key); np = null; }
        int[] sp = spentAt.get(key);
        if (sp != null && (feet == null || !java.util.Arrays.equals(sp, feet))) { spentAt.remove(key); sp = null; }
        if (PricedAnswers.noProgress(a) || np != null) {
            if (feet != null) {
                if (noProgress.size() >= max) noProgress.clear();
                noProgress.put(key, feet.clone());
            }
            PathPrice.Answer e = PricedAnswers.noProgressEstimate(a, estimateS.getAsDouble(), feet);
            if (e != null) { clocks.remove(key); return e; }
        }        if (!PricedAnswers.waits(a)) { clocks.remove(key); return a; }
        // 0.39.z78 J6c run EU (1a; 10:39:58-10:40:37: two lip walks "chunks not loaded" stood 30 s on nothing - 0 walks
        // computing): a walk into chunks not loaded has no search to wait on - it is ranked at once by Baritone's own goal
        // heuristic (the fork's IPathEstimate: no world read, valid without the chunks), "estimate, chunks not loaded",
        // never a price (rule 18 as amended); no estimate: the wait as before
        if (a.kind() == PathPrice.Kind.CHUNKS) {
            PathPrice.Answer e = PricedAnswers.chunksEstimate(a, estimateS.getAsDouble());
            if (e != null) { clocks.remove(key); return e; }
        }
        long wait = (long) Math.max(0, waitS) * GameTimes.TPS;
        // job-stand2 item 3 (run JAS: 6 stands of 30 s in one order): a walk this order's stand already waited its full wait
        // on is past its wait for the rest of the order - the estimate at once, never a second stand on it
        if (spent.contains(key) || sp != null) return PricedAnswers.afterWait(a, wait, waitS, estimateS.getAsDouble());
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
