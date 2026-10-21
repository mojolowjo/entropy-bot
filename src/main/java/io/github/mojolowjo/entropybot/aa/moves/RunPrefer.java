package io.github.mojolowjo.entropybot.aa.moves;

/**
 * Backbone step 4: the FleeHeading lean on Baritone's GoalRunAway. GoalRunAway's heuristic is minus the distance to the
 * nearest mob (any way out is as good); this adds minus {@link #WEIGHT} per block made along the preferred heading from
 * where the run started, so A* opens the cells on the heading first and ends there when that is as cheap. The goal
 * itself (every mob at least {@code dist} away) is unchanged. Pure: JUnit runs it.
 */
public final class RunPrefer {
    /** Heuristic units per block along the heading (Baritone's walk costs about 4.6 per block; GoalXZ's ~3.6). */
    public static double weight() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("run.weight"); }

    private RunPrefer() {}

    /** The added heuristic at (x, z) for a run from (ox, oz) toward the unit heading (hx, hz): lower is better. */
    public static double bias(int x, int z, int ox, int oz, double hx, double hz) {
        return -weight() * ((x - ox) * hx + (z - oz) * hz);
    }

    /** The heading as a unit vector, or null for none / zero. */
    public static double[] unit(double[] h) {
        if (h == null || h.length < 2) return null;
        double n = Math.hypot(h[0], h[1]);
        if (!(n > 1e-6)) return null;
        return new double[]{h[0] / n, h[1] / n};
    }
}
