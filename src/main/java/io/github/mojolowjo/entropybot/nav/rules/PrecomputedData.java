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
 * Modified for Entropy Bot 2026-11-27: asks a BlockRules (one config) instead of the static MovementHelper
 */

package io.github.mojolowjo.entropybot.nav.rules;

import io.github.mojolowjo.entropybot.nav.util.Ternary;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per block state, the three state-only answers (walk on, walk through, fully passable) worked out once and kept in a
 * byte; a MAYBE falls through to the position check.
 *
 * <p>Entropy Bot: asks one {@link BlockRules} (one config) instead of the static MovementHelper, so the cache is right
 * for that config only; {@link BlockRules} owns it. Pure (JUnit: RulesMatchForkTest). Loader notes:
 * Block.BLOCK_STATE_REGISTRY (common side, Mojang names).
 */
public class PrecomputedData {

    private final byte[] data = new byte[Block.BLOCK_STATE_REGISTRY.size()];
    private final BlockRules rules;

    PrecomputedData(BlockRules rules) {
        this.rules = rules;
    }

    /**
     * byte layout
     *
     *          7              6             5              4              3              2              1             0
     *          |              |             |              |              |              |              |             |
     *      unused         canWalkOn       maybe       canWalkThrough    maybe        fullyPassable    maybe       completed
     */

    private static final byte COMPLETED_MASK = (byte) 1 << 0;
    private static final byte FULLY_PASSABLE_MAYBE_MASK = (byte) 1 << 1;
    private static final byte FULLY_PASSABLE_MASK = (byte) 1 << 2;
    private static final byte CAN_WALK_THROUGH_MAYBE_MASK = (byte) 1 << 3;
    private static final byte CAN_WALK_THROUGH_MASK = (byte) 1 << 4;
    private static final byte CAN_WALK_ON_MAYBE_MASK = (byte) 1 << 5;
    private static final byte CAN_WALK_ON_MASK = (byte) 1 << 6;

    private int fillData(int id, BlockState state) {
        byte blockData = 0;

        Ternary canWalkOnState = rules.canWalkOnBlockState(state);
        switch (canWalkOnState) {
            case YES -> blockData |= CAN_WALK_ON_MASK;
            case MAYBE -> blockData |= CAN_WALK_ON_MAYBE_MASK;
        }

        Ternary canWalkThroughState = rules.canWalkThroughBlockState(state);
        switch (canWalkThroughState) {
            case YES -> blockData |= CAN_WALK_THROUGH_MASK;
            case MAYBE -> blockData |= CAN_WALK_THROUGH_MAYBE_MASK;
        }

        Ternary fullyPassableState = rules.fullyPassableBlockState(state);
        switch (fullyPassableState) {
            case YES -> blockData |= FULLY_PASSABLE_MASK;
            case MAYBE -> blockData |= FULLY_PASSABLE_MAYBE_MASK;
        }

        blockData |= COMPLETED_MASK;

        data[id] = blockData; // in theory, this is thread "safe" because every thread should compute the exact same int to write?
        return blockData;
    }

    public boolean canWalkOn(BlockView bsi, int x, int y, int z, BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) { // we need to fill in the data
            blockData = fillData(id, state);
        }

        if ((blockData & CAN_WALK_ON_MAYBE_MASK) != 0) {
            return rules.canWalkOnPosition(bsi, x, y, z, state);
        } else {
            return (blockData & CAN_WALK_ON_MASK) != 0;
        }
    }

    public boolean canWalkThrough(BlockView bsi, int x, int y, int z, BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) { // we need to fill in the data
            blockData = fillData(id, state);
        }

        if ((blockData & CAN_WALK_THROUGH_MAYBE_MASK) != 0) {
            return rules.canWalkThroughPosition(bsi, x, y, z, state);
        } else {
            return (blockData & CAN_WALK_THROUGH_MASK) != 0;
        }
    }

    public boolean fullyPassable(BlockView bsi, int x, int y, int z, BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) { // we need to fill in the data
            blockData = fillData(id, state);
        }

        if ((blockData & FULLY_PASSABLE_MAYBE_MASK) != 0) {
            return rules.fullyPassablePosition(bsi, x, y, z, state);
        } else {
            return (blockData & FULLY_PASSABLE_MASK) != 0;
        }
    }
}
