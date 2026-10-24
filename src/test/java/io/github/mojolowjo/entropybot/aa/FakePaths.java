package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Unit;

/**
 * 0.39.z41 test helper: fakes of Baritone's walk price. The chain has no straight-line price any more; a test world whose
 * walks should stand gets {@link #byDistance}: every walk COMPLETE at the old TIME_MODEL line's seconds (straight metres x
 * 1.3 at walking speed), so the hand sums in the scenarios stay as they were. Tests only: the live provider is Baritone's
 * full A* (commands/LivePathPrice).
 */
public final class FakePaths {
    private FakePaths() {}

    /** The old line factor the scenario sums were written with (test fake only). */
    public static final double LINE_X = 1.3;

    /** The fake's seconds for a walk of {@code metres} straight. */
    public static double walkS(double metres) { return metres * LINE_X / Unit.walkBps(); }

    /** Every walk complete at {@link #walkS} of the chain's straight metres. */
    public static PathPrice byDistance() { return (x, y, z, dist) -> PathPrice.Answer.priced(new PathPrice.Walk(walkS(dist))); }

    /** The world with {@link #byDistance}. */
    public static NeedChain.World paths(NeedChain.World w) { return w.withPath(byDistance()); }
}
