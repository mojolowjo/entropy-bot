package io.github.mojolowjo.entropybot.nav.search;

/**
 * nav step 6: why a search stopped (the fork's {@code IPathCost.Stop}): the honest split between "there is no way"
 * (EXHAUSTED), "I ran out of budget" (TIME, NODES, COST) and "the world is not known there" (CHUNKS).
 *
 * <p>Pure (JUnit: SearchMatchForkTest). Loader notes: none.
 */
public enum Stop {
    /** Reached the goal. */
    GOAL,
    /** The wall clock ran out (primary or failure timeout). */
    TIME,
    /** The node budget ran out. */
    NODES,
    /** The cheapest open node costs more than the cost limit. */
    COST,
    /** Ran out of open nodes after hitting unloaded chunks: the way may be through ground not known. */
    CHUNKS,
    /** Ran out of open nodes with every chunk known: no way. */
    EXHAUSTED,
    /** Cancelled from outside. */
    CANCELLED
}
