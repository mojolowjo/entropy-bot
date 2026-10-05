package io.github.mojolowjo.entropybot.route;

/**
 * What a worker needs to build one box.
 *
 * @param moves      the box's moves (one worker only), covering the box plus a one-cell margin.
 * @param allowBreak true when the {@code CalculationContext} behind {@code moves} was built while breaking was on (a
 *                   {@code mine} job). Review R5: such a box is built (callers may look) but <b>never stored</b>:
 *                   {@link BoxBuilder#buildInto} refuses it and counts it.
 * @param builtAt    wall-clock millis, stored in the record.
 */
public record BuildInput(SectionKey key, CellMoves moves, SectionRecord.Quality quality,
                         boolean allowBreak, long builtAt) {
}
