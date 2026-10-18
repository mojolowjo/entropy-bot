package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.Scorer;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;

/**
 * AA v0 (AA_PLAN 7, 9): the hand-written idle scorer. Only the idle kinds get a score; the rest stay 0 (the ladder,
 * not a score, decides them). Wood and stone score higher the less the bag holds; below 0 = cannot run (enough in
 * the bag already). WAIT is the floor. A trained network implements the same interface later.
 */
public final class AaScorer implements Scorer {
    public static final int STOCK_TARGET = 64;

    @Override public double[] score(Inputs in) {
        double[] s = new double[TaskKind.values().length];
        s[TaskKind.STOCK_WOOD.ordinal()] = in.wood >= STOCK_TARGET ? -1 : 60 - in.wood * 50.0 / STOCK_TARGET;
        s[TaskKind.STOCK_STONE.ordinal()] = in.cobble >= STOCK_TARGET ? -1 : 50 - in.cobble * 45.0 / STOCK_TARGET;
        if (in.pickTier < 1) s[TaskKind.STOCK_STONE.ordinal()] = -1;      // no pickaxe: stone cannot be mined
        s[TaskKind.WAIT.ordinal()] = 10;
        return s;
    }
}
