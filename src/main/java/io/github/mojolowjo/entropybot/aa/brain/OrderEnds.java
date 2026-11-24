package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

/**
 * sim (owner 2026-11-09, "simulate the parts we need"): the goals tick's pass over the orders, moved out of
 * {@code commands.AaRuntime.goalsTick} as it was so the order-line tests run the same sequence with no game: the met
 * orders leave (their "got" said, their deposit weighed), the honest-loop parks whose facts changed try once more
 * ({@link Goals#retryLoopParks}), the all-parked line is said ({@link Goals#parkedTick}), and then, after every un-park of
 * the pass, each open order's deposit is swept ({@link LineDeposits#sweep}). The game side's own parts come back through {@link Hooks}. No Minecraft or Baritone types.
 * Loader notes: none.
 */
public final class OrderEnds {
    private OrderEnds() {}

    /** The runtime's side of the pass. */
    public interface Hooks {
        /**
         * 0.39.z77 answers (1): a met order whose desire still runs its batch's playbook - the runtime keeps it and its
         * desire stays until that run ends. True: carried on (its desire is not removed here).
         */
        boolean carriesOn(Goals.Goal g);

        /** The order's "got N item (goal n done)" line: logged and whispered. */
        void got(String msg);

        /** A deposit answer to say and act on (null or a null text: nothing). */
        void deposit(LineDeposits.Out out);
    }

    /**
     * {@code mainAt}: the main chest's cell as text, null: none set. {@code underGround}: the feet are under the surface
     * depth. {@code reachedWhy}: the caller's words for a met order's desire leaving the brain (the runtime's log text;
     * NoChainTextTest keeps such literals out of aa/).
     */
    public static void tick(Blackboard bb, Brain brain, LineDeposits deposits, String mainAt, boolean underGround, String reachedWhy, Hooks h) {
        var goals = Goals.of(bb);
        // lost-after-done (tester-2 w064): a met -d order whose amount the bag no longer holds, its deposit still owed, is
        // open again and priced like any order (read from the bag; a death, a drop, anything). Before the done check, so a
        // line never ends on a bag that lost a finished order's amount
        if (bb.in != null && bb.in.bag != null)
            for (var e : deposits.lostAfterDone(bb.in.bag)) {
                Goals.Goal g = e.getKey();
                if (Goals.reopen(bb, g)) brain.add(io.github.mojolowjo.entropybot.aa.AaDesires.ownerGoal(g));
                else {
                    // rule 7: the newer order of the same item holds the key; the old one stays out of the goals, its line
                    // waits on it - aa cancel ends that line (said)
                    h.deposit(new LineDeposits.Out(e.getValue().kind(), e.getValue().text() + " (not put back: a newer order of " + g.r.key() + " is among the goals)", e.getValue().items()));
                    continue;
                }
                h.deposit(e.getValue());
            }
        for (var g : Goals.done(bb)) {
            if (!h.carriesOn(g)) brain.remove(g.id(), reachedWhy);
            h.got("got " + Goals.have(Goals.free(bb, g), g) + " " + g.r.key() + " (goal " + g.n + " done)");
            h.deposit(deposits.weigh(g, "done", bb.in.bag, mainAt));                // helper: the bag to the main chest
        }
        // wait-not-park (audit A1): an order whose run ended while it waited on an answer runs again when the answer landed
        Goals.retryWaits(bb, brain.failures);
        // 0.39.z70 (R1 = c): said by Goals (log + whisper); job-lookup: the brain's hold on a retried order is forgotten
        Goals.retryLoopParks(bb, brain.failures);
        // 0.39.z77 climb (run CM 17:43-17:48, rule 7): every order parked - under ground the waiting ones try the climb way once,
        // and the all-parked line (each park and its wake) is said once; said by Goals (log + whisper)
        Goals.parkedTick(bb, underGround, brain.failures, brain.runningDesire() != null || (bb.in != null && bb.in.otherJob != null));
        // helper: the bag of an order stopped for good goes to the chest too; an order that woke is weighed again at its
        // end; parked-holds (owner 2026-11-23): a stopped order that can still wake holds its line's deposit (said once).
        // un-park first (tester-1 w006, 2026-11-22: torch weighed "stopped" at 04:34:10.244 and the line went due; at
        // 04:34:10.249, in this same pass, the climb retry un-parked both orders): the sweep runs after every un-park of
        // this pass (the waits, the loop parks, the climb retry and the look-around), so an order running again is never
        // weighed as stopped
        for (var g : goals) h.deposit(deposits.sweep(g, bb.in.bag, mainAt));
    }
}
