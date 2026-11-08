package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * job-digpace (run JAT 05:08:03-05:14:12: the band leg dug from -100 62 -15, afloat in a lake, 46 levels in 6 min): the dig
 * down leg starts only from a dry column - a floor under the start and no fluid in the column's first {@code band.dryLevels}
 * cells; else it first walks to a dry standable cell within {@code band.dryStepM} (Baritone picks which, through a goal of
 * the dry cells) and digs from there. The price: a dig from a wet column is charged {@code band.wetBreakFactor} (the game's
 * break rule: a player with the eyes in water and no Aqua Affinity breaks at 1/5 speed). Pure; the hands read the cells.
 */
public final class BandDry {
    private BandDry() {}

    /** What the leg does from its start. */
    public enum Act { DIG, STEP_DRY, DIG_WET }

    /**
     * The feet afloat or in water now (the runtime sets it; tests too): the planner's wet-start fact. Default: dry.
     */
    public static volatile BooleanSupplier wetHere = () -> false;

    /** A column is dry: a floor under the start, the feet not in water, no fluid in its first band.dryLevels cells. */
    public static boolean dry(boolean standable, boolean feetInWater, List<Boolean> fluidDown) {
        if (!standable || feetInWater) return false;
        int n = Math.min(fluidDown.size(), Tuning.i("band.dryLevels"));
        for (int i = 0; i < n; i++) if (Boolean.TRUE.equals(fluidDown.get(i))) return false;
        return true;
    }

    /** The leg's act: dry = dig; wet with a dry cell in reach = step to it first; wet with none = dig here, said. */
    public static Act act(boolean dry, int dryCells) {
        if (dry) return Act.DIG;
        return dryCells > 0 ? Act.STEP_DRY : Act.DIG_WET;
    }

    /** The dig's price: the wet start charges band.wetBreakFactor (the game's under-water break rule). */
    public static double price(double digS, boolean wet) {
        return wet ? digS * Tuning.get("band.wetBreakFactor") : digS;
    }

    /** The one line said when the leg steps to a dry cell first. */
    public static String stepWhy(int[] from, int dryCells) {
        return "the start " + from[0] + " " + from[1] + " " + from[2] + " is wet (afloat, no floor or fluid in the first "
                + Tuning.i("band.dryLevels") + " cells): walking first to the nearest of " + dryCells + " dry standable cells within "
                + Tuning.i("band.dryStepM") + " blocks (band.dryStepM), Baritone picks which";
    }
}
