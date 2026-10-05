package io.github.mojolowjo.entropybot.watchview;

import io.github.mojolowjo.entropybot.commands.SelfCheck;

import java.util.ArrayList;
import java.util.List;

/**
 * The watch camera's findings for {@code check} (docs/PLANNING.md section 3 and 5): listeners not registered, the
 * camera-position mixin not applied, the tunnel view on but not drawing, or turned off by an error. Pure (JUnit).
 */
public final class WatchChecks {
    private WatchChecks() {}

    public static List<SelfCheck.Finding> findings(boolean listeners, boolean positionHook, boolean tunnelOn, long msSinceDraw,
                                                   long onForMs, String offByError) {
        List<SelfCheck.Finding> out = new ArrayList<>();
        if (!listeners) out.add(new SelfCheck.Finding("watchhook",
                "the watch camera's event listeners are not registered: watch shows plain third person",
                "look for [entropybot] errors at the start of the game log"));
        if (!positionHook) out.add(new SelfCheck.Finding("camerapos",
                "the camera position hook (WatchMixinCameraAccess) is not applied: watch tunnel can't place its camera",
                "look for a mixin error in the game log after the next restart"));
        if (tunnelOn && onForMs > 1500 && (msSinceDraw < 0 || msSinceDraw > 1000)) out.add(new SelfCheck.Finding("tunneldraw",
                "watch tunnel is on but no frame drew its faces in the last second",
                "watch status, then watch tunnel off"));
        if (offByError != null) out.add(new SelfCheck.Finding("tunnelerror",
                "watch tunnel turned itself off after an error: " + offByError,
                "watch status; the log has [entropybot] watch tunnel lines"));
        return out;
    }
}
