package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import io.github.mojolowjo.entropybot.aa.core.Band;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.Scorer;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;

/**
 * AA v0 (AA_PLAN 7, 9), S1 (MINING_PLAN 12, owner's targets 2026-10-18): the idle scorer. Each stock-up has a
 * {@link Band} (enter below, leave at the target) so a task has a stop and a met stock does not flicker back on; the
 * score is the band's deficit, 0..100 (100 x missing / target), -1 when the band is off or the option cannot run
 * (no pickaxe for stone/coal, no stone pickaxe for iron, no base chests for deposit). The cost side of 12 (cost / 10)
 * is 0 here: the scorer runs before the planner and has no cost yet (assumed good enough; the inertia bonus keeps the
 * winner). Light the camp: night and 8+ torches, a fixed 40. WAIT is the floor (10). Only the idle kinds get a score.
 */
public final class AaScorer implements Scorer {
    public static int stockTarget() { return Tuning.i("aaScorer.stockTarget"); }
    public static int woodLo() { return Tuning.i("aaScorer.woodLo"); }
    public static int woodHi() { return Tuning.i("aaScorer.woodHi"); }
    public static int stoneLo() { return Tuning.i("aaScorer.stoneLo"); }
    public static int stoneHi() { return Tuning.i("aaScorer.stoneHi"); }
    public static int coalLo() { return Tuning.i("aaScorer.coalLo"); }
    public static int coalHi() { return Tuning.i("aaScorer.coalHi"); }
    public static int ironLo() { return Tuning.i("aaScorer.ironLo"); }
    public static int ironHi() { return Tuning.i("aaScorer.ironHi"); }
    public static int torchLo() { return Tuning.i("aaScorer.torchLo"); }
    public static int torchHi() { return Tuning.i("aaScorer.torchHi"); }
    public static int depositBelow() { return Tuning.i("aaScorer.depositBelow"); }
    public static int lightTorches() { return Tuning.i("aaScorer.lightTorches"); }
    public static double lightScore() { return Tuning.get("aaScorer.lightScore"); }
    public static double waitScore() { return Tuning.get("aaScorer.waitScore"); }
    public static double onFloor() { return Tuning.get("aaScorer.onFloor"); }

    final Band wood = new Band(woodLo(), woodHi()), stone = new Band(stoneLo(), stoneHi()), coal = new Band(coalLo(), coalHi()),
            iron = new Band(ironLo(), ironHi()), torches = new Band(torchLo(), torchHi()), bag = new Band(depositBelow(), depositBelow() + Tuning.i("aaScorer.bagBandWidth"));

    /** The band's deficit 0..100, or -1 when it is off. */
    static double deficit(Band b, int have) {
        if (!b.update(have)) return -1;
        // review: a band that is on never scores under WAIT (else it stays on and never runs)
        return Math.max(onFloor(), Math.min(100, 100.0 * (b.leaveAt - have) / b.leaveAt));  // structural: percent
    }

    @Override public double[] score(Inputs in) {
        double[] s = new double[TaskKind.values().length];
        s[TaskKind.STOCK_WOOD.ordinal()] = deficit(wood, in.wood);
        s[TaskKind.STOCK_STONE.ordinal()] = in.pickTier < 1 ? -1 : deficit(stone, in.cobble);
        s[TaskKind.STOCK_COAL.ordinal()] = in.pickTier < 1 ? -1 : deficit(coal, in.coal);
        s[TaskKind.STOCK_IRON.ordinal()] = in.pickTier < 2 ? -1 : deficit(iron, in.ironIngots);
        s[TaskKind.STOCK_TORCHES.ordinal()] = in.torchCoal() ? deficit(torches, in.torches) : -1;   // the coal goal's reserve
        // deposit: free slots under 10 until 16, only with base chests scanned
        double d = in.baseChests ? deficit(bag, in.freeSlots) : -1;
        s[TaskKind.DEPOSIT.ordinal()] = d;
        s[TaskKind.LIGHT.ordinal()] = in.night && !in.litTonight && in.torches >= lightTorches() ? lightScore() : -1;
        s[TaskKind.WAIT.ordinal()] = waitScore();
        return s;
    }
}
