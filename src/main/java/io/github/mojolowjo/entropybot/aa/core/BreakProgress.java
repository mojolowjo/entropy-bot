package io.github.mojolowjo.entropybot.aa.core;

/**
 * 0.39.z76 sb4 review (the progress tick's break stage): a change of the game's break stage is progress only between two
 * stages (0-9). Baritone's stopBreakingBlock can leave a stale break progress behind, which the stage read shows on any
 * arm swing (a mob hit, a placement) and hides again: a -1 &lt;-&gt; stale-stage flip is no progress. A new block's first
 * stage (-1 -&gt; 0) is not counted either; its next stage is. Pure (JUnit: Z76SendBack4Test). Loader notes: none.
 */
public final class BreakProgress {
    private BreakProgress() {}

    /** True: {@code last} -&gt; {@code now} is a change between two stages (both 0 or more). */
    public static boolean counts(int last, int now) {
        return last >= 0 && now >= 0 && now != last;
    }
}
