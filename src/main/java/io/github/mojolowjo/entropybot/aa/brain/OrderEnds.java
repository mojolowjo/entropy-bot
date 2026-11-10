package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

/**
 * sim (owner 2026-11-09, "simulate the parts we need"): the goals tick's pass over the orders, moved out of
 * {@code commands.AaRuntime.goalsTick} as it was so the order-line tests run the same sequence with no game: the met
 * orders leave (their "got" said, their deposit weighed), each open order's deposit is swept ({@link LineDeposits#sweep}),
 * the honest-loop parks whose facts changed try once more ({@link Goals#retryLoopParks}) and the all-parked line is said
 * ({@link Goals#parkedTick}). The game side's own parts come back through {@link Hooks}. No Minecraft or Baritone types.
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
        for (var g : Goals.done(bb)) {
            if (!h.carriesOn(g)) brain.remove(g.id(), reachedWhy);
            h.got("got " + Goals.have(bb.in.bag, g) + " " + g.r.key() + " (goal " + g.n + " done)");
            h.deposit(deposits.weigh(g, "done", bb.in.bag, mainAt));                // helper: the bag to the main chest
        }
        // helper: a stopped order's bag goes to the chest too; an order that woke is weighed again at its end
        for (var g : goals) h.deposit(deposits.sweep(g, bb.in.bag, mainAt));
        // 0.39.z70 (R1 = c): said by Goals (log + whisper); job-lookup: the brain's hold on a retried order is forgotten
        Goals.retryLoopParks(bb, brain.failures);
        // 0.39.z77 climb (run CM 17:43-17:48, rule 7): every order parked - under ground the waiting ones try the climb way once,
        // and the all-parked line (each park and its wake) is said once; said by Goals (log + whisper)
        Goals.parkedTick(bb, underGround, brain.failures);
    }
}
