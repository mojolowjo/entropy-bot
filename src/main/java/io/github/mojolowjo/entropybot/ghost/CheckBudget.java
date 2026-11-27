package io.github.mojolowjo.entropybot.ghost;

/**
 * Dry-run package 3: how much ghost work one game tick may do. The ghost runs at most {@code ghost.ticksPerTick} of its
 * own ticks and stops once {@code ghost.msPerTick} milliseconds are spent, so a long path is checked over a few game
 * ticks; a path not judged within {@code ghost.deadlineTicks} game ticks is marked "unchecked", never "pass" (plan,
 * "Error catching"). The real bot never waits for it.
 *
 * <p>Pure (JUnit: CheckBudgetTest). Loader notes: none.
 */
public final class CheckBudget {
    private final int ticksPerTick, deadlineTicks;
    private final double msPerTick;

    public CheckBudget(int ticksPerTick, double msPerTick, int deadlineTicks) {
        this.ticksPerTick = ticksPerTick;
        this.msPerTick = msPerTick;
        this.deadlineTicks = deadlineTicks;
    }

    /** May the ghost take one more step this game tick, after {@code steps} steps and {@code spentMs}? */
    public boolean mayStep(int steps, double spentMs) {
        return steps < ticksPerTick && spentMs < msPerTick;
    }

    /** Is a check that started {@code gameTicks} game ticks ago out of time? */
    public boolean late(int gameTicks) {
        return gameTicks > deadlineTicks;
    }
}
