package io.github.mojolowjo.entropybot.guard;

/** Set by the Mixin plugin while the game loads: which of the guard's hooks really went in. */
public final class MixinFlags {
    /** The readable Baritone class exists on the classpath (the unoptimized jar, not the ProGuard "api" one). */
    public static volatile boolean astarTargetPresent;
    public static volatile boolean astarApplied;
    /** Baritone walks on modded farmland (FarmlandMixinPrecomputedData, 0.8.2). */
    public static volatile boolean farmlandApplied;
    public static volatile boolean clickApplied;
    public static volatile boolean placeApplied;
    /** Watch camera v1: the render camera's angle hook (WatchMixinCamera) went in. */
    public static volatile boolean watchApplied;
    /**
     * The ClientLevel block-change hook (RecorderMixinClientLevel, require = 0) went in: the recorder's ears and the
     * route map's staleness (routing R2).
     */
    public static volatile boolean levelHookApplied;

    private MixinFlags() {}
}
