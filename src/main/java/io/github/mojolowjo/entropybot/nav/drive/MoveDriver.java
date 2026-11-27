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
 * Entropy Bot 2026-11-27 (nav step 9a): the driving halves (updateState) of Baritone's MovementTraverse, MovementAscend,
 * MovementDescend, MovementFall and MovementDiagonal, and Movement.update's blanket rules, with breaking and placing
 * off: a move whose cells need a break or a block placed fails with that reason instead. The fork's numbers are kept.
 */

package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.moves.MoveShape;
import io.github.mojolowjo.entropybot.nav.search.Step;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * One planned move, driven tick by tick: which keys, which way the head faces, and whether the move is done or failed
 * (and why). Each move type keeps the fork's rule for "done" (the feet in the destination cell, or one past it where the
 * fork allowed the overshoot, and on the ground after a drop), when to jump (an ascend's head-bonk and distance rules),
 * when to sprint (a traverse with room ahead, a diagonal with its corners open) and how a descend aims past its cell.
 * Step 9a has no hands: a move whose cells need a break or a placed block fails "needs a break" / "needs a block
 * placed" (the planner was told so too: its NavConfig has breaking and placing off). Downward, pillar and parkour come
 * with 9b. Pure (JUnit: MoveDriverTest). Loader notes: none; {@link DriveLive} supplies the body and the world.
 */
public abstract class MoveDriver {

    /** The cells as the game shows them now (the live view through BlockRules). */
    public interface MoveWorld {
        boolean canWalkThrough(int x, int y, int z);

        boolean canWalkOn(int x, int y, int z);

        boolean avoidWalkingInto(int x, int y, int z);

        boolean isLiquid(int x, int y, int z);

        boolean isWater(int x, int y, int z);

        default boolean canWalkThrough(BetterBlockPos p) { return canWalkThrough(p.x, p.y, p.z); }

        default boolean canWalkOn(BetterBlockPos p) { return canWalkOn(p.x, p.y, p.z); }
    }

    /** The player this tick: position, motion, ground and wall contact, head. */
    public record Body(double x, double y, double z, double vx, double vy, double vz, boolean onGround,
                       boolean horizontalCollision, float yaw, float pitch) {
        /** Baritone's playerFeet: the cell at the feet, 0.1251 up so a soul sand or slab floor counts as standing in it. */
        public BetterBlockPos feet() {
            return new BetterBlockPos((int) Math.floor(x), (int) Math.floor(y + 0.1251), (int) Math.floor(z));   // structural: the fork's lift
        }
    }

    public enum Status { RUNNING, SUCCESS, FAILED }

    /** What the move does this tick: the keys, the yaw to face (null: keep), and its status with the reason. */
    public record MoveAct(Set<Keys.Key> keys, Float yaw, Status status, String why) {}

    protected final Step step;
    protected final BetterBlockPos src, dest;
    protected final MoveShape shape;
    protected int ticks;

    protected MoveDriver(Step step) {
        this.step = step;
        this.src = step.src();
        this.dest = step.dest();
        this.shape = MoveShape.of(step.move(), src, dest);
    }

    /** The driver for a planned step, or null for a move 9a cannot drive (downward, pillar, parkour). */
    public static MoveDriver of(Step s) {
        return switch (s.move()) {
            case TRAVERSE_NORTH, TRAVERSE_SOUTH, TRAVERSE_EAST, TRAVERSE_WEST -> new Traverse(s);
            case ASCEND_NORTH, ASCEND_SOUTH, ASCEND_EAST, ASCEND_WEST -> new Ascend(s);
            case DESCEND_NORTH, DESCEND_SOUTH, DESCEND_EAST, DESCEND_WEST -> s.dest().y == s.src().y - 1 ? new Descend(s) : new Fall(s);
            case DIAGONAL_NORTHEAST, DIAGONAL_NORTHWEST, DIAGONAL_SOUTHEAST, DIAGONAL_SOUTHWEST -> new Diagonal(s);
            default -> null;
        };
    }

    public Step step() { return step; }

    /** The cells the body may stand in during this move (the fork's valid positions). */
    public Set<BetterBlockPos> valid() { return shape.valid(); }

    /** One tick: Movement.update (the prepared check, the move's own rule, jump in liquid). */
    public final MoveAct tick(Body b, MoveWorld w) {
        ticks++;
        if (ticks == 1) {
            for (BetterBlockPos c : shape.breaks()) {
                if (!w.canWalkThrough(c) && !walkIntoOk(c)) {
                    return fail("needs a break at " + cell(c) + " (breaking is off in 9a)");
                }
            }
        }
        MoveAct a = drive(b, w);
        if (a.status() == Status.RUNNING) {
            BetterBlockPos f = b.feet();
            if (w.isLiquid(f.x, f.y, f.z) && b.y() < dest.y + 0.6) {                 // Movement.update: swim up in a liquid
                EnumSet<Keys.Key> k = a.keys().isEmpty() ? EnumSet.noneOf(Keys.Key.class) : EnumSet.copyOf(a.keys());
                k.add(Keys.Key.JUMP);
                a = new MoveAct(k, a.yaw(), a.status(), a.why());
            }
        }
        return a;
    }

    /** A cell in the break list the move does not need empty (a diagonal's corners: it may walk past one). */
    protected boolean walkIntoOk(BetterBlockPos c) { return false; }

    protected abstract MoveAct drive(Body b, MoveWorld w);

    // ---- helpers

    protected static MoveAct run(Set<Keys.Key> keys, Float yaw) {
        return new MoveAct(keys, yaw, Status.RUNNING, null);
    }

    protected MoveAct done() {
        return new MoveAct(EnumSet.noneOf(Keys.Key.class), null, Status.SUCCESS, null);
    }

    protected MoveAct fail(String why) {
        return new MoveAct(EnumSet.noneOf(Keys.Key.class), null, Status.FAILED, why);
    }

    /** MovementHelper.moveTowards: face the cell's centre (yaw only), forward. */
    protected static float yawTo(Body b, double x, double z) {
        return LookMath.toward(b.x(), 0, b.z(), x, 0, z).yaw();
    }

    protected static float yawTo(Body b, BetterBlockPos c) {
        return yawTo(b, c.x + 0.5, c.z + 0.5);
    }

    /** c moved by (dx, dy, dz), as a BetterBlockPos (BlockPos.offset would give a plain BlockPos). */
    protected static BetterBlockPos at(BetterBlockPos c, int dx, int dy, int dz) {
        return new BetterBlockPos(c.x + dx, c.y + dy, c.z + dz);
    }

    protected static String cell(BetterBlockPos c) {
        return c.x + " " + c.y + " " + c.z;
    }

    protected static EnumSet<Keys.Key> keys(Keys.Key... k) {
        EnumSet<Keys.Key> s = EnumSet.noneOf(Keys.Key.class);
        for (Keys.Key x : k) s.add(x);
        return s;
    }

    // ---- the moves

    /** MovementTraverse (no bridging, no doors): walk to the next cell, sprint when the way ahead is open. */
    static final class Traverse extends MoveDriver {
        Traverse(Step s) { super(s); }

        @Override
        protected MoveAct drive(Body b, MoveWorld w) {
            BetterBlockPos feet = b.feet();
            if (!w.canWalkOn(dest.below())) {
                return fail("needs a block placed at " + cell(dest.below()) + " (placing is off in 9a)");
            }
            if (feet.y != dest.y) {
                return feet.y < dest.y ? run(keys(Keys.Key.JUMP), null) : run(keys(), null);   // the fork: "Wrong Y coordinate"
            }
            int dx = dest.x - src.x, dz = dest.z - src.z;
            if (feet.equals(dest) || feet.equals(at(dest, dx, 0, dz)) || feet.equals(at(dest, 2 * dx, 0, 2 * dz))) {
                return done();                                                     // overshootTraverse (on by default)
            }
            BetterBlockPos into = at(dest, dx, 0, dz);
            boolean sprint = !w.isLiquid(feet.x, feet.y, feet.z) && (!w.avoidWalkingInto(into.x, into.y, into.z) || w.isWater(into.x, into.y, into.z))
                    && !w.avoidWalkingInto(into.x, into.y + 1, into.z);
            return run(sprint ? keys(Keys.Key.FORWARD, Keys.Key.SPRINT) : keys(Keys.Key.FORWARD), yawTo(b, dest));
        }
    }

    /** MovementAscend (no placing): walk at the step and jump once lined up and close, or at once with head room. */
    static final class Ascend extends MoveDriver {
        Ascend(Step s) { super(s); }

        @Override
        protected MoveAct drive(Body b, MoveWorld w) {
            BetterBlockPos feet = b.feet();
            if (feet.y < src.y) {
                return fail("fell below the start (feet at y " + feet.y + ")");
            }
            int dx = dest.x - src.x, dz = dest.z - src.z;
            if (feet.equals(dest) || feet.equals(at(dest, dx, 0, dz))) {
                return done();
            }
            if (!w.canWalkOn(dest.below())) {
                return fail("needs a block placed at " + cell(dest.below()) + " (placing is off in 9a)");
            }
            float yaw = yawTo(b, dest);
            if (feet.equals(src.above())) {
                return run(keys(Keys.Key.FORWARD), yaw);                           // already jumping
            }
            int xAxis = Math.abs(dx), zAxis = Math.abs(dz);
            double flatDistToNext = xAxis * Math.abs((dest.x + 0.5) - b.x()) + zAxis * Math.abs((dest.z + 0.5) - b.z());
            double sideDist = zAxis * Math.abs((dest.x + 0.5) - b.x()) + xAxis * Math.abs((dest.z + 0.5) - b.z());
            double lateral = xAxis * b.vz() + zAxis * b.vx();
            if (Math.abs(lateral) > 0.1) {                                         // structural: the fork's lateral limit
                return run(keys(Keys.Key.FORWARD), yaw);
            }
            if (headBonkClear(w)) {
                return run(keys(Keys.Key.FORWARD, Keys.Key.JUMP), yaw);
            }
            if (flatDistToNext > 1.2 || sideDist > 0.2) {                          // structural: the fork's jump window
                return run(keys(Keys.Key.FORWARD), yaw);
            }
            return run(keys(Keys.Key.FORWARD, Keys.Key.JUMP), yaw);
        }

        private boolean headBonkClear(MoveWorld w) {
            BetterBlockPos up = src.above(2);
            return w.canWalkThrough(up.north()) && w.canWalkThrough(up.south()) && w.canWalkThrough(up.east()) && w.canWalkThrough(up.west());
        }
    }

    /** MovementDescend (one block down): aim past the cell for the first 20 ticks unless that is unsafe, land, done. */
    static final class Descend extends MoveDriver {
        private int aimed;

        Descend(Step s) { super(s); }

        @Override
        protected MoveAct drive(Body b, MoveWorld w) {
            BetterBlockPos feet = b.feet();
            BetterBlockPos fake = new BetterBlockPos(dest.x * 2 - src.x, dest.y, dest.z * 2 - src.z);
            if ((feet.equals(dest) || feet.equals(fake)) && (w.isLiquid(dest.x, dest.y, dest.z) || b.y() - dest.y < 0.5)) {
                return done();
            }
            if (safeMode(w)) {
                double tx = (src.x + 0.5) * 0.17 + (dest.x + 0.5) * 0.83, tz = (src.z + 0.5) * 0.17 + (dest.z + 0.5) * 0.83;   // structural: the fork's safe aim
                return run(keys(Keys.Key.FORWARD), yawTo(b, tx, tz));
            }
            double ab = Math.hypot(b.x() - (dest.x + 0.5), b.z() - (dest.z + 0.5));
            double fromStart = Math.hypot(b.x() - (src.x + 0.5), b.z() - (src.z + 0.5));
            if (!feet.equals(dest) || ab > 0.25) {
                if (aimed++ < 20 && fromStart < 1.25) {                            // structural: the fork's overshoot window
                    return run(keys(Keys.Key.FORWARD), yawTo(b, fake));
                }
                return run(keys(Keys.Key.FORWARD), yawTo(b, dest));
            }
            return run(keys(), null);                                              // over the cell: wait to land
        }

        private boolean safeMode(MoveWorld w) {
            BetterBlockPos into = at(dest, dest.x - src.x, 0, dest.z - src.z);
            if (!w.canWalkThrough(into) && w.canWalkThrough(into.above()) && w.canWalkThrough(into.above(2))) {
                return true;                                                       // skipToAscend
            }
            for (int y = 0; y <= 2; y++) {
                if (w.avoidWalkingInto(into.x, into.y + y, into.z)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** MovementFall (no water bucket): walk off the edge toward the cell's centre, steer the drop, done on the ground. */
    static final class Fall extends MoveDriver {
        Fall(Step s) { super(s); }

        @Override
        protected MoveAct drive(Body b, MoveWorld w) {
            BetterBlockPos feet = b.feet();
            if (feet.equals(dest) && (b.y() - feet.y < 0.094 || w.isLiquid(dest.x, dest.y, dest.z))) {   // structural: the fork's lily-pad height
                return done();
            }
            if (feet.y < dest.y) {
                return fail(String.format(Locale.ROOT, "fell past the landing cell (feet at y %d, the cell at %d)", feet.y, dest.y));
            }
            double cx = dest.x + 0.5, cz = dest.z + 0.5;
            EnumSet<Keys.Key> k = keys();
            if (Math.abs(b.x() + b.vx() - cx) > 0.1 || Math.abs(b.z() + b.vz() - cz) > 0.1) {   // structural: the fork's centring
                if (!b.onGround() && Math.abs(b.vy()) > 0.4) {
                    k.add(Keys.Key.SNEAK);
                }
                k.add(Keys.Key.FORWARD);
            }
            int ax = src.x - dest.x, az = src.z - dest.z;                          // no ladder: avoid = back toward src
            return run(k, yawTo(b, cx + 0.125 * ax, cz + 0.125 * az));
        }
    }

    /** MovementDiagonal: walk the diagonal, sprint with the four corner cells open, jump at a wall on a rising diagonal. */
    static final class Diagonal extends MoveDriver {
        Diagonal(Step s) { super(s); }

        @Override
        protected boolean walkIntoOk(BetterBlockPos c) {
            return shape.breaks().indexOf(c) < 4;                                  // the corners: the fork walks past one (toWalkInto)
        }

        @Override
        protected MoveAct drive(Body b, MoveWorld w) {
            BetterBlockPos feet = b.feet();
            if (feet.equals(dest)) {
                return done();
            }
            if (!valid().contains(feet) && !(w.isLiquid(src.x, src.y, src.z) && valid().contains(feet.above()))) {
                return fail("left the diagonal's cells (feet at " + cell(feet) + ")");
            }
            EnumSet<Keys.Key> k = keys(Keys.Key.FORWARD);
            if (dest.y > src.y && b.y() < src.y + 0.1 && b.horizontalCollision()) {
                k.add(Keys.Key.JUMP);
            }
            boolean sprint = !w.isLiquid(feet.x, feet.y, feet.z);
            for (int i = 0; i < 4 && sprint; i++) {
                sprint = w.canWalkThrough(shape.breaks().get(i));
            }
            if (sprint) {
                k.add(Keys.Key.SPRINT);
            }
            return run(k, yawTo(b, dest));
        }
    }
}
