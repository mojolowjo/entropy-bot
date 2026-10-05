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
    /**
     * Watch camera v1: its hook is in. Since 0.15.2 that is the NeoForge event listeners (ComputeCameraAngles,
     * CalculateDetachedCameraDistanceEvent, ComputeFov), set when EntropyBot registers them; no mixin any more.
     */
    public static volatile boolean watchApplied;
    /** Watch camera v2: the Camera.setPosition invoker (WatchMixinCameraAccess) went in. */
    public static volatile boolean cameraPosApplied;
    /**
     * Watch camera v2 (0.15.4): the terrain-skip hook (WatchMixinLevelRenderer, require = 0) went in. Only a frame-time
     * saving: the tunnel view hides the world by clearing the frame whether or not it applied.
     */
    public static volatile boolean terrainSkipApplied;
    /**
     * The ClientLevel block-change hook (RecorderMixinClientLevel, require = 0) went in: the recorder's ears and the
     * route map's staleness (routing R2).
     */
    public static volatile boolean levelHookApplied;
    /** P1 (0.19.6): the restore ledger's break hook (RestoreMixinGameMode, require = 1) went in. */
    public static volatile boolean restoreApplied;

    private MixinFlags() {}
}
