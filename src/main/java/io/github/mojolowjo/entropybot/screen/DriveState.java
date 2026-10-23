package io.github.mojolowjo.entropybot.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.39.z24 "I drive": the person at the keyboard plays the game; the bot lets go of everything. Pure state machine
 * (JUnit): {@link #enter} says what to switch off and remembers what was the mod's, {@link #leave} what to put back.
 * {@link DriveRuntime} does the actions on the game and reports each one that could not be done.
 */
public final class DriveState {
    public enum Action {
        /** enter: AA off in this world (the per-world switch), its look settings (freeLook, smoothLook, randomLooking) back to the player's. */
        AA_OFF,
        /** enter: every job, chain and step run stopped, Baritone cancelled. */
        STOP_ALL,
        /** enter: Baritone's forced inputs and every held key released. */
        RELEASE_KEYS,
        /** enter: the watch camera / tunnel view off. */
        WATCH_OFF,
        /** enter, only when it was on: blind mode off (frames drawn again). */
        BLIND_OFF,
        /** enter, only when the mod kept the cursor free: the cursor is grabbed normally again. */
        MOUSE_GRAB,
        /** enter: reflexes and the window care pause. */
        REFLEXES_PAUSE,
        /** leave: the cursor free again (it was the mod's setting). */
        MOUSE_FREE,
        /** leave: blind mode on again (it was on). */
        BLIND_ON,
        /** leave: reflexes and the window care run again. */
        REFLEXES_RESUME
    }

    private boolean on, savedFreeMouse, savedBlind;

    public boolean on() { return on; }

    public List<Action> enter(boolean freeMouse, boolean blind) {
        List<Action> out = new ArrayList<>();
        if (on) return out;
        on = true;
        savedFreeMouse = freeMouse;
        savedBlind = blind;
        out.add(Action.AA_OFF);
        out.add(Action.STOP_ALL);
        out.add(Action.RELEASE_KEYS);
        out.add(Action.WATCH_OFF);
        if (blind) out.add(Action.BLIND_OFF);
        if (freeMouse) out.add(Action.MOUSE_GRAB);
        out.add(Action.REFLEXES_PAUSE);
        return out;
    }

    public List<Action> leave() {
        List<Action> out = new ArrayList<>();
        if (!on) return out;
        on = false;
        if (savedFreeMouse) out.add(Action.MOUSE_FREE);
        if (savedBlind) out.add(Action.BLIND_ON);
        out.add(Action.REFLEXES_RESUME);
        return out;
    }

    /** aa on while the owner drives: refused with this; null = allowed. */
    public String aaOnRefusal() {
        return on ? "aa stays off: the owner drives (config screen: I drive off first)" : null;
    }
}
