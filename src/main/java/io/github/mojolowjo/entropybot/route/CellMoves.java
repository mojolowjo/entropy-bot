package io.github.mojolowjo.entropybot.route;

/**
 * Baritone's moves as a pure-Java interface. R2 implements it with {@code Moves.apply} on a per-worker
 * {@code CalculationContext} (walking only: allowBreak and allowPlace false, parkour off); JUnit uses fakes.
 *
 * <p>One instance is used by one thread only (Baritone's BlockStateInterface caches the last chunk).
 *
 * <p>Change against the plan's one-method sketch: {@link #standable} is needed because Baritone's moves do not check
 * that the source is a place you can stand (A* only expands nodes it reached); asking the moves of a mid-air spot
 * would invent doors. R2: {@code MovementHelper.canWalkOn(bsi, x, y-1, z)} (or in water) and
 * {@code canWalkThrough} at y and y+1, the test Baritone's own movements use for their destinations.
 */
public interface CellMoves {
    /** True when a walker can stand (or swim) with its feet at (x, y, z). */
    boolean standable(int x, int y, int z);

    /** Reports every finite-cost move from the feet position (x, y, z). Called only for standable cells. */
    void forEachMove(int x, int y, int z, MoveSink s);
}
