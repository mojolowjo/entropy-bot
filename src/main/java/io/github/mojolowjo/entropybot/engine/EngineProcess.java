package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

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
 * reflexes then still eat and fight, standing still. The state is {@link EngineSlot} (0.39.m2: a reflex goal saves and
 * restores what it replaced).
 */
public final class EngineProcess implements IBaritoneProcess {
    private static final Logger LOG = LogUtils.getLogger();

    public enum Mode { NONE, HOLD, OVERRIDE }

    private final EngineSlot<Goal> slot = new EngineSlot<>();
    private int errors;

    public synchronized Mode mode() { return Mode.valueOf(slot.mode().name()); }

    public boolean disabled() { return errors >= Tuning.i("engineProcess.errorsOff"); }

    /** Backbone step 2: ticks Baritone reported a failed path calculation for an override goal (Moves fails a leg on it). */
    private volatile long calcFails;

    public long calcFails() { return calcFails; }

    /** Baritone stands still, keeping the goal it had (during a reflex: the state after it). */
    public synchronized void hold() { slot.hold(); }

    /** Baritone walks to this goal instead, until {@link #release()} (during a reflex: after it). */
    public synchronized void override(Goal g) { slot.override(g); }

    public synchronized void release() { slot.release(); }

    /**
     * Backbone step 4: a reflex goal (the air swim-up, the creeper flee, the lava/fire escape) runs above every other
     * Baritone process (the fork's BurrowProcess -0.25, Baritone's own -1, its pause 0): {@link #REFLEX_PRIORITY} while
     * it lasts. PathingControlManager sorts the active processes by priority every tick, so the goal is taken on
     * Baritone's next tick. A BurrowProcess running then loses control and fails (its chain moves on, logged).
     */
    public static final double REFLEX_PRIORITY = 10; // structural

    /** Backbone step 4: the reflex goal; the mode and goal it replaces come back at {@link #endReflex()}. */
    public synchronized void overrideReflex(Goal g) { slot.overrideReflex(g); }

    /** 0.39.m2: the reflex goal ends; the job's hold / override before it is back. */
    public synchronized void endReflex() { slot.endReflex(); }

    /** A reflex goal holds the engine now. */
    public synchronized boolean reflexGoal() { return slot.reflexGoal(); }

    @Override
    public boolean isActive() {
        try {
            return !disabled() && mode() != Mode.NONE;
        } catch (Throwable t) {
            error(t);
            return false;
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        try {
            Mode m = mode();
            Goal g;
            synchronized (this) { g = slot.goal(); }
            if (m == Mode.OVERRIDE && calcFailed) calcFails++;
            if (m == Mode.HOLD) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            if (m == Mode.OVERRIDE && g != null) return new PathingCommand(g, PathingCommandType.SET_GOAL_AND_PATH);
        } catch (Throwable t) {
            error(t);
        }
        // isActive() is false by now: Baritone moves on to the next process
        synchronized (this) { slot.lostControl(); }
        return null;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    /** Baritone's cancel (the bridge's stop): drop the goal; a reflex still running asks again next tick. */
    @Override
    public synchronized void onLostControl() {
        slot.lostControl();
    }

    @Override
    public double priority() {
        return reflexGoal() ? REFLEX_PRIORITY : -0.5;
    }

    @Override
    public String displayName0() {
        return "Entropy Bot " + mode().name().toLowerCase() + (reflexGoal() ? " (reflex)" : "");
    }

    private void error(Throwable t) {
        errors++;
        LOG.error("[entropybot] engine process error #{}: {}", errors, t.toString());
        if (disabled()) {
            synchronized (this) { slot.lostControl(); }
            LOG.error("[entropybot] engine process switched off after 5 errors; reflexes go on without moving");
        }
    }
}
