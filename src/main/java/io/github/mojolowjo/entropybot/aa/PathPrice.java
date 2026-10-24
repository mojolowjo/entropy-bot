package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

/**
 * path-cost (BARITONE_HANDOFF channel 3): the walk price of a chain's trip, asked of Baritone's own A* before walking
 * (the fork's IPathCost: its planned path in ticks, within a budget; no movement). TIME_MODEL: path_length = Baritone's
 * planned path if there is one, else the straight line x 1.3; the chain asks here first and keeps the line when there is
 * no answer. The live one is {@code commands/LivePathPrice}; tests use a fake. Pure. Loader notes: none.
 */
public interface PathPrice {

    /** The walk from the bot's feet to stand by the block: seconds of Baritone's path; {@code about}: a partial path plus its estimate of the rest. */
    record Walk(double seconds, boolean about, double lavaS) {
        public Walk(double seconds, boolean about) { this(seconds, about, 0); }
    }

    /** Baritone's price of the walk to stand by the block at x y z; null: no path answer within budget (the caller takes the line, said so). */
    Walk toBlock(int x, int y, int z);

    /** 0.39.z28: one query's answer: the walk, or (walk null) why there is none, in the provider's words. */
    record Answer(Walk walk, String reason) {}

    /** {@link #toBlock} with the reason when there is no walk (aa debug prints it); a provider without reasons says so. */
    default Answer price(int x, int y, int z) {
        Walk w = toBlock(x, y, z);
        return new Answer(w, w == null ? "this path provider gives no reason" : null);
    }

    /** Milliseconds one query may take (the fork caps it). */
    static long budgetMs() { return (long) Tuning.get("pathCost.budgetMs"); }

    /** The walk past which A* stops pricing (its cost ceiling), in seconds. */
    static double budgetS() { return Tuning.get("pathCost.budgetS"); }

    /** Path queries one chain may ask; past them the line. */
    static int perChainMax() { return Tuning.i("pathCost.perChainMax"); }
}
