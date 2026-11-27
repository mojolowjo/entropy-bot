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
 * Modified for Entropy Bot 2026-11-27: cost half of MovementDescend only (descend and the fall it turns into), over SearchContext and BlockRules; build height from the context (fork)
 */

package io.github.mojolowjo.entropybot.nav.moves;

import com.google.common.collect.ImmutableSet;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.cost.MovementStatus;
import io.github.mojolowjo.entropybot.nav.cost.MutableMoveResult;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.RotationMath;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Step down one block, or fall further (the fall move is priced here, as in Baritone).
 *
 * <p>Entropy Bot (nav step 5): the cost half of Baritone's {@code MovementDescend} only, same code over {@link SearchContext} and
 * {@link BlockRules}; the driving half (updateState) comes with the pilot in step 8. {@code MovesMatchForkTest}
 * checks every price against the fork jar to the tick.
 *
 * <p>Pure (JUnit: MovesMatchForkTest). Loader notes: block classes and BlockState (common side, Mojang names).
 */
public final class DescendCost implements ActionCosts {

    private DescendCost() {}

    public static void cost(SearchContext context, int x, int y, int z, int destX, int destZ, MutableMoveResult res) {
        double totalCost = 0;
        BlockState destDown = context.get(destX, y - 1, destZ);
        totalCost += BlockRules.getMiningDurationTicks(context, destX, y - 1, destZ, destDown, false);
        if (totalCost >= COST_INF) {
            return;
        }
        totalCost += BlockRules.getMiningDurationTicks(context, destX, y, destZ, false);
        if (totalCost >= COST_INF) {
            return;
        }
        totalCost += BlockRules.getMiningDurationTicks(context, destX, y + 1, destZ, true); // only the top block in the 3 we need to mine needs to consider the falling blocks above
        if (totalCost >= COST_INF) {
            return;
        }

        Block fromDown = context.get(x, y - 1, z).getBlock();
        if (BlockRules.isClimbable(fromDown)) {
            return;
        }

        // A
        //SA
        // A
        // B
        // C
        // D
        //if S is where you start, B needs to be air for a movementfall
        //A is plausibly breakable by either descend or fall
        //C, D, etc determine the length of the fall

        BlockState below = context.get(destX, y - 2, destZ);
        if (!BlockRules.canWalkOn(context, destX, y - 2, destZ, below)) {
            dynamicFallCost(context, x, y, z, destX, destZ, totalCost, below, res);
            return;
        }

        if (destDown.getBlock() == Blocks.LADDER || destDown.getBlock() == Blocks.VINE) {
            return;
        }
        if (BlockRules.canUseFrostWalker(context, destDown)) { // no need to check assumeWalkOnWater
            return; // the water will freeze when we try to walk into it
        }

        // we walk half the block plus 0.3 to get to the edge, then we walk the other 0.2 while simultaneously falling (math.max because of how it's in parallel)
        double walk = WALK_OFF_BLOCK_COST;
        if (fromDown == Blocks.SOUL_SAND) {
            // use this ratio to apply the soul sand speed penalty to our 0.8 block distance
            walk *= WALK_ONE_OVER_SOUL_SAND_COST / WALK_ONE_BLOCK_COST;
        }
        totalCost += walk + Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST);
        res.x = destX;
        res.y = y - 1;
        res.z = destZ;
        res.cost = totalCost;
    }

    public static boolean dynamicFallCost(SearchContext context, int x, int y, int z, int destX, int destZ, double frontBreak, BlockState below, MutableMoveResult res) {
        if (frontBreak != 0 && context.get(destX, y + 2, destZ).getBlock() instanceof FallingBlock) {
            // if frontBreak is 0 we can actually get through this without updating the falling block and making it actually fall
            // but if frontBreak is nonzero, we're breaking blocks in front, so don't let anything fall through this column,
            // and potentially replace the water we're going to fall into
            return false;
        }
        if (!BlockRules.canWalkThrough(context, destX, y - 2, destZ, below)) {
            return false;
        }
        double costSoFar = 0;
        int effectiveStartHeight = y;
        for (int fallHeight = 3; true; fallHeight++) {
            int newY = y - fallHeight;
            if (newY < context.minBuildHeight() /* fork: through the context */) {
                // when pathing in the end, where you could plausibly fall into the void
                // this check prevents it from getting the block at y=(below whatever the minimum height is) and crashing
                return false;
            }
            boolean reachedMinimum = fallHeight >= context.minFallHeight;
            BlockState ontoBlock = context.get(destX, newY, destZ);
            int unprotectedFallHeight = fallHeight - (y - effectiveStartHeight); // equal to fallHeight - y + effectiveFallHeight, which is equal to -newY + effectiveFallHeight, which is equal to effectiveFallHeight - newY
            double tentativeCost = WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[unprotectedFallHeight] + frontBreak + costSoFar;
            if (reachedMinimum && BlockRules.isWater(ontoBlock)) {
                if (!BlockRules.canWalkThrough(context, destX, newY, destZ, ontoBlock)) {
                    return false;
                }
                if (context.assumeWalkOnWater) {
                    return false; // TODO fix
                }
                if (BlockRules.isFlowing(destX, newY, destZ, ontoBlock, context.bsi)) {
                    return false; // TODO flowing check required here?
                }
                if (!BlockRules.canWalkOn(context, destX, newY - 1, destZ)) {
                    // we could punch right through the water into something else
                    return false;
                }
                // found a fall into water
                res.x = destX;
                res.y = newY;
                res.z = destZ;
                res.cost = tentativeCost;// TODO incorporate water swim up cost?
                return false;
            }
            if (reachedMinimum && context.allowFallIntoLava && BlockRules.isLava(ontoBlock)) {
                // found a fall into lava
                res.x = destX;
                res.y = newY;
                res.z = destZ;
                res.cost = tentativeCost;
                return false;
            }
            if (unprotectedFallHeight <= 11 && BlockRules.isClimbable(ontoBlock.getBlock())) {
                // if fall height is greater than or equal to 11, we don't actually grab on to vines or ladders. the more you know
                // this effectively "resets" our falling speed
                costSoFar += FALL_N_BLOCKS_COST[unprotectedFallHeight - 1];// we fall until the top of this block (not including this block)
                costSoFar += LADDER_DOWN_ONE_COST;
                effectiveStartHeight = newY;
                continue;
            }
            if (BlockRules.canWalkThrough(context, destX, newY, destZ, ontoBlock)) {
                continue;
            }
            if (!BlockRules.canWalkOn(context, destX, newY, destZ, ontoBlock)) {
                return false;
            }
            if (BlockRules.isBottomSlab(ontoBlock)) {
                return false; // falling onto a half slab is really glitchy, and can cause more fall damage than we'd expect
            }
            if (reachedMinimum && unprotectedFallHeight <= context.maxFallHeightNoWater + 1) {
                // fallHeight = 4 means onto.up() is 3 blocks down, which is the max
                res.x = destX;
                res.y = newY + 1;
                res.z = destZ;
                res.cost = tentativeCost;
                return false;
            }
            if (reachedMinimum && context.hasWaterBucket && unprotectedFallHeight <= context.maxFallHeightBucket + 1) {
                res.x = destX;
                res.y = newY + 1;// this is the block we're falling onto, so dest is +1
                res.z = destZ;
                res.cost = tentativeCost + context.placeBucketCost();
                return true;
            } else {
                return false;
            }
        }
    }
}
