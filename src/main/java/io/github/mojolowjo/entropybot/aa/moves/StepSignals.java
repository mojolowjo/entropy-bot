package io.github.mojolowjo.entropybot.aa.moves;

/**
 * 0.39.z71 (design D2 3.2, Job B): what the game shows about the running step at one look, read by its hands
 * ({@code Steps.Hands#signals}; the real ones: {@code commands/StepSignalsLive}) for {@link StepMonitor}. Facts only, no
 * verdict. Rule 15: {@code loaded} false with {@code notLoaded} saying why (Baritone not loaded, no player, its event hook
 * not wired, a reflex holds the bot) - the monitor then never says REPLAN.
 * <ul>
 * <li>{@code feet}: the feet cell {x, y, z}; {@code goalDist}: Baritone's {@code Goal.heuristic} at the feet (NaN: no goal;
 *     a progress fact, never a price - rule 18); {@code goalId}: which goal that is (0.39.z72: another goal re-bases the best -
 *     a target that changes is no progress), null: one unknown goal; {@code inGoal}: the feet in that goal;</li>
 * <li>{@code executing}: Baritone follows a path; {@code calcRunning}: a path search runs; {@code calcFailed}: Baritone's
 *     CALC_FAILED path events so far (a running count; the monitor counts those since the last new best);</li>
 * <li>{@code made}: how many of the step's own output (StepMonitor#output) the bag gained since the step started, -1: not
 *     known;</li>
 * <li>{@code destroyStage}: the game's break stage of the block being broken (0-9), -1: none; {@code placedCount}: blocks
 *     this client placed so far (a running count); {@code breakingCell}: "x y z" of that block (0.39.z71 review 1: a
 *     restart of the same block is no progress), null: not known; {@code exploredChunks}: the running explore's new chunks, -1: none;</li>
 * <li>{@code ownWait}: the step waits on a timed game process that has its own watch (a furnace cooking: furnaceJobs'
 *     due time and stall watch), its words; null: none.</li>
 * </ul>
 * Pure (JUnit: Z71StepMonitorTest). Loader notes: none (plain values; the adapter reads Baritone's API and vanilla).
 */
public record StepSignals(boolean loaded, String notLoaded, int[] feet, double goalDist, String goalId, boolean inGoal, boolean executing,
                          boolean calcRunning, long calcFailed, int made, int destroyStage, String breakingCell, long placedCount,
                          int exploredChunks, String ownWait) {
    public StepSignals {
        feet = feet == null ? null : feet.clone();
    }

    /** Not loaded, with why (rule 15). */
    public static StepSignals notLoaded(String why) {
        return new StepSignals(false, why == null ? "not known why" : why, null, Double.NaN, null, false, false, false, 0, -1, -1, null, 0, -1, null);
    }
}
