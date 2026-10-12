package io.github.mojolowjo.entropybot.commands;

import java.util.List;

/** Test access to the package-private StairPlan's 0.27.3 staircase up. */
public final class StairPlanUpAccess {
    private StairPlanUpAccess() {}

    public static List<String> upSteps(int x, int y, int z, int dx, int dz, int n) { return StairPlan.upSteps(x, y, z, dx, dz, n); }

    public static int upCount(int y, int surface) { return StairPlan.upCount(y, surface); }
}
