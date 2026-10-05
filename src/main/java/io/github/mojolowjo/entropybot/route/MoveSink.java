package io.github.mojolowjo.entropybot.route;

/** Receives the moves {@link CellMoves} finds from one cell. */
@FunctionalInterface
public interface MoveSink {
    /**
     * One possible move to the feet position (x, y, z), costing {@code ticks} (Baritone's cost, finite and
     * positive; impossible moves, {@code ActionCosts.COST_INF}, are never reported).
     */
    void move(int x, int y, int z, double ticks);
}
