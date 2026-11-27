/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Entropy Bot 2026-11-27 (nav step 8): the driver that turns one order (look, walk, break, place) into keys, a head
 * turn and clicks tick by tick, as Baritone's Movement.update / PathExecutor do for one movement, here with no game
 * types and every end an observed state.
 */

package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * One task at a time: a look, a walk, a break or a place. Each tick {@link DriveLive} hands in what the game shows
 * ({@link See}) and gets back what to do ({@link Act}): the keys down, where the head turns, whether to hit the block
 * under the crosshair or use the item on it, and, once, how the task ended. Every task ends on an observed state or a
 * named failure with its tick count (rule 7: never "I clicked"): a look reached or not, a walk's blocks moved or why it
 * stopped, a block gone or still there and why, a block there or not. The driver lets go when the game pauses, the
 * world changes or Baritone starts driving (the two never drive together). Pure (JUnit: DriverTest). Loader notes:
 * none here; {@link DriveLive} is the game side.
 */
public final class Driver {

    /** An order. */
    public sealed interface Task permits Look, Walk, Break, Place {}

    /** Turn the head to this yaw and pitch. */
    public record Look(float yaw, float pitch) implements Task {}

    /** Walk forward (the way the head faces) this many blocks. */
    public record Walk(double blocks) implements Task {}

    /** Break the block at this cell. */
    public record Break(BetterBlockPos pos) implements Task {}

    /** Place a block into this cell. */
    public record Place(BetterBlockPos pos) implements Task {}

    /**
     * The task's block as the game shows it this tick.
     *
     * @param empty     the cell is air or replaceable
     * @param aim       where the head must look (a break: the block's nearest visible face; a place: the support's face)
     * @param onTarget  the crosshair is on that block (and, for a place, on that face of the support)
     * @param progress  the game's break progress per tick with the tool in hand (0: it will not break)
     * @param refusal   why the task cannot be done at all (out of reach, no support face, no block in the bag, the
     *                  guard), null when it can
     * @param under     what the crosshair is on (for a failure line)
     */
    public record Target(boolean empty, LookMath.Turn aim, boolean onTarget, float progress, String refusal, String under) {}

    /**
     * What the game shows this tick.
     *
     * @param yaw    the head's yaw
     * @param pitch  the head's pitch
     * @param x      the feet's x
     * @param z      the feet's z
     * @param paused the game is paused
     * @param baritone Baritone is driving (pathing or a process in control)
     * @param sameWorld the world is the one the task started in
     * @param target the task's block (null for a look or a walk)
     */
    public record See(float yaw, float pitch, double x, double z, boolean paused, boolean baritone, boolean sameWorld, Target target) {}

    /**
     * What to do this tick.
     *
     * @param keys    the keys down (empty: every key let go)
     * @param look    where the head turns this tick (null: it stays)
     * @param hit     hit the block under the crosshair (hold a break)
     * @param use     use the item in the hand on the block under the crosshair (one right click)
     * @param ended   how the task ended (null: it runs on)
     * @param ok      the task did what it was asked
     */
    public record Act(Set<Keys.Key> keys, LookMath.Turn look, boolean hit, boolean use, String ended, boolean ok) {
        static Act idle() { return new Act(EnumSet.noneOf(Keys.Key.class), null, false, false, null, false); }
    }

    /** A place holds sneak, as Baritone does, so a support that has a menu (a chest, a table) takes the block. */
    private static final Set<Keys.Key> SNEAK = EnumSet.of(Keys.Key.SNEAK);

    private final DriveConfig config;
    private Task task;
    private int ticks;
    /** A walk's start and best progress; a break's hold start and budget; a place's click count and delay. */
    private double startX, startZ, best;
    private int lastGain, holdStart = -1, budget, clicks, delay;
    private float walkYaw;

    public Driver(DriveConfig config) {
        this.config = config;
    }

    /** The running task, or null. */
    public Task task() { return task; }

    /** Starts a task (the one running, if any, ends as "replaced"). Returns what was replaced, or null. */
    public String start(Task t) {
        String was = task == null ? null : name(task) + " replaced by " + name(t);
        task = t;
        ticks = 0;
        best = 0;
        lastGain = 0;
        holdStart = -1;
        budget = 0;
        clicks = 0;
        delay = 0;
        return was;
    }

    /** Ends the running task from outside (the owner's stop). Returns its line, or null when none ran. */
    public String stop(String why) {
        if (task == null) {
            return null;
        }
        String line = name(task) + ": stopped after " + ticks + " ticks (" + why + ")";
        task = null;
        return line;
    }

    /** One tick. */
    public Act tick(See s) {
        if (task == null) {
            return Act.idle();
        }
        if (s.paused()) {
            return end("let go after " + ticks + " ticks: the game paused", false);
        }
        if (!s.sameWorld()) {
            return end("let go after " + ticks + " ticks: the world changed", false);
        }
        if (s.baritone()) {
            return end("let go after " + ticks + " ticks: Baritone is driving (the two never drive together)", false);
        }
        ticks++;
        return switch (task) {
            case Look l -> look(s, l);
            case Walk w -> walk(s, w);
            case Break b -> breakTick(s, b);
            case Place p -> place(s, p);
        };
    }

    private Act look(See s, Look l) {
        if (LookMath.reached(s.yaw(), s.pitch(), l.yaw(), l.pitch(), config.lookWithin())) {
            return end(String.format(Locale.ROOT, "reached yaw %.1f pitch %.1f in %d ticks", s.yaw(), s.pitch(), ticks - 1), true);
        }
        if (ticks > config.lookTimeout()) {
            return end(String.format(Locale.ROOT, "not reached in %d ticks: at yaw %.1f pitch %.1f", config.lookTimeout(), s.yaw(), s.pitch()), false);
        }
        return run(Set.of(), new LookMath.Turn(l.yaw(), l.pitch()), false, false);
    }

    private Act walk(See s, Walk w) {
        if (ticks == 1) {
            startX = s.x();
            startZ = s.z();
            walkYaw = s.yaw();
        }
        double moved = Math.hypot(s.x() - startX, s.z() - startZ);
        if (moved >= w.blocks()) {
            return end(String.format(Locale.ROOT, "moved %.2f blocks in %d ticks", moved, ticks - 1), true);
        }
        if (moved >= best + 0.1) {                                            // structural: a tenth of a block counts as progress
            best = moved;
            lastGain = ticks;
        }
        if (ticks - lastGain > config.stuckTicks()) {
            return end(String.format(Locale.ROOT, "stuck: moved %.2f of %s blocks in %d ticks, none in the last %d (something in the way)",
                    moved, num(w.blocks()), ticks - 1, config.stuckTicks()), false);
        }
        int limit = (int) Math.ceil(w.blocks() * config.walkTicksPerBlock()) + config.stuckTicks();
        if (ticks > limit) {
            return end(String.format(Locale.ROOT, "moved %.2f of %s blocks in %d ticks: too slow", moved, num(w.blocks()), limit), false);
        }
        return run(EnumSet.of(Keys.Key.FORWARD), new LookMath.Turn(walkYaw, s.pitch()), false, false);
    }

    private Act breakTick(See s, Break b) {
        Target t = s.target();
        if (t.empty()) {
            return ticks == 1 ? end("nothing to break at " + cell(b.pos()) + " (it is air)", false)
                    : end("block gone in " + (ticks - 1) + " ticks (" + (holdStart < 0 ? 0 : ticks - holdStart) + " holding)", true);
        }
        if (t.refusal() != null) {
            return end("not broken: " + t.refusal(), false);
        }
        boolean aimed = LookMath.reached(s.yaw(), s.pitch(), t.aim().yaw(), t.aim().pitch(), config.lookWithin());
        if (holdStart < 0) {
            if (aimed && t.onTarget()) {
                if (t.progress() <= 0f) {
                    return end("not broken: no break progress with the tool in hand (unbreakable here, or the guard refuses)", false);
                }
                holdStart = ticks;
                budget = (int) Math.ceil(1f / t.progress()) + config.breakMargin();
            } else if (ticks > config.lookTimeout()) {
                return end("still there after " + (ticks - 1) + " ticks: " + (aimed ? "wrong block under the crosshair: " + t.under()
                        : "the look did not arrive"), false);
            } else {
                return run(Set.of(), t.aim(), false, false);
            }
        }
        if (!t.onTarget() && !aimed) {
            holdStart = -1;                                                   // review 2026-11-27: off the block, the game's progress restarts: aim again
            return run(Set.of(), t.aim(), false, false);
        }
        if (ticks - holdStart > budget) {
            return end("still there after " + (ticks - 1) + " ticks (" + (ticks - holdStart) + " holding, the game's time "
                    + (int) Math.ceil(1f / Math.max(t.progress(), 1e-6f)) + "): " + (t.onTarget() ? "under the crosshair, the break did not finish" : "wrong block under the crosshair: " + t.under()), false);
        }
        return run(Set.of(), t.aim(), t.onTarget(), false);
    }

    private Act place(See s, Place p) {
        Target t = s.target();
        if (!t.empty()) {
            return clicks == 0 ? end("not placed: " + cell(p.pos()) + " is not empty (" + t.under() + ")", false)
                    : end("block there in " + (ticks - 1) + " ticks (" + clicks + " click" + (clicks == 1 ? "" : "s") + ")", true);
        }
        if (t.refusal() != null) {
            return end("not placed: " + t.refusal(), false);
        }
        boolean aimed = LookMath.reached(s.yaw(), s.pitch(), t.aim().yaw(), t.aim().pitch(), config.lookWithin());
        if (holdStart < 0) {
            if (aimed && t.onTarget()) {
                holdStart = ticks;
            } else if (ticks > config.lookTimeout()) {
                return end("not there after " + (ticks - 1) + " ticks: " + (aimed ? "wrong face under the crosshair: " + t.under()
                        : "the look did not arrive"), false);
            } else {
                return run(SNEAK, t.aim(), false, false);
            }
        }
        if (ticks - holdStart > config.placeTimeout()) {
            return end("not there after " + (ticks - 1) + " ticks (" + clicks + " clicks): " + (t.onTarget() ? "the click placed nothing" : "wrong face under the crosshair: " + t.under()), false);
        }
        boolean click = delay <= 0 && t.onTarget();
        if (click) {
            clicks++;
            delay = config.placeDelay();
        } else {
            delay--;
        }
        return run(SNEAK, t.aim(), false, click);
    }

    private Act run(Set<Keys.Key> keys, LookMath.Turn look, boolean hit, boolean use) {
        return new Act(keys.isEmpty() ? EnumSet.noneOf(Keys.Key.class) : EnumSet.copyOf(keys), look, hit, use, null, false);
    }

    private Act end(String why, boolean ok) {
        String line = name(task) + ": " + why;
        task = null;
        return new Act(EnumSet.noneOf(Keys.Key.class), null, false, false, line, ok);
    }

    /** A task's name for the log ("look 90 0", "break 1 64 2"). */
    public static String name(Task t) {
        return switch (t) {
            case Look l -> "look " + num(l.yaw()) + " " + num(l.pitch());
            case Walk w -> "walk " + num(w.blocks());
            case Break b -> "break " + cell(b.pos());
            case Place p -> "place " + cell(p.pos());
        };
    }

    private static String cell(BetterBlockPos c) {
        return c.x + " " + c.y + " " + c.z;
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }
}
