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
 * Modified for Entropy Bot 2026-11-27: cost half of MovementAscend only, over SearchContext and BlockRules
 */

package io.github.mojolowjo.entropybot.nav.moves;

import com.google.common.collect.ImmutableSet;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.cost.MovementStatus;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Step up one block onto the next cell (placing a block to stand on if needed).
 *
 * <p>Entropy Bot (nav step 5): the cost half of Baritone's {@code MovementAscend} only, same code over {@link SearchContext} and
 * {@link BlockRules}; the driving half (updateState) comes with the pilot in step 8. {@code MovesMatchForkTest}
 * checks every price against the fork jar to the tick.
 *
 * <p>Pure (JUnit: MovesMatchForkTest). Loader notes: block classes and BlockState (common side, Mojang names).
 */
public final class AscendCost implements ActionCosts {

    private AscendCost() {}

    public static double cost(SearchContext context, int x, int y, int z, int destX, int destZ) {
        BlockState toPlace = context.get(destX, y, destZ);
        double additionalPlacementCost = 0;
        if (!BlockRules.canWalkOn(context, destX, y, destZ, toPlace)) {
            additionalPlacementCost = context.costOfPlacingAt(destX, y, destZ, toPlace);
            if (additionalPlacementCost >= COST_INF) {
                return COST_INF;
            }
            if (!BlockRules.isReplaceable(destX, y, destZ, toPlace, context.bsi)) {
                return COST_INF;
            }
            boolean foundPlaceOption = false;
            for (int i = 0; i < 5; i++) {
                int againstX = destX + MoveCatalog.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepX();
                int againstY = y + MoveCatalog.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepY();
                int againstZ = destZ + MoveCatalog.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepZ();
                if (againstX == x && againstZ == z) { // we might be able to backplace now, but it doesn't matter because it will have been broken by the time we'd need to use it
                    continue;
                }
                if (BlockRules.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) {
                    foundPlaceOption = true;
                    break;
                }
            }
            if (!foundPlaceOption) { // didn't find a valid place =(
                return COST_INF;
            }
        }
        BlockState srcUp2 = context.get(x, y + 2, z); // used lower down anyway
        if (context.get(x, y + 3, z).getBlock() instanceof FallingBlock && (BlockRules.canWalkThrough(context, x, y + 1, z) || !(srcUp2.getBlock() instanceof FallingBlock))) {//it would fall on us and possibly suffocate us
            // HOWEVER, we assume that we're standing in the start position
            // that means that src and src.up(1) are both air
            // maybe they aren't now, but they will be by the time this starts
            // if the lower one is can't walk through and the upper one is falling, that means that by standing on src
            // (the presupposition of this Movement)
            // we have necessarily already cleared the entire FallingBlock stack
            // on top of our head

            // as in, if we have a block, then two FallingBlocks on top of it
            // and that block is x, y+1, z, and we'd have to clear it to even start this movement
            // we don't need to worry about those FallingBlocks because we've already cleared them
            return COST_INF;
            // you may think we only need to check srcUp2, not srcUp
            // however, in the scenario where glitchy world gen where unsupported sand / gravel generates
            // it's possible srcUp is AIR from the start, and srcUp2 is falling
            // and in that scenario, when we arrive and break srcUp2, that lets srcUp3 fall on us and suffocate us
        }
        BlockState srcDown = context.get(x, y - 1, z);
        if (BlockRules.isClimbable(srcDown.getBlock())) {
            return COST_INF;
        }
        // we can jump from soul sand, but not from a bottom slab
        boolean jumpingFromBottomSlab = BlockRules.isBottomSlab(srcDown);
        boolean jumpingToBottomSlab = BlockRules.isBottomSlab(toPlace);
        if (jumpingFromBottomSlab && !jumpingToBottomSlab) {
            return COST_INF;// the only thing we can ascend onto from a bottom slab is another bottom slab
        }
        double walk;
        if (jumpingToBottomSlab) {
            if (jumpingFromBottomSlab) {
                walk = Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST); // we hit space immediately on entering this action
                walk += context.jumpPenalty;
            } else {
                walk = WALK_ONE_BLOCK_COST; // we don't hit space we just walk into the slab
            }
        } else {
            // jumpingFromBottomSlab must be false
            if (toPlace.is(Blocks.SOUL_SAND)) {
                walk = WALK_ONE_OVER_SOUL_SAND_COST;
            } else if (toPlace.is(Blocks.MAGMA_BLOCK)) {
                walk = SNEAK_ONE_BLOCK_COST;
            } else {
                walk = Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST);
            }
            walk += context.jumpPenalty;
        }

        double totalCost = walk + additionalPlacementCost;
        // start with srcUp2 since we already have its state
        // includeFalling isn't needed because of the falling check above -- if srcUp3 is falling we will have already exited with COST_INF if we'd actually have to break it
        totalCost += BlockRules.getMiningDurationTicks(context, x, y + 2, z, srcUp2, false);
        if (totalCost >= COST_INF) {
            return COST_INF;
        }
        totalCost += BlockRules.getMiningDurationTicks(context, destX, y + 1, destZ, false);
        if (totalCost >= COST_INF) {
            return COST_INF;
        }
        totalCost += BlockRules.getMiningDurationTicks(context, destX, y + 2, destZ, true);
        return totalCost;
    }
}
