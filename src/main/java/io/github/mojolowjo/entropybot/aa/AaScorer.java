package io.github.mojolowjo.entropybot.aa;

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
    public static final int STOCK_TARGET = 64;
    public static final int WOOD_LO = 32, WOOD_HI = 64, STONE_LO = 32, STONE_HI = 64, COAL_LO = 8, COAL_HI = 32,
            IRON_LO = 4, IRON_HI = 16, TORCH_LO = 32, TORCH_HI = 64, DEPOSIT_BELOW = 10, LIGHT_TORCHES = 8;
    public static final double LIGHT_SCORE = 40, WAIT_SCORE = 10, ON_FLOOR = 12;

    final Band wood = new Band(WOOD_LO, WOOD_HI), stone = new Band(STONE_LO, STONE_HI), coal = new Band(COAL_LO, COAL_HI),
            iron = new Band(IRON_LO, IRON_HI), torches = new Band(TORCH_LO, TORCH_HI), bag = new Band(DEPOSIT_BELOW, DEPOSIT_BELOW + 6);

    /** The band's deficit 0..100, or -1 when it is off. */
    static double deficit(Band b, int have) {
        if (!b.update(have)) return -1;
        // review: a band that is on never scores under WAIT (else it stays on and never runs)
        return Math.max(ON_FLOOR, Math.min(100, 100.0 * (b.leaveAt - have) / b.leaveAt));
    }

    @Override public double[] score(Inputs in) {
        double[] s = new double[TaskKind.values().length];
        s[TaskKind.STOCK_WOOD.ordinal()] = deficit(wood, in.wood);
        s[TaskKind.STOCK_STONE.ordinal()] = in.pickTier < 1 ? -1 : deficit(stone, in.cobble);
        s[TaskKind.STOCK_COAL.ordinal()] = in.pickTier < 1 ? -1 : deficit(coal, in.coal);
        s[TaskKind.STOCK_IRON.ordinal()] = in.pickTier < 2 ? -1 : deficit(iron, in.ironIngots);
        s[TaskKind.STOCK_TORCHES.ordinal()] = deficit(torches, in.torches);
        // deposit: free slots under 10 until 16, only with base chests scanned
        double d = in.baseChests ? deficit(bag, in.freeSlots) : -1;
        s[TaskKind.DEPOSIT.ordinal()] = d;
        s[TaskKind.LIGHT.ordinal()] = in.night && !in.litTonight && in.torches >= LIGHT_TORCHES ? LIGHT_SCORE : -1;
        s[TaskKind.WAIT.ordinal()] = WAIT_SCORE;
        return s;
    }
}
