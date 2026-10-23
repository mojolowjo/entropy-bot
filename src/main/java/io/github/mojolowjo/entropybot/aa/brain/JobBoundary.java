package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.moves.Step;

/**
 * jobs (owner-watched 2026-10-22: "if it's mining a tree I want it to do the whole thing; the goal check waits until the
 * current job is done, then checks/cancels"; TIME_MODEL "Order, chain, job, batch"): the brain's goal-done check, its
 * "reason is gone" check, the plan rebuild and the non-danger interrupts run only at a job boundary. While a block job
 * (cut, collect, dig, kelp), a placement or a use runs, {@link #mid()} is true and they wait. A craft is not held here: its
 * batches end within the step and the checks run between them. Danger (a trumping desire), starving food, and the owner's
 * aa off / drop / do still stop a job at once (they do not go through these checks).
 * <p>
 * The step hand calls {@link #enter} when such a step starts and {@link #leave} when it ends, fails or is cancelled. One
 * running step at a time (the car runs one). Pure, so it is unit-tested.
 */
public final class JobBoundary {
    private JobBoundary() {}

    private static volatile String job;

    /** A job that holds the boundary checks while it runs: the block jobs, the placements, the uses. */
    public static boolean holds(Step s) {
        return s instanceof Step.ChopTree || s instanceof Step.Collect || s instanceof Step.KelpCut || s instanceof Step.MineAt
                || s instanceof Step.Dig || s instanceof Step.Build || s instanceof Step.PlaceNear || s instanceof Step.BuildSchematic
                || s instanceof Step.Light || s instanceof Step.Eat;
    }

    /** The step started: a holding job marks the boundary closed until {@link #leave}. */
    public static void enter(Step s) {
        job = s != null && holds(s) ? s.text() : null;
    }

    /** The step ended (done, failed, cancelled): a boundary. */
    public static void leave() { job = null; }

    /** A holding job runs now: no goal check, no reason-gone end, no replan. */
    public static boolean mid() { return job != null; }

    /** The running job's text (null: at a boundary). */
    public static String job() { return job; }
}
