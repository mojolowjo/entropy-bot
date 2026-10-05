package io.github.mojolowjo.entropybot.watchview;

import io.github.mojolowjo.entropybot.commands.SelfCheck;

import java.util.ArrayList;
import java.util.List;

/**
 * The watch camera's findings for {@code check} (docs/PLANNING.md section 3 and 5): listeners not registered, the
 * camera-position mixin not applied, the tunnel view on but not drawing, turned off by an error, frames that had to be
 * cleared late because the world-hiding step missed them, and the terrain-skip hook not applied or not firing. Pure
 * (JUnit).
 */
public final class WatchChecks {
    private WatchChecks() {}

    /**
     * The tunnel view is on, frames are being rendered (one in the last second), but none ran the view's drawing for a
     * second. A stalled game (no frames at all) is not counted: that is a hitch, not the view's fault. -1 = never.
     */
    public static boolean notDrawing(boolean tunnelOn, long onForMs, long msSinceDraw, long msSinceFrame) {
        if (!tunnelOn || onForMs <= 1500) return false;
        boolean framesRendered = msSinceFrame >= 0 && msSinceFrame <= 1000;
        return framesRendered && (msSinceDraw < 0 || msSinceDraw > 1000);
    }

    public static List<SelfCheck.Finding> findings(boolean listeners, boolean positionHook, boolean tunnelOn, long msSinceDraw,
                                                   long onForMs, long msSinceFrame, String offByError) {
        List<SelfCheck.Finding> out = new ArrayList<>();
        if (!listeners) out.add(new SelfCheck.Finding("watchhook",
                "the watch camera's event listeners are not registered: watch shows plain third person",
                "look for [entropybot] errors at the start of the game log"));
        if (!positionHook) out.add(new SelfCheck.Finding("camerapos",
                "the camera position hook (WatchMixinCameraAccess) is not applied: watch tunnel can't place its camera",
                "look for a mixin error in the game log after the next restart"));
        if (notDrawing(tunnelOn, onForMs, msSinceDraw, msSinceFrame)) out.add(new SelfCheck.Finding("tunneldraw",
                "watch tunnel is on and frames are rendered, but none ran its drawing in the last second",
                "watch status, then watch tunnel off"));
        if (offByError != null) out.add(new SelfCheck.Finding("tunnelerror",
                "watch tunnel turned itself off after an error: " + offByError,
                "watch status; the log has [entropybot] watch tunnel lines"));
        return out;
    }

    /**
     * The world-hiding findings (0.15.4). misses: frames rendered with the view on but without the veil (each was
     * cleared late, at the frame's end, so the world never showed; 3 in a row turn the view off). skipApplied/skipped:
     * the terrain-skip hook and how many layers it skipped since the view went on (0 after 3 s = not firing).
     */
    public static List<SelfCheck.Finding> veilFindings(long misses, boolean skipApplied, boolean tunnelOn, long onForMs, long skippedSinceOn) {
        List<SelfCheck.Finding> out = new ArrayList<>();
        if (misses > 0) out.add(new SelfCheck.Finding("tunnelhide",
                "watch tunnel: " + misses + " frame(s) were rendered without its world-hiding step (cleared at the frame's end, so the world did not show, but the step is not reliable)",
                "watch tunnel status; the log has [entropybot] watch tunnel lines"));
        if (!skipApplied) out.add(new SelfCheck.Finding("tunnelskip",
                "the terrain-skip hook (WatchMixinLevelRenderer) is not applied: watch tunnel still hides the world, but draws the terrain first (frame time)",
                "look for a mixin error about WatchMixinLevelRenderer in the game log"));
        else if (tunnelOn && onForMs > 3000 && skippedSinceOn == 0) out.add(new SelfCheck.Finding("tunnelskip",
                "the terrain-skip hook is applied but not firing (another mod may replace LevelRenderer.renderSectionLayer after it): watch tunnel still hides the world, it only costs frame time",
                "watch tunnel status; note the mods that touch LevelRenderer"));
        return out;
    }

    /** watch seen (0.16.0): on, in a world, on for over 2 s, and nothing sampled for 2 s (-1 = never). */
    public static boolean seenStalled(boolean on, boolean inWorld, long onForMs, long msSinceSample) {
        if (!on || !inWorld || onForMs <= 2000) return false;
        return msSinceSample < 0 || msSinceSample > 2000;
    }

    /**
     * 0.16.1 check {@code tunnelcut}: the cutaway is on (the default) but its shader is not available (failed to load,
     * compile or link, broke while drawing, or was never registered). The view then draws every face without the cut.
     * Null when there is nothing to report (cut off, or the shader is fine).
     */
    public static String cutProblem(boolean cutOn, String shaderProblem) {
        if (!cutOn || shaderProblem == null) return null;
        return "watch tunnel cut is on, but the cutaway shader is not available (" + shaderProblem
                + "): the tunnel view draws every face, also those between the camera and the bot";
    }

    /** The status words for the terrain-skip hook. perSec -1 = not measured yet. */
    public static String skipReport(boolean applied, boolean tunnelOn, long perSec, long total) {
        if (!applied) return "drawn, then cleared (skip hook NOT applied: costs frame time, hides nothing less)";
        if (!tunnelOn) return "normal (view off; skip hook applied, " + total + " layers skipped so far)";
        if (perSec == 0) return "drawn, then cleared (skip hook applied but NOT firing)";
        return "skipped" + (perSec > 0 ? " (" + perSec + " layers/s)" : "");
    }
}
