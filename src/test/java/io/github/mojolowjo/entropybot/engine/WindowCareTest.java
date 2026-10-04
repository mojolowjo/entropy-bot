package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.engine.WindowCare.Action;
import io.github.mojolowjo.entropybot.engine.WindowCare.Screen;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** B7e (E1): the bot's window, as the bridge kept it: focus pause off, the mouse free, only the pause and a leftover inventory closed. */
class WindowCareTest {

    static Set<Action> d(long tick, boolean pause, boolean free, boolean active, boolean grabbed, Screen s, boolean job, boolean reflex) {
        return WindowCare.decide(tick, pause, free, active, grabbed, s, job, reflex);
    }

    @Test
    void pauseOnLostFocusGoesOffEvery100Ticks() {
        assertTrue(d(100, true, true, false, false, Screen.NONE, false, false).contains(Action.PAUSE_ON_LOST_FOCUS_OFF));
        assertFalse(d(101, true, true, false, false, Screen.NONE, false, false).contains(Action.PAUSE_ON_LOST_FOCUS_OFF));
        assertFalse(d(100, false, true, false, false, Screen.NONE, false, false).contains(Action.PAUSE_ON_LOST_FOCUS_OFF), "already off");
    }

    @Test
    void theMouseIsReleasedOnlyWhileFocusedAndFree() {
        assertEquals(Set.of(Action.RELEASE_MOUSE), d(7, false, true, true, true, Screen.NONE, false, false), "every tick, not only on the 20s");
        assertTrue(d(7, false, true, false, true, Screen.NONE, false, false).isEmpty(), "unfocused: leave it");
        assertTrue(d(7, false, false, true, true, Screen.NONE, false, false).isEmpty(), "mouse grab: normal Minecraft");
        assertTrue(d(7, false, true, true, false, Screen.NONE, false, false).isEmpty(), "not grabbed");
    }

    @Test
    void thePauseMenuClosesOnlyInTheBackground() {
        assertTrue(d(20, false, true, false, false, Screen.PAUSE, false, false).contains(Action.CLOSE_PAUSE));
        assertFalse(d(20, false, true, true, false, Screen.PAUSE, false, false).contains(Action.CLOSE_PAUSE), "someone pressed Esc in it: they use it");
        assertFalse(d(21, false, true, false, false, Screen.PAUSE, false, false).contains(Action.CLOSE_PAUSE), "every 20 ticks");
        assertTrue(d(20, false, true, false, false, Screen.PAUSE, true, true).contains(Action.CLOSE_PAUSE), "a job doesn't matter for the pause menu");
    }

    @Test
    void aLeftoverInventoryClosesOnlyWhenIdle() {
        assertTrue(d(40, false, true, true, false, Screen.INVENTORY, false, false).contains(Action.CLOSE_INVENTORY));
        assertFalse(d(40, false, true, true, false, Screen.INVENTORY, true, false).contains(Action.CLOSE_INVENTORY), "a job may use it");
        assertFalse(d(40, false, true, true, false, Screen.INVENTORY, false, true).contains(Action.CLOSE_INVENTORY), "a reflex may use it");
    }

    @Test
    void otherScreensAreNeverTouched() {
        assertTrue(d(20, false, true, false, false, Screen.OTHER, false, false).isEmpty(), "a chest, a crafting table, a modded menu");
        assertTrue(d(20, false, true, false, false, Screen.NONE, false, false).isEmpty());
    }

    @Test
    void mouseVerb() {
        WindowCare w = WindowCare.INSTANCE;
        assertEquals("ok: mouse is grabbed normally when the game window has focus", w.mouseCommand("grab"));
        assertFalse(w.freeMouse());
        assertEquals("ok: mouse is free (the game window never keeps the cursor)", w.mouseCommand("free"));
        assertTrue(w.freeMouse());
        w.mouseCommand("grab");
        w.mouseCommand("release");
        assertTrue(w.freeMouse(), "release = free (the bridge's words)");
        assertTrue(w.mouseCommand("").startsWith("ok: mouse is free"), "no word: tells the state");
    }
}
