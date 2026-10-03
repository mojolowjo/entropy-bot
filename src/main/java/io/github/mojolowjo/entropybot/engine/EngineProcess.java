package io.github.mojolowjo.entropybot.engine;

import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * The mod's one Baritone process (docs/BOT_PLAN.md 5.4), at priority -0.5: above Baritone's own
 * processes (-1), below its pause (0). Temporary, so the walk a bridge job asked for keeps its goal
 * while a reflex holds it (a meal: REQUEST_PAUSE) or takes over (a fight, a flight, a retreat:
 * SET_GOAL_AND_PATH), and goes on once the reflex lets go. Five caught errors switch it off; the
 * reflexes then still eat and fight, standing still.
 */
public final class EngineProcess implements IBaritoneProcess {
    private static final Logger LOG = LogUtils.getLogger();

    public enum Mode { NONE, HOLD, OVERRIDE }

    private Mode mode = Mode.NONE;
    private Goal goal;
    private int errors;

    public Mode mode() { return mode; }

    public boolean disabled() { return errors >= 5; }

    /** Baritone stands still, keeping the goal it had. */
    public void hold() {
        mode = Mode.HOLD;
        goal = null;
    }

    /** Baritone walks to this goal instead, until {@link #release()}. */
    public void override(Goal g) {
        mode = Mode.OVERRIDE;
        goal = g;
    }

    public void release() {
        mode = Mode.NONE;
        goal = null;
    }

    @Override
    public boolean isActive() {
        try {
            return !disabled() && mode != Mode.NONE;
        } catch (Throwable t) {
            error(t);
            return false;
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        try {
            if (mode == Mode.HOLD) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            if (mode == Mode.OVERRIDE && goal != null) return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
        } catch (Throwable t) {
            error(t);
        }
        // isActive() is false by now: Baritone moves on to the next process
        mode = Mode.NONE;
        return null;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    /** Baritone's cancel (the bridge's stop): drop the goal; a reflex still running asks again next tick. */
    @Override
    public void onLostControl() {
        mode = Mode.NONE;
        goal = null;
    }

    @Override
    public double priority() {
        return -0.5;
    }

    @Override
    public String displayName0() {
        return "Entropy Bot " + mode.name().toLowerCase();
    }

    private void error(Throwable t) {
        errors++;
        LOG.error("[entropybot] engine process error #{}: {}", errors, t.toString());
        if (disabled()) {
            mode = Mode.NONE;
            LOG.error("[entropybot] engine process switched off after 5 errors; reflexes go on without moving");
        }
    }
}
