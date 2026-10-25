package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.GameTimes;

/**
 * 0.39.z58 (Opus Design ruling on run Q 02:29:32-02:33:13: rule 18 stays exact - a walk priced from a cell the bot has left is
 * never known; the "explore 5 while paths compute" moved the bot off every crafting walk's first answer, so "collect 4 torch",
 * the only priced way, won and failed): the explore while paths compute first WAITS in place - Baritone idle, no walk - while
 * first answers (walks not answered yet in this order: searches running or queued in the fork) are on their way, up to the
 * step's own {@code waitS} (pathCost.firstAnswerWaitS); each landing re-prices the order as before (Steps), and a priced way
 * ends the step there. With none pending, or the wait over, or a fork that does not count them (-1: not known, rule 15 -
 * never read as "none pending" silently: said), the explore starts as before. The scorer still picks the way (rule 1).
 * Review 7: the wait counts GAME ticks (the level's game time), so a paused game does not burn it.
 * 0.39.z60 item 1: the wait runs while first answers progress, up to a 30 s cap (see the six-argument {@link #of}).
 * Pure (JUnit: Z58PathQueueTest, Z60RunRTest). Loader notes: none (the caller reads Level#getGameTime).
 */
public final class FirstAnswerWait {
    private FirstAnswerWait() {}

    /** Wait on in place, or start the explore; with the words for the step line and the step probe. */
    public enum Next { WAIT, EXPLORE }

    public record Decision(Next next, String why) {}

    /**
     * @param waitedTicks  game ticks the step has waited so far
     * @param waitS        the step's wait in seconds (0: none)
     * @param pendingFirst first answers computing or queued now; -1: this fork does not count them
     * @param landedSince  an answer landed since the last look: its re-price may not have run yet (the order is priced
     *                     again on it at the next step tick), so the last first answer landing holds the wait one more look
     */
    public static Decision of(long waitedTicks, int waitS, int pendingFirst, boolean landedSince) {
        return of(waitedTicks, waitS, pendingFirst, landedSince, 0, 0);
    }

    /**
     * 0.39.z60 item 1 (run R 03:34:54-03:34:56: "waited 4 s, 2 paths still computing: exploring meanwhile"; the two searches
     * - to -138 41 15 and -56 48 -11 - landed 03:35:00.5, 6 s after the order started, while the explore had already moved
     * the bot off every first answer): the wait lasts while first answers are computing AND progressing, up to its cap
     * {@code waitS} (pathCost.firstAnswerWaitS, 30 s since z60); it ends early as before when none is pending (or a priced
     * way ends the step, Steps), and now also when none progressed - no fork segment and no answer landed - for
     * {@code stallS} (pathCost.firstAnswerStallS).
     *
     * @param stalledTicks game ticks since the last progress (a segment search or an answer landed); -1: progress not known
     *                     (z60 review 2: the wait ends at once, as before z60)
     * @param stallS       seconds without progress that end the wait (0: progress is never judged - the 4-argument call)
     */
    public static Decision of(long waitedTicks, int waitS, int pendingFirst, boolean landedSince, long stalledTicks, double stallS) {
        long limit = (long) waitS * GameTimes.TPS;
        if (waitS <= 0) return new Decision(Next.EXPLORE, "no wait in this step");
        if (pendingFirst < 0) return new Decision(Next.EXPLORE, "this fork jar does not count first answers (older than z58): exploring at once");
        if (pendingFirst == 0 && landedSince && waitedTicks < limit) {
            return new Decision(Next.WAIT, "the last first answer just landed (" + secs(waitedTicks) + " s): the order is priced again on it");
        }
        if (pendingFirst == 0) return new Decision(Next.EXPLORE, waitedTicks <= 0 ? "no first answer is computing: exploring at once"
                : "no first answer computing any more after " + secs(waitedTicks) + " s and still no priced way: exploring meanwhile");
        // z60 review 2: progress not known (the fork gives no segment count): no 30 s wait on an unknown - explore at once as before
        if (stallS > 0 && stalledTicks < 0) {
            return new Decision(Next.EXPLORE, "this fork jar does not count segment searches (IPricedPath.searchCounts), so progress is not known: exploring at once");
        }
        if (waitedTicks >= limit) {
            return new Decision(Next.EXPLORE, "waited " + waitS + " s, " + pendingFirst + " path" + (pendingFirst == 1 ? "" : "s")
                    + " still computing: exploring meanwhile");
        }
        if (stallS > 0 && stalledTicks >= (long) Math.ceil(stallS * GameTimes.TPS)) {
            return new Decision(Next.EXPLORE, pendingFirst + " path" + (pendingFirst == 1 ? "" : "s") + " still computing but none progressed for "
                    + secs(stalledTicks) + " s (pathCost.firstAnswerStallS " + secs((long) Math.ceil(stallS * GameTimes.TPS)) + "; waited "
                    + secs(waitedTicks) + " s of " + waitS + "): exploring meanwhile");
        }
        return new Decision(Next.WAIT, "waiting up to " + waitS + " s while " + pendingFirst + " path" + (pendingFirst == 1 ? "" : "s")
                + " compute (" + secs(waitedTicks) + " s so far; Baritone idle, priced from this cell)");
    }

    /**
     * 0.39.z60 review 2: the wait's state as the step hands keep it (StepHands holds one; Z60RunRTest drives the same one on
     * fake hands): when it began, the landed answers and fork segments at the last look, and when the last progress was.
     * Each look turns the live numbers into a {@link Decision}. Pure.
     */
    public static final class Tracker {
        private long start = -1, landed, segs = -1, progressAt = -1;

        /** The wait begins now with these counts (segments -1: the fork does not count them). */
        public void begin(long now, long landedNow, long segsNow) {
            start = now;
            landed = landedNow;
            segs = segsNow;
            progressAt = now;
        }

        public boolean waiting() { return start >= 0; }

        public void end() { start = -1; }

        /** One look: an answer or a fork segment landed since the last look is progress; the decision on the numbers now. */
        public Decision look(long now, long landedNow, long segsNow, int pendingFirst, int waitS, double stallS) {
            boolean since = landedNow != landed;
            landed = landedNow;
            if (since || segsNow != segs) progressAt = now;
            segs = segsNow;
            long stalled = segsNow < 0 ? -1 : now - progressAt;
            return of(now - start, waitS, pendingFirst, since, stalled, stallS);
        }
    }

    /**
     * 0.39.z60 review 2: the seconds the chain charges for the wait when it prices "explore while paths compute" - the
     * EXPECTED wait, not the cap: none pending (or not counted: the hands then explore at once) 0; else one search round
     * ({@code roundS}, pathCost.firstAnswerExpectS: about one segment search, Baritone's 2 s failureTimeoutMS) per
     * {@code slots} first answers pending (the fork runs pathCost.maxPending at once), at most the cap {@code waitS}.
     */
    public static double expectedS(int pendingFirst, int slots, double roundS, int waitS) {
        if (pendingFirst <= 0 || waitS <= 0) return 0;
        int rounds = (pendingFirst + Math.max(1, slots) - 1) / Math.max(1, slots);
        return Math.min(waitS, rounds * Math.max(0, roundS));
    }

    private static String secs(long ticks) {
        return String.format(java.util.Locale.ROOT, "%.1f", ticks / (double) GameTimes.TPS);
    }
}
