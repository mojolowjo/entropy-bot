package io.github.mojolowjo.entropybot.screen;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.blind.BlindRuntime;
import io.github.mojolowjo.entropybot.engine.WatchCamera;
import io.github.mojolowjo.entropybot.engine.WindowCare;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.39.z24 "I drive" on the game: runs {@link DriveState}'s actions (game thread: the screen's button) and keeps
 * every action that could not be done in {@link #problems()}, which the screen shows (never silent). The state is
 * in memory: a restart starts with "I drive" off (AA stays off in that world until {@code aa on}).
 */
public final class DriveRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    public static final DriveRuntime INSTANCE = new DriveRuntime();

    private final DriveState state = new DriveState();
    private volatile boolean on;
    private final List<String> problems = new ArrayList<>();

    private DriveRuntime() {}

    public boolean on() { return on; }

    public String aaOnRefusal() { return state.aaOnRefusal(); }

    public synchronized List<String> problems() { return new ArrayList<>(problems); }

    /** Toggles; returns the line for the screen. */
    public synchronized String toggle() {
        problems.clear();
        List<DriveState.Action> todo = state.on() ? state.leave()
                : state.enter(WindowCare.INSTANCE.freeMouse(), BlindRuntime.INSTANCE.mode().enabled());
        for (DriveState.Action a : todo) {
            String p;
            try {
                p = run(a);
            } catch (Throwable t) {
                p = a + ": " + t;
            }
            if (p != null) problems.add(p);
        }
        on = state.on();
        LOG.info("[entropybot] I drive {}{}", on ? "on" : "off", problems.isEmpty() ? "" : " - not done: " + problems);
        if (on) return "you drive: AA off, Baritone stopped, keys released, camera and cursor yours" + (problems.isEmpty() ? "" : " (see below)");
        return "the bot may drive again: aa on starts AA" + (problems.isEmpty() ? "" : " (see below)");
    }

    /** One action; a problem text or null. */
    private String run(DriveState.Action a) {
        var c = Core.INSTANCE.commands;
        switch (a) {
            case AA_OFF -> { return c.driveAaOff(); }
            case STOP_ALL -> { return c.driveStopAll(); }
            case RELEASE_KEYS -> {
                net.minecraft.client.KeyMapping.releaseAll();
                try {
                    var b = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (b != null) b.getInputOverrideHandler().clearAllKeys();
                } catch (Throwable t) {
                    return "Baritone's forced keys not released: " + t;
                }
                return null;
            }
            case WATCH_OFF -> { WatchCamera.INSTANCE.turnAllOff(); return null; }
            case BLIND_OFF -> { BlindRuntime.INSTANCE.set(false); return BlindRuntime.INSTANCE.mode().enabled() ? "blind mode is still on (blind off)" : null; }
            case BLIND_ON -> { BlindRuntime.INSTANCE.set(true); return null; }
            case MOUSE_GRAB -> { WindowCare.INSTANCE.setFreeMouse(false); return null; }
            case MOUSE_FREE -> { WindowCare.INSTANCE.setFreeMouse(true); return null; }
            case REFLEXES_PAUSE, REFLEXES_RESUME -> { return null; }     // Core reads on() each tick
        }
        return null;
    }
}
