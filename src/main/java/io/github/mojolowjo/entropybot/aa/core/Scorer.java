package io.github.mojolowjo.entropybot.aa.core;

/**
 * AA v0 (AA_PLAN 7): a score per {@link TaskKind} from the fixed inputs. v0's scorer is hand-written
 * ({@code aa.AaScorer}); a trained network later implements the same interface. Scores are 0..100; a kind that
 * cannot run now scores below 0.
 */
@FunctionalInterface
public interface Scorer {
    double[] score(Inputs in);
}
