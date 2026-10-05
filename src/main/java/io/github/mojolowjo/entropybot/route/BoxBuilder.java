package io.github.mojolowjo.entropybot.route;

/**
 * Builds one box from its moves: doors (grouped crossing moves per face) and the crossing matrix (one Dijkstra per
 * enterable door over the box plus a one-cell margin). See {@link Door} and {@link SectionRecord} for the rules.
 * Pure function of the input; runs on a worker. 4096 cells x ~22 moves: a few ms with fakes.
 */
public final class BoxBuilder {
    private BoxBuilder() {
    }

    /** Builds the record. Throws only on a broken {@link CellMoves} (the caller counts it). */
    public static SectionRecord build(BuildInput in) {
        throw new UnsupportedOperationException("stage B");
    }

    /**
     * Builds and stores the box, unless {@code in.allowBreak()} (review R5: counted as refused, not stored). Never
     * throws: an exception is counted and logged. Returns true when stored.
     */
    public static boolean buildInto(BuildInput in, RouteStore store, RouteCounters counters, RouteLog log) {
        throw new UnsupportedOperationException("stage B");
    }
}
