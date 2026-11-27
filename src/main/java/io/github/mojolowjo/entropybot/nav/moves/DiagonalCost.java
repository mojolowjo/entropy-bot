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
 * Modified for Entropy Bot 2026-11-27: cost half of MovementDiagonal only, over SearchContext and BlockRules
 */

package io.github.mojolowjo.entropybot.nav.moves;

import com.google.common.collect.ImmutableSet;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.cost.MovementStatus;
import io.github.mojolowjo.entropybot.nav.cost.MutableMoveResult;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;

/**
 * Walk to a diagonal neighbour, flat or one up or down.
 *
 * <p>Entropy Bot (nav step 5): the cost half of Baritone's {@code MovementDiagonal} only, same code over {@link SearchContext} and
 * {@link BlockRules}; the driving half (updateState) comes with the pilot in step 8. {@code MovesMatchForkTest}
 * checks every price against the fork jar to the tick.
 *
 * <p>Pure (JUnit: MovesMatchForkTest). Loader notes: block classes and BlockState (common side, Mojang names).
 */
public final class DiagonalCost implements ActionCosts {

    private DiagonalCost() {}

    private static final double SQRT_2 = Math.sqrt(2);

    public static void cost(SearchContext context, int x, int y, int z, int destX, int destZ, MutableMoveResult res) {
        BlockState destIntoUpper = context.get(destX, y + 1, destZ);
        if (!BlockRules.canWalkThrough(context, destX, y + 1, destZ, destIntoUpper)) {
            return;
        }
        BlockState destInto = context.get(destX, y, destZ);
        BlockState fromDown;
        boolean ascend = false;
        BlockState destWalkOn;
        boolean descend = false;
        boolean frostWalker = false;
        boolean sneaking = false;
        if (!BlockRules.canWalkThrough(context, destX, y, destZ, destInto)) {
            ascend = true;
            if (!context.allowDiagonalAscend || !BlockRules.canWalkThrough(context, x, y + 2, z) || !BlockRules.canWalkOn(context, destX, y, destZ, destInto) || !BlockRules.canWalkThrough(context, destX, y + 2, destZ)) {
                return;
            }
            destWalkOn = destInto;
            fromDown = context.get(x, y - 1, z);
        } else {
            destWalkOn = context.get(destX, y - 1, destZ);
            fromDown = context.get(x, y - 1, z);
            boolean standingOnABlock = BlockRules.mustBeSolidToWalkOn(context, x, y - 1, z, fromDown);
            frostWalker = standingOnABlock && BlockRules.canUseFrostWalker(context, destWalkOn);
            if (!frostWalker && !BlockRules.canWalkOn(context, destX, y - 1, destZ, destWalkOn)) {
                descend = true;
                if (!context.allowDiagonalDescend || !BlockRules.canWalkOn(context, destX, y - 2, destZ) || !BlockRules.canWalkThrough(context, destX, y - 1, destZ, destWalkOn)) {
                    return;
                }
            }
            frostWalker &= !context.assumeWalkOnWater; // do this after checking for descends because jesus can't prevent the water from freezing, it just prevents us from relying on the water freezing
        }
        BlockState startState = context.get(x, y, z);
        if (isBlockingDoor(startState, x, z, destX, destZ) || isBlockingDoor(ascend ? destIntoUpper : destInto, destX, destZ, x, y)) {
            return; // Easier to just traverse instead
        }
        double multiplier = WALK_ONE_BLOCK_COST;
        // For either possible soul sand, that affects half of our walking
        if (destWalkOn.is(Blocks.SOUL_SAND)) {
            multiplier += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
        } else if (context.allowWalkOnMagmaBlocks && destWalkOn.is(Blocks.MAGMA_BLOCK)) {
            multiplier += (SNEAK_ONE_BLOCK_COST - WALK_ONE_BLOCK_COST) / 2;
            sneaking = true;
        } else if (frostWalker) {
            // frostwalker lets us walk on water without the penalty
        } else if (destWalkOn.getBlock() == Blocks.WATER) {
            multiplier += context.walkOnWaterOnePenalty * SQRT_2;
        }
        Block fromDownBlock = fromDown.getBlock();
        if (BlockRules.isClimbable(fromDownBlock)) {
            return;
        }
        if (fromDownBlock == Blocks.SOUL_SAND) {
            multiplier += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
        } else if (context.allowWalkOnMagmaBlocks && fromDownBlock.equals(Blocks.MAGMA_BLOCK)) {
            multiplier += (SNEAK_ONE_BLOCK_COST - WALK_ONE_BLOCK_COST) / 2;
            sneaking = true;
        }
        BlockState cuttingOver1 = context.get(x, y - 1, destZ);
        if ((!context.allowWalkOnMagmaBlocks && cuttingOver1.is(Blocks.MAGMA_BLOCK)) || BlockRules.isLava(cuttingOver1)) {
            return;
        }
        BlockState cuttingOver2 = context.get(destX, y - 1, z);
        if ((!context.allowWalkOnMagmaBlocks && cuttingOver2.is(Blocks.MAGMA_BLOCK)) || BlockRules.isLava(cuttingOver2)) {
            return;
        }
        boolean water = false;
        Block startIn = startState.getBlock();
        if (BlockRules.isWater(startState) || BlockRules.isWater(destInto)) {
            if (ascend) {
                return;
            }
            // Ignore previous multiplier
            // Whatever we were walking on (possibly soul sand) doesn't matter as we're actually floating on water
            // Not even touching the blocks below
            multiplier = context.waterWalkSpeed;
            water = true;
        }
        BlockState pb0 = context.get(x, y, destZ);
        BlockState pb2 = context.get(destX, y, z);
        if (ascend) {
            boolean ATop = BlockRules.canWalkThrough(context, x, y + 2, destZ);
            boolean AMid = BlockRules.canWalkThrough(context, x, y + 1, destZ);
            boolean ALow = BlockRules.canWalkThrough(context, x, y, destZ, pb0);
            boolean BTop = BlockRules.canWalkThrough(context, destX, y + 2, z);
            boolean BMid = BlockRules.canWalkThrough(context, destX, y + 1, z);
            boolean BLow = BlockRules.canWalkThrough(context, destX, y, z, pb2);
            ALow &= !isBlockingDoor(pb0, x, destZ, destX, z);
            BLow &= !isBlockingDoor(pb2, destX, z, x, destZ);
            if ((!(ATop && AMid && ALow) && !(BTop && BMid && BLow)) // no option
                    || context.rules.avoidWalkingInto(pb0) // bad
                    || context.rules.avoidWalkingInto(pb2) // bad
                    || (ATop && AMid && BlockRules.canWalkOn(context, x, y, destZ, pb0)) // we could just ascend
                    || (BTop && BMid && BlockRules.canWalkOn(context, destX, y, z, pb2)) // we could just ascend
                    || (!ATop && AMid && ALow) // head bonk A
                    || (!BTop && BMid && BLow)) { // head bonk B
                return;
            }
            res.cost = multiplier * SQRT_2 + JUMP_ONE_BLOCK_COST;
            res.x = destX;
            res.z = destZ;
            res.y = y + 1;
            return;
        }
        double optionA = BlockRules.getMiningDurationTicks(context, x, y, destZ, pb0, false);
        double optionB = BlockRules.getMiningDurationTicks(context, destX, y, z, pb2, false);
        optionA += isBlockingDoor(pb0, x, destZ, destX, z) ? 1 : 0;
        optionB += isBlockingDoor(pb2, destX, z, x, destZ) ? 1 : 0;
        if (optionA != 0 && optionB != 0) {
            // check these one at a time -- if pb0 and pb2 were nonzero, we already know that (optionA != 0 && optionB != 0)
            // so no need to check pb1 as well, might as well return early here
            return;
        }
        BlockState pb1 = context.get(x, y + 1, destZ);
        optionA += BlockRules.getMiningDurationTicks(context, x, y + 1, destZ, pb1, true);
        if (optionA != 0 && optionB != 0) {
            // same deal, if pb1 makes optionA nonzero and option B already was nonzero, pb3 can't affect the result
            return;
        }
        BlockState pb3 = context.get(destX, y + 1, z);
        if (optionA == 0 && ((context.rules.avoidWalkingInto(pb2) && pb2.getBlock() != Blocks.WATER) || context.rules.avoidWalkingInto(pb3))) {
            // at this point we're done calculating optionA, so we can check if it's actually possible to edge around in that direction
            return;
        }
        optionB += BlockRules.getMiningDurationTicks(context, destX, y + 1, z, pb3, true);
        if (optionA != 0 && optionB != 0) {
            // and finally, if the cost is nonzero for both ways to approach this diagonal, it's not possible
            return;
        }
        if (optionB == 0 && ((context.rules.avoidWalkingInto(pb0) && pb0.getBlock() != Blocks.WATER) || context.rules.avoidWalkingInto(pb1))) {
            // and now that option B is fully calculated, see if we can edge around that way
            return;
        }
        if (optionA != 0 || optionB != 0) {
            multiplier *= SQRT_2 - 0.001; // TODO tune
            if (BlockRules.isClimbable(startIn)) {
                // edging around doesn't work if doing so would climb a ladder or vine instead of moving sideways
                return;
            }
        } else {
            // only can sprint if not edging around
            if (context.canSprint && !water && !sneaking) {
                // If we aren't edging around anything, and we aren't in water
                // We can sprint =D
                // Don't check for soul sand, since we can sprint on that too
                multiplier *= SPRINT_MULTIPLIER;
            }
        }
        res.cost = multiplier * SQRT_2;
        if (descend) {
            res.cost += Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST);
            res.y = y - 1;
        } else {
            res.y = y;
        }
        res.x = destX;
        res.z = destZ;
    }

    /**
     * We can always pass wooden doors in src or dest and we can also pass
     * wooden doors in the A or B columns, unless their hinge points towards
     * the other column.
     * This function checks whether {@code other} is the diagonal neighbor of
     * {@code door} in the direction of the hinge of {@code state}.
     */
    private static boolean isBlockingDoor(BlockState state, int doorX, int doorZ, int otherX, int otherZ) {
        if (!(state.getBlock() instanceof DoorBlock)) {
            return false;
        }

        Vec3i offset = state.getValue(HorizontalDirectionalBlock.FACING).getNormal();
        int ox = offset.getX();
        int oz = offset.getZ();

        int nbrX, nbrZ;
        if (state.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT) {
            nbrX = doorX - ox + oz;
            nbrZ = doorZ - oz - ox;
        } else {
            nbrX = doorX - ox - oz;
            nbrZ = doorZ - oz + ox;
        }

        return nbrX == otherX && nbrZ == otherZ;
    }
}
