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
 * Entropy Bot 2026-11-27 (nav steps 9a, 9b): the driving halves (updateState) of Baritone's MovementTraverse,
 * MovementAscend, MovementDescend, MovementFall, MovementDiagonal, MovementDownward, MovementPillar and MovementParkour,
 * Movement.prepared (break what is in the way first) and Movement.update's blanket rules, and the sense half of
 * MovementHelper.attemptToPlaceABlock as a {@link MoveDriver.Hands} input. The fork's numbers are kept.
 */

package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.moves.MoveShape;
import io.github.mojolowjo.entropybot.nav.search.Step;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.Rotation;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * One planned move, driven tick by tick: which keys, which way the head faces, and whether the move is done or failed
 * (and why). Each move type keeps the fork's rule for "done" (the feet in the destination cell, or one past it where the
 * fork allowed the overshoot, and on the ground after a drop), when to jump (an ascend's head-bonk and distance rules),
 * when to sprint (a traverse with room ahead, a diagonal with its corners open) and how a descend aims past its cell.
 * Step 9b's hands: before a move walks, each blocking cell is broken (aim at a face in reach, hit once the crosshair is
 * on it); a bridge, a step or a pillar places a block (the support face the live side found, sneaking, click once the
 * crosshair is on it). The move only wishes: {@link Hand} names the cell and whether to click now, and the live side
 * holds the tool or block and gates the click to that cell. Not here: doors, gates, ladders, vines, parkour mid-air
 * placing. Pure (JUnit: MoveDriverTest, MoveDriverHandsTest). Loader notes: none; {@link DriveLive} supplies the body,
 * the world and the hands.
 */
public abstract class MoveDriver {

    /** The cells as the game shows them now (the live view through BlockRules). */
    public interface MoveWorld {
        boolean canWalkThrough(int x, int y, int z);

        boolean canWalkOn(int x, int y, int z);

        boolean avoidWalkingInto(int x, int y, int z);

        boolean isLiquid(int x, int y, int z);

        boolean isWater(int x, int y, int z);

        /** Air or a block a placed block replaces (BlockState.canBeReplaced). */
        default boolean isReplaceable(int x, int y, int z) { return canWalkThrough(x, y, z); }

        /** A ladder or vine (BlockRules.isClimbable): not driven in 9b. */
        default boolean isClimbable(int x, int y, int z) { return false; }

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

    /** A wish for the hands: break or place {@code cell}, clicking this tick or not (the live side gates the click). */
    public record Hand(boolean place, BetterBlockPos cell, boolean click) {}

    /**
     * What the move does this tick: the keys, the yaw to face (null: keep), its status with the reason, the pitch to
     * face (null: keep) and the hands' wish (null: none).
     */
    public record MoveAct(Set<Keys.Key> keys, Float yaw, Status status, String why, Float pitch, Hand hand) {
        public MoveAct(Set<Keys.Key> keys, Float yaw, Status status, String why) {
            this(keys, yaw, status, why, null, null);
        }
    }

    /** The live side's sense of the hands (MovementHelper.attemptToPlaceABlock's ray checks, RotationUtils.reachable). */
    public interface Hands {
        /** The crosshair is on this block (ctx.isLookingAt). */
        boolean lookingAt(BetterBlockPos c);

        /** A rotation whose ray from the eyes reaches a face of {@code c} within reach, or null (RotationUtils.reachable). */
        Rotation reach(BetterBlockPos c);

        /** The rotation from the eyes (sneaking eyes when {@code sneak}) to a point. */
        Rotation toward(double x, double y, double z, boolean sneak);

        /**
         * attemptToPlaceABlock without the clicks: null when no support face is in reach (NO_OPTION), else the rotation
         * to one (preferDown: the last found, else the first) and whether the crosshair is on a support face of
         * {@code cell} now (READY_TO_PLACE).
         */
        PlaceSense place(BetterBlockPos cell, boolean preferDown, boolean sneak);

        /** The body is crouching (sneak has taken effect). */
        boolean crouching();

        /** A full block to place is in the bag (selectThrowawayForLocation). */
        boolean hasBlock();

        /** No hands: nothing in reach, nothing in the bag (the 9a tests). */
        Hands NONE = new Hands() {
            @Override public boolean lookingAt(BetterBlockPos c) { return false; }
            @Override public Rotation reach(BetterBlockPos c) { return null; }
            @Override public Rotation toward(double x, double y, double z, boolean sneak) { return new Rotation(0, 0); }
            @Override public PlaceSense place(BetterBlockPos cell, boolean preferDown, boolean sneak) { return null; }
            @Override public boolean crouching() { return false; }
            @Override public boolean hasBlock() { return false; }
        };
    }

    /** attemptToPlaceABlock's answer: the rotation to a support face, and READY_TO_PLACE (the crosshair is on one). */
    public record PlaceSense(Rotation aim, boolean ready) {}

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

    /** The driver for a planned step. */
    public static MoveDriver of(Step s) {
        return switch (s.move()) {
            case DOWNWARD -> new Downward(s);
            case PILLAR -> new Pillar(s);
            case PARKOUR_NORTH, PARKOUR_SOUTH, PARKOUR_EAST, PARKOUR_WEST -> new Parkour(s);
            case TRAVERSE_NORTH, TRAVERSE_SOUTH, TRAVERSE_EAST, TRAVERSE_WEST -> new Traverse(s);
            case ASCEND_NORTH, ASCEND_SOUTH, ASCEND_EAST, ASCEND_WEST -> new Ascend(s);
            case DESCEND_NORTH, DESCEND_SOUTH, DESCEND_EAST, DESCEND_WEST -> s.dest().y == s.src().y - 1 ? new Descend(s) : new Fall(s);
            case DIAGONAL_NORTHEAST, DIAGONAL_NORTHWEST, DIAGONAL_SOUTHEAST, DIAGONAL_SOUTHWEST -> new Diagonal(s);
        };
    }

    public Step step() { return step; }

    /** The cells the body may stand in during this move (the fork's valid positions). */
    public Set<BetterBlockPos> valid() { return shape.valid(); }

    /** One tick without hands (the 9a tests). */
    public final MoveAct tick(Body b, MoveWorld w) {
        return tick(b, w, Hands.NONE);
    }

    /** One tick: Movement.update (the prepared check, the move's own rule, jump in liquid). */
    public final MoveAct tick(Body b, MoveWorld w, Hands h) {
        ticks++;
        MoveAct a = prepared(b, w, h);
        if (a == null) {
            a = drive(b, w, h);
        }
        if (a.status() == Status.RUNNING) {
            BetterBlockPos f = b.feet();
            if (w.isLiquid(f.x, f.y, f.z) && b.y() < dest.y + 0.6) {                 // Movement.update: swim up in a liquid
                EnumSet<Keys.Key> k = a.keys().isEmpty() ? EnumSet.noneOf(Keys.Key.class) : EnumSet.copyOf(a.keys());
                k.add(Keys.Key.JUMP);
                a = new MoveAct(k, a.yaw(), a.status(), a.why(), a.pitch(), a.hand());
            }
        }
        return a;
    }

    /**
     * Movement.prepared, every tick: the first cell in the way is broken before the move's own rule runs (null: nothing
     * in the way). A face in reach is aimed at and hit once the crosshair is on it or the head is on the aim; with no
     * face in sight the fork aims at the centre and hits anyway (the live side still hits only that cell).
     */
    protected MoveAct prepared(Body b, MoveWorld w, Hands h) {
        for (BetterBlockPos c : shape.breaks()) {
            if (w.canWalkThrough(c) || walkIntoOk(c)) {
                continue;
            }
            Rotation r = h.reach(c);
            boolean click;
            if (r != null) {
                click = h.lookingAt(c) || new Rotation(b.yaw(), b.pitch()).isReallyCloseTo(r);
            } else {
                r = h.toward(c.x + 0.5, c.y + 0.5, c.z + 0.5, false);
                click = true;
            }
            EnumSet<Keys.Key> k = keys();
            Float yaw = r.getYaw();
            if (walkWhileBreaking(b, w)) {                                         // MovementTraverse: walk up to it meanwhile
                k.add(Keys.Key.FORWARD);
                yaw = yawTo(b, dest);
            }
            return new MoveAct(k, yaw, Status.RUNNING, null, r.getPitch(), new Hand(false, c, click));
        }
        return null;
    }

    /** walkWhileBreaking (on by default): only a traverse walks toward its cell while it breaks the way. */
    protected boolean walkWhileBreaking(Body b, MoveWorld w) { return false; }

    /** A cell in the break list the move does not need empty (a diagonal's corners: it may walk past one). */
    protected boolean walkIntoOk(BetterBlockPos c) { return false; }

    protected abstract MoveAct drive(Body b, MoveWorld w, Hands h);

    // ---- helpers

    protected static MoveAct run(Set<Keys.Key> keys, Float yaw) {
        return new MoveAct(keys, yaw, Status.RUNNING, null);
    }

    protected static MoveAct run(Set<Keys.Key> keys, Rotation r, Hand hand) {
        return new MoveAct(keys, r == null ? null : r.getYaw(), Status.RUNNING, null, r == null ? null : r.getPitch(), hand);
    }

    /** Chebyshev distance from the body to a cell's centre, flat (the fork's dist1 and friends). */
    protected static double flatMax(Body b, double x, double z) {
        return Math.max(Math.abs(b.x() - x), Math.abs(b.z() - z));
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

    /** MovementTraverse (no doors): walk to the next cell, sprint when the way ahead is open, bridge a missing floor. */
    static final class Traverse extends MoveDriver {
        private boolean wasTheBridgeBlockAlwaysThere = true;

        Traverse(Step s) { super(s); }

        @Override
        protected boolean walkWhileBreaking(Body b, MoveWorld w) {
            BetterBlockPos pb0 = shape.breaks().get(0), pb1 = shape.breaks().get(1);
            return !w.avoidWalkingInto(pb0.x, pb0.y, pb0.z) && !w.avoidWalkingInto(pb1.x, pb1.y, pb1.z)
                    && flatMax(b, dest.x + 0.5, dest.z + 0.5) >= 0.83;            // structural: the fork's "pressed up against it"
        }

        @Override
        protected MoveAct drive(Body b, MoveWorld w, Hands h) {
            BetterBlockPos feet = b.feet();
            if (feet.y != dest.y) {
                return feet.y < dest.y ? run(keys(Keys.Key.JUMP), null) : run(keys(), null);   // the fork: "Wrong Y coordinate"
            }
            int dx = dest.x - src.x, dz = dest.z - src.z;
            BetterBlockPos under = dest.below();
            if (w.canWalkOn(under)) {
                if (feet.equals(dest) || feet.equals(at(dest, dx, 0, dz)) || feet.equals(at(dest, 2 * dx, 0, 2 * dz))) {
                    return done();                                                 // overshootTraverse (on by default)
                }
                BetterBlockPos into = at(dest, dx, 0, dz);
                boolean sprint = wasTheBridgeBlockAlwaysThere && !w.isLiquid(feet.x, feet.y, feet.z)
                        && (!w.avoidWalkingInto(into.x, into.y, into.z) || w.isWater(into.x, into.y, into.z))
                        && !w.avoidWalkingInto(into.x, into.y + 1, into.z);
                return run(sprint ? keys(Keys.Key.FORWARD, Keys.Key.SPRINT) : keys(Keys.Key.FORWARD), yawTo(b, dest));
            }
            wasTheBridgeBlockAlwaysThere = false;
            if (!h.hasBlock()) {
                return fail("needs a block placed at " + cell(under) + " and the bag has no full block");
            }
            double dist1 = flatMax(b, dest.x + 0.5, dest.z + 0.5);
            PlaceSense p = h.place(under, false, true);
            EnumSet<Keys.Key> k = keys();
            if (p != null || dist1 < 0.6) {                                       // structural: the fork's sneak distance; a face found sneaks (attemptToPlaceABlock wouldSneak)
                k.add(Keys.Key.SNEAK);
            }
            if (p != null && p.ready()) {
                return run(k, p.aim(), new Hand(true, under, h.crouching()));
            }
            if (p != null) {                                                       // ATTEMPTING
                if (dist1 > 0.83 && Math.abs(p.aim().getYaw() - yawTo(b, dest)) < 0.1) {   // structural: the fork's "straight ahead"
                    k.add(Keys.Key.FORWARD);
                }
                return run(k, p.aim(), new Hand(true, under, false));
            }
            if (feet.equals(dest)) {                                               // over the gap: place back against the block walked off
                double faceX = (dest.x + src.x + 1.0) * 0.5, faceY = (dest.y + src.y - 1.0) * 0.5, faceZ = (dest.z + src.z + 1.0) * 0.5;
                Rotation back = h.toward(faceX, faceY, faceZ, true);
                Rotation aim = back;
                if (flatMax(b, faceX, faceZ) < 0.29) {                             // structural: the fork's issue #208 distance
                    aim = new Rotation(LookMath.toward(dest.x + 0.5, 0, dest.z + 0.5, b.x(), 0, b.z()).yaw(), back.getPitch());
                    k.add(Keys.Key.BACK);
                }
                return run(k, aim, new Hand(true, under, h.lookingAt(src.below())));
            }
            k.add(Keys.Key.FORWARD);                                               // moveTowardsWithSlightRotation
            return run(k, new Rotation(yawTo(b, dest), b.pitch()), null);
        }
    }

    /** MovementAscend: place the step when it is missing, walk at it and jump once lined up and close, or at once with head room. */
    static final class Ascend extends MoveDriver {
        private int ticksWithoutPlacement;

        Ascend(Step s) { super(s); }

        @Override
        protected MoveAct prepared(Body b, MoveWorld w, Hands h) {
            if (b.feet().y < src.y) {                                              // the fork checks this before preparing
                return fail("fell below the start (feet at y " + b.feet().y + ")");
            }
            return super.prepared(b, w, h);
        }

        @Override
        protected MoveAct drive(Body b, MoveWorld w, Hands h) {
            BetterBlockPos feet = b.feet();
            if (feet.y < src.y) {
                return fail("fell below the start (feet at y " + feet.y + ")");
            }
            int dx = dest.x - src.x, dz = dest.z - src.z;
            if (feet.equals(dest) || feet.equals(at(dest, dx, 0, dz))) {
                return done();
            }
            if (!w.canWalkOn(dest.below())) {
                if (!h.hasBlock()) {
                    return fail("needs a block placed at " + cell(dest.below()) + " and the bag has no full block");
                }
                ticksWithoutPlacement++;
                PlaceSense p = h.place(dest.below(), false, true);
                EnumSet<Keys.Key> k = keys();
                boolean click = false;
                if (p != null) {                                                   // attemptToPlaceABlock wouldSneak: sneak once a face is found
                    k.add(Keys.Key.SNEAK);
                    click = p.ready() && h.crouching();
                }
                if (ticksWithoutPlacement > 10) {                                  // structural: the fork's "standing in the way"
                    k.add(Keys.Key.BACK);
                }
                return run(k, p == null ? null : p.aim(), new Hand(true, dest.below(), click));
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
        protected MoveAct drive(Body b, MoveWorld w, Hands h) {
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
        protected MoveAct drive(Body b, MoveWorld w, Hands h) {
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
        protected MoveAct drive(Body b, MoveWorld w, Hands h) {
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

    /** MovementDownward: break the floor (prepared), wait up to 10 ticks centred while it falls away, walk at it, done in it. */
    static final class Downward extends MoveDriver {
        private int numTicks;

        Downward(Step s) { super(s); }

        @Override
        protected MoveAct drive(Body b, MoveWorld w, Hands h) {
            BetterBlockPos feet = b.feet();
            if (feet.equals(dest)) {
                return done();
            }
            if (!valid().contains(feet)) {
                return fail("left the cells of the dig down (feet at " + cell(feet) + ")");
            }
            double ab = Math.hypot(b.x() - (dest.x + 0.5), b.z() - (dest.z + 0.5));
            if (numTicks++ < 10 && ab < 0.2) {                                     // structural: the fork's 10 ticks, 0.2 centring
                return run(keys(), null);
            }
            return run(keys(Keys.Key.FORWARD), yawTo(b, dest));
        }
    }

    /** MovementPillar (no ladder): sneak, centre within 0.17, jump, place the block under the feet once risen 0.1 over the cell. */
    static final class Pillar extends MoveDriver {
        Pillar(Step s) { super(s); }

        @Override
        protected MoveAct prepared(Body b, MoveWorld w, Hands h) {
            if (w.isWater(dest.x, dest.y + 1, dest.z)) {                           // the fork swims up without breaking
                return null;
            }
            return super.prepared(b, w, h);
        }

        @Override
        protected MoveAct drive(Body b, MoveWorld w, Hands h) {
            BetterBlockPos feet = b.feet();
            if (feet.y < src.y) {
                return fail("fell below the start (feet at y " + feet.y + ")");
            }
            if (w.isWater(src.x, src.y, src.z) && w.isWater(dest.x, dest.y, dest.z)) {   // swim up a water column, centred
                if (feet.equals(dest)) {
                    return done();
                }
                Rotation r = h.toward(dest.x + 0.5, dest.y + 0.5, dest.z + 0.5, false);
                boolean off = Math.abs(b.x() - (dest.x + 0.5)) > 0.2 || Math.abs(b.z() - (dest.z + 0.5)) > 0.2;   // structural: the fork's centring
                return run(off ? keys(Keys.Key.FORWARD) : keys(), r.getYaw());
            }
            if (w.isClimbable(src.x, src.y, src.z)) {
                return fail("climbs a ladder or vine at " + cell(src) + " (ladders are not driven yet)");
            }
            if (!h.hasBlock()) {
                return fail("needs a block placed at " + cell(src) + " and the bag has no full block");
            }
            Rotation rot = h.toward(src.x + 0.5, src.y + 0.5, src.z + 0.5, false);
            Rotation target = new Rotation(b.yaw(), rot.getPitch());
            boolean blockIsThere = w.canWalkOn(src);
            EnumSet<Keys.Key> k = keys(Keys.Key.SNEAK);
            double dist = Math.hypot(b.x() - (dest.x + 0.5), b.z() - (dest.z + 0.5));
            double flatMotion = Math.hypot(b.vx(), b.vz());
            if (dist > 0.17) {                                                     // structural: the fork's 0.17 (under the 0.3 sneak limit)
                k.add(Keys.Key.FORWARD);
                target = rot;
            } else if (flatMotion < 0.05 && b.y() < dest.y) {                      // structural: the fork's still-enough
                k.add(Keys.Key.JUMP);
            }
            Hand hand = null;
            if (!blockIsThere) {
                if (!w.isReplaceable(src.x, src.y, src.z)) {                       // something not replaceable where the block goes: break it
                    Rotation r = h.reach(src);
                    if (r != null) target = r;
                    k.remove(Keys.Key.JUMP);
                    hand = new Hand(false, src, true);
                } else {
                    boolean click = h.crouching() && (h.lookingAt(src.below()) || h.lookingAt(src)) && b.y() > dest.y + 0.1;   // structural: the fork's 0.1
                    hand = new Hand(true, src, click);
                }
            }
            if (feet.equals(dest) && blockIsThere) {
                return done();
            }
            return run(k, target, hand);
        }
    }

    /** MovementParkour (no mid-air placing): sprint at a 4-gap or an ascend, walk at the edge, jump once off the start. */
    static final class Parkour extends MoveDriver {
        private final int dist, dx, dz;
        private final boolean ascend;

        Parkour(Step s) {
            super(s);
            dist = Math.abs(dest.x - src.x) + Math.abs(dest.z - src.z);
            dx = Integer.signum(dest.x - src.x);
            dz = Integer.signum(dest.z - src.z);
            ascend = dest.y > src.y;
        }

        @Override
        protected MoveAct drive(Body b, MoveWorld w, Hands h) {
            BetterBlockPos feet = b.feet();
            if (feet.y < src.y) {
                return fail("fell short of the jump (feet at y " + feet.y + ")");
            }
            EnumSet<Keys.Key> k = keys(Keys.Key.FORWARD);
            if (dist >= 4 || ascend) {                                             // structural: the fork's sprint rule
                k.add(Keys.Key.SPRINT);
            }
            float yaw = yawTo(b, dest);
            if (feet.equals(dest)) {
                if (b.y() - feet.y < 0.094) {                                      // structural: the fork's lily-pad height
                    return done();
                }
            } else if (!feet.equals(src)) {
                if (feet.equals(at(src, dx, 0, dz)) || b.y() - src.y > 0.0001) {  // structural: off the edge or already rising
                    if (dist == 3 && !ascend) {                                    // a 2-block gap: jump late
                        double fromStart = flatMax(b, src.x + 0.5, src.z + 0.5);
                        if (fromStart < 0.7) {                                     // structural: the fork's 0.7
                            return run(k, yaw);
                        }
                    }
                    k.add(Keys.Key.JUMP);
                } else if (!feet.equals(at(dest, -dx, 0, -dz))) {
                    k.remove(Keys.Key.SPRINT);
                    yaw = feet.equals(at(src, -dx, 0, -dz)) ? yawTo(b, src) : yawTo(b, at(src, -dx, 0, -dz));
                }
            }
            return run(k, yaw);
        }
    }
}
