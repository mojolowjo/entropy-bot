package io.github.mojolowjo.entropybot.aa.moves;

import java.util.List;

/**
 * Backbone step 2 ("AA is the driver, Baritone is the car"): what {@link Moves} asks Baritone for. One command at a
 * time; a new one replaces the last. Cells are {x, y, z}. The real one is {@link BaritoneCar}; tests use a fake.
 * Never throws (a failure is a FAILED status with its reason).
 */
public interface Car {
    enum State { RUNNING, DONE, FAILED }

    /**
     * lostControl: the end was another Baritone process taking control (the fork's typed end, never a text match);
     * cell: the cell the end is about as the fork reported it (null: none).
     */
    record Status(State state, String why, boolean lostControl, int[] cell) {
        public Status(State state, String why) { this(state, why, false, null); }
        public static Status running(String why) { return new Status(State.RUNNING, why); }
        public static Status done(String why) { return new Status(State.DONE, why); }
        public static Status failed(String why) { return new Status(State.FAILED, why); }
        public static Status failed(String why, int[] cell) { return new Status(State.FAILED, why, false, cell); }
        public static Status lost(String why) { return new Status(State.FAILED, why, true, null); }
    }

    /** GoalGetToBlock: next to / in this cell. */
    void cover(int[] cell);

    /** GoalRunAway: {@code dist} blocks from every one of these points. */
    void runAway(int dist, List<int[]> from);

    /**
     * Backbone step 4 (the reflexes): GoalRunAway {@code dist} from these points, the goal's heuristic leaning toward
     * {@code prefer} ({dx, dz}, a unit heading; null = none, plain GoalRunAway).
     */
    void runAway(int dist, List<int[]> from, double[] prefer);

    /** Backbone step 4: GoalBlock, feet in this cell (the air reflex's surface cell, the fire reflex's water). */
    void reach(int[] cell);

    /** Backbone step 4: Baritone holds a calculated path for the last goal command (the air reflex's jump gap ends). */
    boolean pathReady();

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
