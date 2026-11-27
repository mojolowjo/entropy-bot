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
 * Modified for Entropy Bot 2026-11-27: cost half of MovementDownward only, over SearchContext and BlockRules
 */

package io.github.mojolowjo.entropybot.nav.moves;

import com.google.common.collect.ImmutableSet;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.cost.MovementStatus;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dig or climb straight down one block.
 *
 * <p>Entropy Bot (nav step 5): the cost half of Baritone's {@code MovementDownward} only, same code over {@link SearchContext} and
 * {@link BlockRules}; the driving half (updateState) comes with the pilot in step 8. {@code MovesMatchForkTest}
 * checks every price against the fork jar to the tick.
 *
 * <p>Pure (JUnit: MovesMatchForkTest). Loader notes: block classes and BlockState (common side, Mojang names).
 */
public final class DownwardCost implements ActionCosts {

    private DownwardCost() {}

    public static double cost(SearchContext context, int x, int y, int z) {
        if (!context.allowDownward) {
            return COST_INF;
        }
        if (!BlockRules.canWalkOn(context, x, y - 2, z)) {
            return COST_INF;
        }
        BlockState down = context.get(x, y - 1, z);
        Block downBlock = down.getBlock();
        if (downBlock == Blocks.LADDER || downBlock == Blocks.VINE) {
            return LADDER_DOWN_ONE_COST;
        } else {
            // we're standing on it, while it might be block falling, it'll be air by the time we get here in the movement
            return FALL_N_BLOCKS_COST[1] + BlockRules.getMiningDurationTicks(context, x, y - 1, z, down, false);
        }
    }
}
