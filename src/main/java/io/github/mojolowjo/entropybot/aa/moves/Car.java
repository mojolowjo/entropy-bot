package io.github.mojolowjo.entropybot.aa.moves;

import java.util.List;

/**
 * Backbone step 2 ("AA is the driver, Baritone is the car"): what {@link Moves} asks Baritone for. One command at a
 * time; a new one replaces the last. Cells are {x, y, z}. The real one is {@link BaritoneCar}; tests use a fake.
 * Never throws (a failure is a FAILED status with its reason).
 */
public interface Car {
    enum State { RUNNING, DONE, FAILED }

    record Status(State state, String why) {
        public static Status running(String why) { return new Status(State.RUNNING, why); }
        public static Status done(String why) { return new Status(State.DONE, why); }
        public static Status failed(String why) { return new Status(State.FAILED, why); }
    }

    /** GoalGetToBlock: next to / in this cell. */
    void cover(int[] cell);

    /** GoalRunAway: {@code dist} blocks from every one of these points. */
    void runAway(int dist, List<int[]> from);

    /** GoalBlock {@code top} with allowPlace on and the bag's throwaway list (planks, cobble, dirt...): Baritone pillars. */
    void pillar(int[] top);

    /** The fork's BurrowProcess: dig these cells, step into {@code stand} (null = stay), seal these. */
    void burrow(List<int[]> dig, int[] stand, List<int[]> seal, int timeoutTicks);

    /** The LookBehavior target this tick (the one camera owner). */
    void look(double x, double y, double z);

    /** The last command's state now. */
    Status status();

    /** Ends the last command; Baritone keeps holding the job (the danger hold) until {@link #release()}. */
    void stop(String why);

    /** Baritone back to the job. */
    void release();
}
