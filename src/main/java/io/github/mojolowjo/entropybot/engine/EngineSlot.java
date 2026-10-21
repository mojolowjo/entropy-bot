package io.github.mojolowjo.entropybot.engine;

/**
 * 0.39.m2 (review of backbone step 4): what {@link EngineProcess} asks Baritone for, with the reflex save/restore. A
 * reflex goal saves the mode and goal it replaces (a job's or a danger move's hold / override) and puts them back when
 * it ends, so the job is not left idle. A hold / override / release asked while a reflex runs is taken as the new
 * "after the reflex" state (the reflex keeps the wheel). Pure: JUnit runs it. G is Baritone's Goal in the game.
 */
public final class EngineSlot<G> {
    public enum Mode { NONE, HOLD, OVERRIDE }

    private Mode mode = Mode.NONE, savedMode = Mode.NONE;
    private G goal, savedGoal;
    private boolean reflex;

    public Mode mode() { return mode; }
    public G goal() { return goal; }
    public boolean reflexGoal() { return reflex && mode == Mode.OVERRIDE; }

    public void hold() { set(Mode.HOLD, null); }
    public void override(G g) { set(Mode.OVERRIDE, g); }
    public void release() { set(Mode.NONE, null); }

    private void set(Mode m, G g) {
        if (reflex) { savedMode = m; savedGoal = g; return; }
        mode = m;
        goal = g;
    }

    public void overrideReflex(G g) {
        if (!reflex) { savedMode = mode; savedGoal = goal; }
        reflex = true;
        mode = Mode.OVERRIDE;
        goal = g;
    }

    /** The reflex ends: the state it replaced (or one asked for meanwhile) comes back. */
    public void endReflex() {
        if (!reflex) return;
        reflex = false;
        mode = savedMode;
        goal = savedGoal;
        savedMode = Mode.NONE;
        savedGoal = null;
    }

    /** Baritone's cancel: everything goes (the saved state too: the owner's stop means stop). */
    public void lostControl() {
        reflex = false;
        mode = savedMode = Mode.NONE;
        goal = savedGoal = null;
    }
}
