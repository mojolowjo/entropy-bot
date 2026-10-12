package io.github.mojolowjo.entropybot.commands;

/**
 * 0.27.4 (live run1 pit: a walk straight up 23 blocks of stone; the dig-out made one step and Baritone still had no path):
 * when a walk fails underground inside the areas and its goal is above the bot, dig a staircase up toward the goal, then
 * walk on. At most {@link #MAX_STAIRS} staircases per goal. Pure: the caller reads the bot's state and acts on the answer.
 */
public final class SealedWalk {
    private SealedWalk() {}

    public static final int MAX_STAIRS = 2;

    /**
     * True when a staircase up should start: underground, inside the areas, the walk failed, the goal is more than 2
     * blocks above the feet, and fewer than MAX_STAIRS staircases were dug for this goal.
     */
    public static boolean want(boolean underground, boolean inAreas, int[] goal, int[] feet, boolean walkFailed, int staircasesSoFar) {
        return underground && inAreas && walkFailed && goal != null && feet != null
                && goal.length >= 2 && feet.length >= 2 && goal[1] > feet[1] + 2 && staircasesSoFar < MAX_STAIRS;
    }

    /** The staircase's steps: the height difference plus one, at most StairPlan.MAX. */
    public static int steps(int[] goal, int[] feet) {
        return Math.min(StairPlan.MAX, goal[1] - feet[1] + 1);
    }
}
