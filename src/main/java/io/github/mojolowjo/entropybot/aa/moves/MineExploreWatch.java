package io.github.mojolowjo.entropybot.aa.moves;

/**
 * 0.39.z77 wood 2 (review of 1207c13b): the spot collect's watch on Baritone's mine explore, one per collect step, pure so
 * the tests drive it. StepHands.mineStatus feeds it each status tick what it reads from Baritone: whether the mine process
 * is the one in control (IPathingControlManager#mostRecentInControl), whether its goal is its explore (GoalRunAway, from
 * MineProcess.updateGoal when it knows none of the blocks), the mine's scan pace (the mineGoalUpdateInterval setting) and
 * its explore Y (legitMineYLevel).
 * <p>Tick-0 race: a fresh mine can show the explore before its first scan has landed (legit: addNearby runs in its own
 * tick; not legit: the rescan runs on Baritone's executor every scan pace). So the explore ends the collect only after the
 * mine has been in control for more than one scan pace of status ticks in a row; a tick it is not in control (another
 * process, a reflex holding) starts the count again.
 */
public final class MineExploreWatch {
    private int inControl;

    /** A new collect: the mine has not been in control yet. */
    public void reset() { inControl = 0; }

    /** Status ticks in a row the mine has been in control (for the tests and the probe). */
    public int inControl() { return inControl; }

    /**
     * One status tick of a collect at {@code spot} (null: none priced, no claim). Null: the collect runs on. Else the
     * failure text, with the spot's near-collect fact said ({@link FactChange#nearCollect}).
     */
    public String tick(Step.Collect spot, int[] feet, boolean mineInControl, boolean explores, int scanTicks, int exploreY, String what, int got, int want) {
        if (!mineInControl) { inControl = 0; return null; }
        inControl++;
        if (spot == null || inControl <= Math.max(1, scanTicks)) return null;
        if (!spot.exploresAway(feet, explores)) return null;
        String why = what + ": Baritone's mine knows no " + spot.item() + " in its sight (" + Step.Collect.MINE_SIGHT + " blocks of the feet) and runs its explore toward y "
                + exploreY + " - the priced spot " + spot.spot() + " (" + Math.round(spot.dist(feet)) + " m) is not what it goes for, got " + got + " of " + want;
        FactChange.nearCollect(spot.item(), spot.at(), why);
        return why;
    }
}
