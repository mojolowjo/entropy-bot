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
 * Modified for Entropy Bot 2026-11-27: cost half of MovementTraverse only, over SearchContext and BlockRules; the fork's lily pad rule and mining under a broken falling block
 */

package io.github.mojolowjo.entropybot.nav.moves;

import com.google.common.collect.ImmutableSet;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.cost.MovementStatus;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.Rotation;
import io.github.mojolowjo.entropybot.nav.util.RotationMath;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * Walk to the next cell on the same level, breaking what is in the way or bridging a block under it.
 *
 * <p>Entropy Bot (nav step 5): the cost half of Baritone's {@code MovementTraverse} only, same code over {@link SearchContext} and
 * {@link BlockRules}; the driving half (updateState) comes with the pilot in step 8. {@code MovesMatchForkTest}
 * checks every price against the fork jar to the tick.
 *
 * <p>Pure (JUnit: MovesMatchForkTest). Loader notes: block classes and BlockState (common side, Mojang names).
 */
public final class TraverseCost implements ActionCosts {

    private TraverseCost() {}

    public static double cost(SearchContext context, int x, int y, int z, int destX, int destZ) {
        BlockState pb0 = context.get(destX, y + 1, destZ);
        BlockState pb1 = context.get(destX, y, destZ);
        BlockState destOn = context.get(destX, y - 1, destZ);
        BlockState srcDown = context.get(x, y - 1, z);
        Block srcDownBlock = srcDown.getBlock();
        boolean standingOnABlock = BlockRules.mustBeSolidToWalkOn(context, x, y - 1, z, srcDown);
        boolean frostWalker = standingOnABlock && !context.assumeWalkOnWater && BlockRules.canUseFrostWalker(context, destOn);
        if (frostWalker || BlockRules.canWalkOn(context, destX, y - 1, destZ, destOn)) { //this is a walk, not a bridge
            if (!standingOnABlock && (pb0.getBlock() == Blocks.LILY_PAD || pb1.getBlock() == Blocks.LILY_PAD)) {
                return COST_INF; // fork: no stepping onto or off a lily pad while swimming (not standing on a block)
            }
            double WC = WALK_ONE_BLOCK_COST;
            boolean water = false;
            boolean sneaking = false;
            if (BlockRules.isWater(pb0) || BlockRules.isWater(pb1)) {
                WC = context.waterWalkSpeed;
                water = true;
            } else {
                if (destOn.getBlock() == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                } else if (frostWalker) {
                    // with frostwalker we can walk on water without the penalty, if we are sure we won't be using jesus
                } else if (destOn.getBlock() == Blocks.WATER) {
                    WC += context.walkOnWaterOnePenalty;
                }
                if (srcDownBlock == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                } else if (context.allowWalkOnMagmaBlocks && srcDownBlock.equals(Blocks.MAGMA_BLOCK)) {
                    sneaking = true;
                    WC += (SNEAK_ONE_BLOCK_COST - WALK_ONE_BLOCK_COST) / 2;
                }
            }
            double hardness1 = BlockRules.getMiningDurationTicksUnderBroken(context, destX, y, destZ, pb1); // fork
            if (hardness1 >= COST_INF) {
                return COST_INF;
            }
            double hardness2 = BlockRules.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
            if (hardness1 == 0 && hardness2 == 0) {
                if (!water && !sneaking && context.canSprint) {
                    // If there's nothing in the way, and this isn't water, and we aren't sneak placing
                    // We can sprint =D
                    // Don't check for soul sand, since we can sprint on that too
                    WC *= SPRINT_MULTIPLIER;
                }
                return WC;
            }
            if (BlockRules.isClimbable(srcDownBlock)) {
                hardness1 *= 5;
                hardness2 *= 5;
            }
            return WC + hardness1 + hardness2;
        } else {//this is a bridge, so we need to place a block
            if (BlockRules.isClimbable(srcDownBlock)) {
                return COST_INF;
            }
            if (BlockRules.isReplaceable(destX, y - 1, destZ, destOn, context.bsi)) {
                boolean throughWater = BlockRules.isWater(pb0) || BlockRules.isWater(pb1);
                if (BlockRules.isWater(destOn) && throughWater) {
                    // this happens when assume walk on water is true and this is a traverse in water, which isn't allowed
                    return COST_INF;
                }
                double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, destOn);
                if (placeCost >= COST_INF) {
                    return COST_INF;
                }
                double hardness1 = BlockRules.getMiningDurationTicksUnderBroken(context, destX, y, destZ, pb1); // fork
                if (hardness1 >= COST_INF) {
                    return COST_INF;
                }
                double hardness2 = BlockRules.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
                double WC = throughWater ? context.waterWalkSpeed : WALK_ONE_BLOCK_COST;
                for (int i = 0; i < 5; i++) {
                    int againstX = destX + MoveCatalog.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepX();
                    int againstY = y - 1 + MoveCatalog.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepY();
                    int againstZ = destZ + MoveCatalog.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepZ();
                    if (againstX == x && againstZ == z) { // this would be a backplace
                        continue;
                    }
                    if (BlockRules.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) { // found a side place option
                        return WC + placeCost + hardness1 + hardness2;
                    }
                }
                // now that we've checked all possible directions to side place, we actually need to backplace
                if (srcDownBlock == Blocks.SOUL_SAND || (srcDownBlock instanceof SlabBlock && srcDown.getValue(SlabBlock.TYPE) != SlabType.DOUBLE)) {
                    return COST_INF; // can't sneak and backplace against soul sand or half slabs (regardless of whether it's top half or bottom half) =/
                }
                if (!standingOnABlock) { // standing on water / swimming
                    return COST_INF; // this is obviously impossible
                }
                Block blockSrc = context.getBlock(x, y, z);
                if ((blockSrc == Blocks.LILY_PAD || blockSrc instanceof CarpetBlock) && !srcDown.getFluidState().isEmpty()) {
                    return COST_INF; // we can stand on these but can't place against them
                }
                WC = WC * (SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST);//since we are sneak backplacing, we are sneaking lol
                return WC + placeCost + hardness1 + hardness2;
            }
            return COST_INF;
        }
    }
}
