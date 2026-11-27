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
 * Modified for Entropy Bot 2026-11-27: renamed MoveCatalog; prices only (apply0, which built the executable Movement, left for the pilot)
 */

package io.github.mojolowjo.entropybot.nav.moves;

import io.github.mojolowjo.entropybot.nav.cost.MutableMoveResult;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import net.minecraft.core.Direction;

/**
 * An enum of all possible movements attached to all possible directions they could be taken in
 *
 * <p>Entropy Bot (nav step 5): Baritone's {@code Moves}, the same entries in the same order (the search tries them in
 * this order, so ties break the same way), pricing only. {@code MovesMatchForkTest} checks each entry's result
 * (destination and cost) against the fork jar's {@code Moves}.
 *
 * <p>Pure (JUnit: MovesMatchForkTest). Loader notes: Direction (common side, Mojang names).
 *
 * @author leijurv
 */
public enum MoveCatalog {
    DOWNWARD(0, -1, 0) {
        @Override
        public double cost(SearchContext context, int x, int y, int z) {
            return DownwardCost.cost(context, x, y, z);
        }
    },

    PILLAR(0, +1, 0) {
        @Override
        public double cost(SearchContext context, int x, int y, int z) {
            return PillarCost.cost(context, x, y, z);
        }
    },

    TRAVERSE_NORTH(0, 0, -1) {
        @Override
        public double cost(SearchContext context, int x, int y, int z) {
            return TraverseCost.cost(context, x, y, z, x, z - 1);
        }
    },

    TRAVERSE_SOUTH(0, 0, +1) {
        @Override
        public double cost(SearchContext context, int x, int y, int z) {
            return TraverseCost.cost(context, x, y, z, x, z + 1);
        }
    },

    TRAVERSE_EAST(+1, 0, 0) {
        @Override
        public double cost(SearchContext context, int x, int y, int z) {
            return TraverseCost.cost(context, x, y, z, x + 1, z);
        }
    },

    TRAVERSE_WEST(-1, 0, 0) {
        @Override
        public double cost(SearchContext context, int x, int y, int z) {
            return TraverseCost.cost(context, x, y, z, x - 1, z);
        }
    },

    ASCEND_NORTH(0, +1, -1) {
        @Override
        public double cost(SearchContext context, int x, int y, int z) {
            return AscendCost.cost(context, x, y, z, x, z - 1);
        }
    },

    ASCEND_SOUTH(0, +1, +1) {
        @Override
        public double cost(SearchContext context, int x, int y, int z) {
            return AscendCost.cost(context, x, y, z, x, z + 1);
        }
    },

    ASCEND_EAST(+1, +1, 0) {
        @Override
        public double cost(SearchContext context, int x, int y, int z) {
            return AscendCost.cost(context, x, y, z, x + 1, z);
        }
    },

    ASCEND_WEST(-1, +1, 0) {
        @Override
        public double cost(SearchContext context, int x, int y, int z) {
            return AscendCost.cost(context, x, y, z, x - 1, z);
        }
    },

    DESCEND_EAST(+1, -1, 0, false, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            DescendCost.cost(context, x, y, z, x + 1, z, result);
        }
    },

    DESCEND_WEST(-1, -1, 0, false, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            DescendCost.cost(context, x, y, z, x - 1, z, result);
        }
    },

    DESCEND_NORTH(0, -1, -1, false, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            DescendCost.cost(context, x, y, z, x, z - 1, result);
        }
    },

    DESCEND_SOUTH(0, -1, +1, false, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            DescendCost.cost(context, x, y, z, x, z + 1, result);
        }
    },

    DIAGONAL_NORTHEAST(+1, 0, -1, false, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            DiagonalCost.cost(context, x, y, z, x + 1, z - 1, result);
        }
    },

    DIAGONAL_NORTHWEST(-1, 0, -1, false, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            DiagonalCost.cost(context, x, y, z, x - 1, z - 1, result);
        }
    },

    DIAGONAL_SOUTHEAST(+1, 0, +1, false, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            DiagonalCost.cost(context, x, y, z, x + 1, z + 1, result);
        }
    },

    DIAGONAL_SOUTHWEST(-1, 0, +1, false, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            DiagonalCost.cost(context, x, y, z, x - 1, z + 1, result);
        }
    },

    PARKOUR_NORTH(0, 0, -4, true, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            ParkourCost.cost(context, x, y, z, Direction.NORTH, result);
        }
    },

    PARKOUR_SOUTH(0, 0, +4, true, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            ParkourCost.cost(context, x, y, z, Direction.SOUTH, result);
        }
    },

    PARKOUR_EAST(+4, 0, 0, true, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            ParkourCost.cost(context, x, y, z, Direction.EAST, result);
        }
    },

    PARKOUR_WEST(-4, 0, 0, true, true) {
        @Override
        public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
            ParkourCost.cost(context, x, y, z, Direction.WEST, result);
        }
    };

    /** The four sides and down: where a placed block can lean (Baritone's constant on {@code Movement}). */
    public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    public final boolean dynamicXZ;
    public final boolean dynamicY;

    public final int xOffset;
    public final int yOffset;
    public final int zOffset;

    MoveCatalog(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
        this.dynamicXZ = dynamicXZ;
        this.dynamicY = dynamicY;
    }

    MoveCatalog(int x, int y, int z) {
        this(x, y, z, false, false);
    }

    public void apply(SearchContext context, int x, int y, int z, MutableMoveResult result) {
        if (dynamicXZ || dynamicY) {
            throw new UnsupportedOperationException("Movements with dynamic offset must override `apply`");
        }
        result.x = x + xOffset;
        result.y = y + yOffset;
        result.z = z + zOffset;
        result.cost = cost(context, x, y, z);
    }

    public double cost(SearchContext context, int x, int y, int z) {
        throw new UnsupportedOperationException("Movements must override `cost` or `apply`");
    }
}
