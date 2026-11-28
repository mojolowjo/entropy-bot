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
 * Entropy Bot 2026-11-27 (nav step 9a): Baritone's PathExecutor.onTick as a pure class: where the body is on the
 * route (back to an earlier move, skip ahead), the off-route and too-slow give-ups (maxDistFromPath 2 for 200 ticks,
 * maxMaxDistFromPath 3 at once, a fall measured flat, movementTimeoutTicks 100),
 * the "this move became impossible" check before each move, and the next move started in the same tick. Every give-up
 * says why. Not yet: sprint across moves, plan-ahead and splice (the next segment is planned when one ends), pause.
 * 9b: the hands' wish of the current move passes through.
 */

package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.moves.MoveShape;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.Step;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.nav.steer.Choice;
import io.github.mojolowjo.entropybot.nav.steer.Sim;
import io.github.mojolowjo.entropybot.nav.steer.SteerConfig;
import io.github.mojolowjo.entropybot.nav.steer.Steerer;
import io.github.mojolowjo.entropybot.nav.steer.Target;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Drives one planned route move by move with {@link MoveDriver}s. Each tick it finds the body on the route (the current
 * move's cells, an earlier move's cells, or a later move's), gives up when the body is more than 2 blocks from the
 * route for 200 ticks or 3 blocks at once (a fall: flat to its landing), when a move takes 100 ticks longer than its price, when a move's own
 * driver fails, or when the world changed under a move before it started (the price is asked again). It ends "arrived"
 * when the feet are in the goal, or "segment done" when the route (a part of the way) runs out: {@link DriveLive} plans
 * the next part. It lets go when the game pauses, the world changes or Baritone drives.
 *
 * <p>Steering S2 (plan to-do/nav-steer.md, m0j 2026-11-27): with a {@link Ghost} set and {@code steer.on} 1, a body in
 * water, or more than 2 blocks off the route, is handed to the {@link Steerer}, which tries key choices on the ghost
 * first. In water it heads for the move's end cell (or the next move's cells) and that counts as the move done; off the
 * route it heads back to the current move's cells and the move's driver starts again. While it steers, the move's
 * time limit and the off-route ends wait; the steerer's own give-up (no progress for {@code steer.giveUpTicks}) ends the
 * trip with its reason. Pure (JUnit: PilotTest, PilotSteerTest).
 * Loader notes: none; {@link DriveLive} supplies the body, the world and the re-price.
 */
public final class Pilot {

    /** What the pilot does this tick. ended: null while it drives; replan: the route ran out short of the goal. */
    public record Act(Set<Keys.Key> keys, Float yaw, String ended, boolean ok, boolean replan, Float pitch, MoveDriver.Hand hand) {
        public Act(Set<Keys.Key> keys, Float yaw, String ended, boolean ok, boolean replan) {
            this(keys, yaw, ended, ok, replan, null, null);
        }
    }

    /** The fork's PathExecutor numbers. */
    static final double MAX_DIST_FROM_PATH = 2, MAX_MAX_DIST_FROM_PATH = 3;   // structural: Settings maxDistFromPath, maxMaxDistFromPath
    static final int MOVEMENT_TIMEOUT_TICKS = 100;                            // structural: Settings movementTimeoutTicks
    static final int MAX_TICKS_AWAY = 200;                                    // structural: PathExecutor MAX_TICKS_AWAY

    /** The start of an end caused from outside the route (pause, world change, Baritone): not the route's fault. */
    static final String LET_GO = "let go after ";

    /** True for an ended line the pilot wrote for an end caused from outside the route. */
    public static boolean letGo(String ended) {
        return ended != null && ended.contains(": " + LET_GO);
    }

    private Path path;
    private int index, moveTicks, ticks, moves, ticksAway;
    private MoveDriver driver;
    private List<Set<BetterBlockPos>> valid;                                   // each move's valid cells, for sync and off-route
    private String name;

    /** Steering S2: the live side's ghost for the steerer to try options on. */
    public interface Ghost {
        /** Is the body in water (feet or eyes)? */
        boolean inWater();

        /** The ghost as the steerer's sim; reset copies the real body. */
        Sim sim();

        /** This game tick's budget for ghost steps. */
        Steerer.Budget budget();
    }

    public enum Steer { OFF, WATER, BACK_TO_ROUTE }

    private Ghost ghost;
    private Steerer steerer;
    private Steer steer = Steer.OFF;
    private String steerWhy = "";

    /** Sets the ghost the steerer tries options on (null: no steering). */
    public void ghost(Ghost g) {
        this.ghost = g;
    }

    /** What the steerer is doing now, for the status line and the log. */
    public Steer steering() { return steer; }

    /** The steerer's counters and its last choice. */
    public String steerStatus() {
        return steerer == null ? "steer: not used yet" : "steer " + steer.name().toLowerCase(Locale.ROOT).replace('_', ' ') + "; " + steerer.status();
    }

    /** Starts a route (any running one is dropped). */
    public void start(Path p, String name) {
        this.path = p;
        this.name = name;
        begin();
    }

    /** A new route for the same trip (the next part): the tick and move counts carry on. */
    public void next(Path p) {
        this.path = p;
        begin();
    }

    private void begin() {
        index = 0;
        moveTicks = 0;
        ticksAway = 0;
        driver = null;
        steer = Steer.OFF;
        valid = new ArrayList<>();
        for (Step s : path.steps()) valid.add(MoveShape.of(s.move(), s.src(), s.dest()).valid());
    }

    /** Resets the counts for a new trip. */
    public void resetCounts() {
        ticks = 0;
        moves = 0;
    }

    public boolean driving() { return path != null; }

    public String name() { return name; }

    /** The current move's index and step, for the status line. */
    public String where() {
        if (path == null) return "no route";
        return "move " + (index + 1) + " of " + path.steps().size() + (index < path.steps().size() ? " (" + path.steps().get(index).move().name().toLowerCase(Locale.ROOT) + ")" : "");
    }

    /** Stops the route from outside. Returns its line, or null. */
    public String stop(String why) {
        if (path == null) return null;
        String line = name + ": stopped after " + ticks + " ticks, " + moves + " moves (" + why + ")";
        path = null;
        steer = Steer.OFF;
        return line;
    }

    /**
     * One tick.
     *
     * @param b        the body
     * @param w        the world's cells
     * @param paused   the game is paused
     * @param baritone Baritone drives
     * @param sameWorld the world is the one the trip started in
     * @param impossible the re-price of a move before it starts: null when it can still be done, else why not
     */
    public Act tick(MoveDriver.Body b, MoveDriver.MoveWorld w, boolean paused, boolean baritone, boolean sameWorld,
                    Function<Step, String> impossible) {
        return tick(b, w, MoveDriver.Hands.NONE, paused, baritone, sameWorld, impossible);
    }

    /** One tick with the live side's hands. */
    public Act tick(MoveDriver.Body b, MoveDriver.MoveWorld w, MoveDriver.Hands h, boolean paused, boolean baritone, boolean sameWorld,
                    Function<Step, String> impossible) {
        if (path == null) {
            return idle();
        }
        if (paused) return end(LET_GO + ticks + " ticks: the game paused", false);
        if (!sameWorld) return end(LET_GO + ticks + " ticks: the world changed", false);
        if (baritone) return end(LET_GO + ticks + " ticks: Baritone is driving (the two never drive together)", false);
        ticks++;
        BetterBlockPos feet = b.feet();
        if (path.getGoal().isInGoal(feet.x, feet.y, feet.z) && (b.onGround() || index >= path.steps().size())) {
            return end("arrived in " + ticks + " ticks, " + moves + " moves", true);
        }
        for (int round = 0; round < 2; round++) {                              // a move that ends hands over in the same tick
            if (index >= path.steps().size()) {
                if (path.getGoal().isInGoal(feet.x, feet.y, feet.z)) {            // the last move ended in the goal (a dig down lands mid-air)
                    return end("arrived in " + ticks + " ticks, " + moves + " moves", true);
                }
                Act a = new Act(EnumSet.noneOf(Keys.Key.class), null, null, false, true);
                path = null;                                                   // DriveLive plans the next part
                return a;
            }
            sync(feet);
            if (driver == null) {
                Step s = path.steps().get(index);
                driver = MoveDriver.of(s);
                String why = impossible.apply(s);
                if (why != null) {
                    return end("move " + (index + 1) + " became impossible: " + why, false);
                }
                moveTicks = 0;
            }
            double off = offRoute(b);
            Steer want = steerWanted(b, off);
            if (want != Steer.OFF) {
                Act a = steerTick(b, want);
                if (a != null) return a;
                continue;                                                      // the steerer got there: the next move, this tick
            }
            if (offBy(b, off, MAX_DIST_FROM_PATH)) {                             // PathExecutor: far for too long
                if (++ticksAway > MAX_TICKS_AWAY) {
                    return end(String.format(Locale.ROOT, "off the route for %d ticks: %.1f blocks from it at move %d (feet at %s)", ticksAway, off, index + 1, cell(feet)), false);
                }
            } else {
                ticksAway = 0;
            }
            if (offBy(b, off, MAX_MAX_DIST_FROM_PATH)) {
                return end(String.format(Locale.ROOT, "off the route: %.1f blocks from it at move %d (feet at %s)", off, index + 1, cell(feet)), false);
            }
            moveTicks++;
            Step s = driver.step();
            if (moveTicks > s.cost() + MOVEMENT_TIMEOUT_TICKS) {
                return end(String.format(Locale.ROOT, "too slow: %d ticks on move %d (%s), its price %.1f", moveTicks, index + 1,
                        s.move().name().toLowerCase(Locale.ROOT), s.cost()), false);
            }
            MoveDriver.MoveAct a = driver.tick(b, w, h);
            switch (a.status()) {
                case RUNNING:
                    return new Act(a.keys(), a.yaw(), null, false, false, a.pitch(), a.hand());
                case FAILED:
                    return end("move " + (index + 1) + " (" + s.move().name().toLowerCase(Locale.ROOT) + " " + cell(s.src()) + " to " + cell(s.dest()) + ") failed: " + a.why(), false);
                case SUCCESS:
                    moves++;
                    index++;
                    driver = null;
                    break;
            }
        }
        return new Act(EnumSet.noneOf(Keys.Key.class), null, null, false, false);
    }

    /**
     * PathExecutor's re-sync: feet in an earlier move's cells go back to it (a lag-back); feet in the cells of a move 3 or
     * more ahead skip to it. Added here: feet on the start of one of the next 2 moves skip to it (the fork leaves that to
     * the move's own done-check, which this port's drivers do not all have).
     */
    private void sync(BetterBlockPos feet) {
        if (valid.get(index).contains(feet)) {
            return;
        }
        for (int j = 0; j < index; j++) {
            if (valid.get(j).contains(feet)) {
                index = j;
                driver = null;
                return;
            }
        }
        int n = path.steps().size();
        for (int j = index + 1; j < Math.min(n, index + 3); j++) {               // structural: the next 2 moves by their start
            if (path.steps().get(j).src().equals(feet)) {
                index = j;
                driver = null;
                return;
            }
        }
        for (int j = index + 3; j < n - 1; j++) {                               // structural: the fork's skip, pathPosition + 3 on
            if (valid.get(j).contains(feet)) {
                index = j;
                driver = null;
                return;
            }
        }
    }

    /** Steering S2: does this spot go to the steerer? Water always; off the route past 2 blocks until back on it. */
    private Steer steerWanted(MoveDriver.Body b, double off) {
        if (ghost == null || Tuning.i("steer.on") == 0) return Steer.OFF;
        if (ghost.inWater()) return Steer.WATER;
        if (steer == Steer.BACK_TO_ROUTE) return Steer.BACK_TO_ROUTE;          // until the steerer says it is back
        if (offBy(b, off, MAX_DIST_FROM_PATH)) return Steer.BACK_TO_ROUTE;
        return Steer.OFF;
    }

    /** One steered tick. Null when the steerer got there (the caller goes on with the next move or the driver). */
    private Act steerTick(MoveDriver.Body b, Steer want) {
        if (steerer == null) steerer = new Steerer(SteerConfig.live());
        if (want != steer) {
            steerer.restart();
            steer = want;
        }
        ticksAway = 0;
        Step s = path.steps().get(index);
        Target t = want == Steer.WATER ? waterTarget(s) : backTarget(b);
        Steerer.Decision d = steerer.tick(b.x(), b.y(), b.z(), t, ghost.sim(), ghost.budget());
        switch (d.status()) {
            case ARRIVED:
                steer = Steer.OFF;
                if (want == Steer.WATER) {
                    moves++;
                    index++;
                }
                driver = null;
                if (index < path.steps().size()) {
                    driver = MoveDriver.of(path.steps().get(index));
                    moveTicks = 0;
                }
                return null;
            case GAVE_UP:
                steer = Steer.OFF;
                return end("steer (" + want.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ") at move " + (index + 1) + ": " + d.why(), false);
            default:
                return new Act(keysOf(d.choice()), d.yaw(), null, false, false);
        }
    }

    /** In water: the move's end cell, or any cell of the next move. Aimed at the end cell's centre, on its floor. */
    private Target waterTarget(Step s) {
        List<Target.Cell> cells = new ArrayList<>();
        cells.add(new Target.Cell(s.dest().x, s.dest().y, s.dest().z));
        if (index + 1 < valid.size()) for (BetterBlockPos c : valid.get(index + 1)) cells.add(new Target.Cell(c.x, c.y, c.z));
        return new Target(cells, s.dest().x + 0.5, s.dest().y, s.dest().z + 0.5);
    }

    /** Off the route: any cell of the current move, aimed at the nearest one's centre, on its floor. */
    private Target backTarget(MoveDriver.Body b) {
        List<Target.Cell> cells = new ArrayList<>();
        BetterBlockPos near = null;
        double best = Double.MAX_VALUE;
        for (BetterBlockPos c : valid.get(index)) {
            cells.add(new Target.Cell(c.x, c.y, c.z));
            double dx = b.x() - (c.x + 0.5), dy = b.y() - c.y, dz = b.z() - (c.z + 0.5);
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) {
                best = d;
                near = c;
            }
        }
        return new Target(cells, near.x + 0.5, near.y, near.z + 0.5);
    }

    /** A steerer's choice as the driver's keys. */
    static Set<Keys.Key> keysOf(Choice c) {
        Set<Keys.Key> k = EnumSet.noneOf(Keys.Key.class);
        if (c.forward() > 0) k.add(Keys.Key.FORWARD);
        if (c.forward() < 0) k.add(Keys.Key.BACK);
        if (c.left() > 0) k.add(Keys.Key.LEFT);
        if (c.left() < 0) k.add(Keys.Key.RIGHT);
        if (c.jump()) k.add(Keys.Key.JUMP);
        if (c.sprint()) k.add(Keys.Key.SPRINT);
        if (c.sneak()) k.add(Keys.Key.SNEAK);
        return k;
    }

    /** PathExecutor.closestPathPos: the body's distance to the centre of the nearest valid cell of any move. */
    private double offRoute(MoveDriver.Body b) {
        double best = Double.MAX_VALUE;
        for (Set<BetterBlockPos> cells : valid) {
            for (BetterBlockPos c : cells) {
                double dx = b.x() - (c.x + 0.5), dy = b.y() - (c.y + 0.5), dz = b.z() - (c.z + 0.5);
                best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        return best;
    }

    /** PathExecutor.possiblyOffPath: past the leniency, except a fall measured flat to its landing cell. */
    private boolean offBy(MoveDriver.Body b, double off, double leniency) {
        if (off <= leniency) return false;
        if (driver instanceof MoveDriver.Fall) {
            BetterBlockPos d = driver.step().dest();
            return Math.hypot(b.x() - (d.x + 0.5), b.z() - (d.z + 0.5)) >= leniency;
        }
        return true;
    }

    private Act idle() {
        return new Act(EnumSet.noneOf(Keys.Key.class), null, null, false, false);
    }

    private Act end(String why, boolean ok) {
        String line = name + ": " + why;
        path = null;
        driver = null;
        steer = Steer.OFF;
        return new Act(EnumSet.noneOf(Keys.Key.class), null, line, ok, false);
    }

    private static String cell(BetterBlockPos c) {
        return c.x + " " + c.y + " " + c.z;
    }
}
