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
 * Entropy Bot 2026-11-27 (nav step 9b): the sense half of MovementHelper.attemptToPlaceABlock (support faces in the
 * order north, south, east, west, down; the face centre the fork aims at; READY when the crosshair is on a support
 * face of the cell or on the replaceable cell itself) and RotationUtils.reachable, over the client's level.
 */

package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.Rotation;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the hands can do this tick, for the pure move drivers: is the crosshair on a block, which rotation reaches a face
 * of a block, where a support face for a placed block is and whether the crosshair is on one, crouching, and a full
 * block in the bag. One differs from the fork: the rays start at the eyes as they are now (the fork guesses the sneaking
 * eyes when it is about to sneak); the rotation to a point can use the sneaking eye height. Pure: no (the game's
 * level). Loader notes: vanilla {@code Level.clip}, {@code LocalPlayer.pick}, {@code Entity.getEyeHeight(Pose)};
 * Fabric the same.
 */
final class LiveHands implements MoveDriver.Hands {
    /** The fork's HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP. */
    private static final Direction[] AGAINST = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    private final Minecraft mc;
    private final LocalPlayer p;
    private final BlockView view;
    private final BlockHitResult under;

    LiveHands(Minecraft mc, LocalPlayer p, BlockView view) {
        this.mc = mc;
        this.p = p;
        this.view = view;
        this.under = DriveLive.crosshair(p);
    }

    private static BlockPos pos(BetterBlockPos c) {
        return new BlockPos(c.x, c.y, c.z);
    }

    @Override
    public boolean lookingAt(BetterBlockPos c) {
        return under != null && under.getBlockPos().equals(pos(c));
    }

    @Override
    public Rotation reach(BetterBlockPos c) {
        Vec3 v = DriveLive.visible(mc, p, pos(c));
        return v == null ? null : toward(v.x, v.y, v.z, false);
    }

    @Override
    public Rotation toward(double x, double y, double z, boolean sneak) {
        double eye = p.getY() + p.getEyeHeight(sneak ? Pose.CROUCHING : Pose.STANDING);
        LookMath.Turn t = LookMath.toward(p.getX(), eye, p.getZ(), x, y, z);
        return new Rotation(t.yaw(), t.pitch());
    }

    @Override
    public MoveDriver.PlaceSense place(BetterBlockPos cell, boolean preferDown, boolean sneak) {
        Rotation aim = null;
        BlockPos at = pos(cell);
        if (!mc.level.getBlockState(at).isAir()) {
            aim = reach(cell);                                                   // a replaceable block there: aim at it directly
        }
        for (Direction d : AGAINST) {
            BlockPos against = at.relative(d);
            if (!BlockRules.canPlaceAgainst(view, against.getX(), against.getY(), against.getZ())) {
                continue;
            }
            Vec3 face = new Vec3((at.getX() + against.getX() + 1.0) * 0.5, (at.getY() + against.getY() + 0.5) * 0.5,
                    (at.getZ() + against.getZ() + 1.0) * 0.5);                  // structural: the fork's face centre
            BlockHitResult r = DriveLive.ray(mc, p, face);
            if (r != null && r.getBlockPos().equals(against) && r.getBlockPos().relative(r.getDirection()).equals(at)) {
                aim = toward(face.x, face.y, face.z, sneak);
                if (!preferDown) {
                    break;
                }
            }
        }
        boolean ready = under != null && (under.getBlockPos().equals(at)
                || (BlockRules.canPlaceAgainst(view, under.getBlockPos().getX(), under.getBlockPos().getY(), under.getBlockPos().getZ())
                && under.getBlockPos().relative(under.getDirection()).equals(at)));
        if (aim == null && !ready) {
            return null;
        }
        return new MoveDriver.PlaceSense(aim == null ? new Rotation(p.getYRot(), p.getXRot()) : aim, ready);
    }

    @Override
    public boolean crouching() {
        return p.isCrouching();
    }

    @Override
    public boolean hasBlock() {
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {                                           // structural: the player inventory's 36 slots
            if (DriveLive.fullBlock(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }
}
