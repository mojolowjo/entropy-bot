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
 * Modified for Entropy Bot 2026-11-27: cost half of MovementParkour only, over SearchContext and BlockRules; build limit from the context
 */

package io.github.mojolowjo.entropybot.nav.moves;

import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.cost.MovementStatus;
import io.github.mojolowjo.entropybot.nav.cost.MutableMoveResult;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;

/**
 * Sprint-jump over a 1 to 3 block gap, onto the same level or one up, or place a block to land on.
 *
 * <p>Entropy Bot (nav step 5): the cost half of Baritone's {@code MovementParkour} only, same code over {@link SearchContext} and
 * {@link BlockRules}; the driving half (updateState) comes with the pilot in step 8. {@code MovesMatchForkTest}
 * checks every price against the fork jar to the tick.
 *
 * <p>Pure (JUnit: MovesMatchForkTest). Loader notes: block classes and BlockState (common side, Mojang names).
 */
public final class ParkourCost implements ActionCosts {

    private ParkourCost() {}

    public static void cost(SearchContext context, int x, int y, int z, Direction dir, MutableMoveResult res) {
        if (!context.allowParkour) {
            return;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.minBuildHeight() + context.dimensionHeight()) {
            return;
        }
        int xDiff = dir.getStepX();
        int zDiff = dir.getStepZ();
        if (!BlockRules.fullyPassable(context, x + xDiff, y, z + zDiff)) {
            // most common case at the top -- the adjacent block isn't air
            return;
        }
        BlockState adj = context.get(x + xDiff, y - 1, z + zDiff);
        if (BlockRules.canWalkOn(context, x + xDiff, y - 1, z + zDiff, adj)) { // don't parkour if we could just traverse (for now)
            // second most common case -- we could just traverse not parkour
            return;
        }
        if (context.rules.avoidWalkingInto(adj) && !(adj.getFluidState().getType() instanceof WaterFluid)) { // magma sucks
            return;
        }
        if (!BlockRules.fullyPassable(context, x + xDiff, y + 1, z + zDiff)) {
            return;
        }
        if (!BlockRules.fullyPassable(context, x + xDiff, y + 2, z + zDiff)) {
            return;
        }
        if (!BlockRules.fullyPassable(context, x, y + 2, z)) {
            return;
        }
        BlockState standingOn = context.get(x, y - 1, z);
        if (BlockRules.isClimbable(standingOn.getBlock()) || standingOn.getBlock() instanceof StairBlock || BlockRules.isBottomSlab(standingOn)) {
            return;
        }
        // we can't jump from (frozen) water with assumeWalkOnWater because we can't be sure it will be frozen
        if (context.assumeWalkOnWater && !standingOn.getFluidState().isEmpty()) {
            return;
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return; // can't jump out of water
        }
        int maxJump;
        if (context.allowWalkOnMagmaBlocks && standingOn.is(Blocks.MAGMA_BLOCK)) {
            maxJump = 2;
        } else if (standingOn.getBlock() == Blocks.SOUL_SAND) {
            maxJump = 2; // 1 block gap
        } else if (context.canSprint) {
            maxJump = 4;
        } else {
            maxJump = 3;
        }

        // check parkour jumps from smallest to largest for obstacles/walls and landing positions
        int verifiedMaxJump = 1; // i - 1 (when i = 2)
        for (int i = 2; i <= maxJump; i++) {
            int destX = x + xDiff * i;
            int destZ = z + zDiff * i;

            // check head/feet
            if (!BlockRules.fullyPassable(context, destX, y + 1, destZ)) {
                break;
            }
            if (!BlockRules.fullyPassable(context, destX, y + 2, destZ)) {
                break;
            }

            // check for ascend landing position
            BlockState destInto = context.bsi.get0(destX, y, destZ);
            if (!BlockRules.fullyPassable(context, destX, y, destZ, destInto)) {
                if (i <= 3 && context.allowParkourAscend && context.canSprint && BlockRules.canWalkOn(context, destX, y, destZ, destInto) && checkOvershootSafety(context, destX + xDiff, y + 1, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y + 1;
                    res.z = destZ;
                    res.cost = i * SPRINT_ONE_BLOCK_COST + context.jumpPenalty;
                    return;
                }
                break;
            }

            // check for flat landing position
            BlockState landingOn = context.bsi.get0(destX, y - 1, destZ);
            // farmland needs to be canWalkOn otherwise farm can never work at all, but we want to specifically disallow ending a jump on farmland haha
            // frostwalker works here because we can't jump from possibly unfrozen water
            if ((landingOn.getBlock() != Blocks.FARMLAND && BlockRules.canWalkOn(context, destX, y - 1, destZ, landingOn))
                    || (Math.min(16, context.frostWalker + 2) >= i && BlockRules.canUseFrostWalker(context, landingOn))
            ) {
                if (checkOvershootSafety(context, destX + xDiff, y, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + context.jumpPenalty;
                    return;
                }
                break;
            }

            if (!BlockRules.fullyPassable(context, destX, y + 3, destZ)) {
                break;
            }

            verifiedMaxJump = i;
        }

        // parkour place starts here
        if (!context.allowParkourPlace) {
            return;
        }
        // check parkour jumps from largest to smallest for positions to place blocks
        for (int i = verifiedMaxJump; i > 1; i--) {
            int destX = x + i * xDiff;
            int destZ = z + i * zDiff;
            BlockState toReplace = context.get(destX, y - 1, destZ);
            double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, toReplace);
            if (placeCost >= COST_INF) {
                continue;
            }
            if (!BlockRules.isReplaceable(destX, y - 1, destZ, toReplace, context.bsi)) {
                continue;
            }
            if (!checkOvershootSafety(context, destX + xDiff, y, destZ + zDiff)) {
                continue;
            }
            for (int j = 0; j < 5; j++) {
                int againstX = destX + MoveCatalog.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepX();
                int againstY = y - 1 + MoveCatalog.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepY();
                int againstZ = destZ + MoveCatalog.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepZ();
                if (againstX == destX - xDiff && againstZ == destZ - zDiff) { // we can't turn around that fast
                    continue;
                }
                if (BlockRules.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + placeCost + context.jumpPenalty;
                    return;
                }
            }
        }
    }

    private static boolean checkOvershootSafety(SearchContext context, int x, int y, int z) {
        BlockView bsi = context.bsi;
        // we're going to walk into these two blocks after the landing of the parkour anyway, so make sure they aren't avoidWalkingInto
        return !context.rules.avoidWalkingInto(bsi.get0(x, y, z)) && !context.rules.avoidWalkingInto(bsi.get0(x, y + 1, z));
    }

    private static double costFromJumpDistance(int dist) {
        switch (dist) {
            case 2:
                return WALK_ONE_BLOCK_COST * 2; // IDK LOL
            case 3:
                return WALK_ONE_BLOCK_COST * 3;
            case 4:
                return SPRINT_ONE_BLOCK_COST * 4;
            default:
                throw new IllegalStateException("LOL " + dist);
        }
    }
}
