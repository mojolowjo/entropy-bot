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
 * Entropy Bot 2026-11-27: the cells each Baritone Movement names in its constructor (positionsToBreak, positionToPlace)
 * and in calculateValidPositions, for the 8 movements (Fall for a descend of more than one block), without a Movement
 * object: the price's watched cells (step 7) need them before anything walks.
 */

package io.github.mojolowjo.entropybot.nav.moves;

import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import net.minecraft.core.Direction;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The cells one move names: the blocks it breaks (in the Movement's own order), the block it places (null: none), and
 * where the bot may stand during it (the Movement's valid positions, in the same set order: Baritone's ImmutableSet keeps
 * insertion order, MovementFall and MovementParkour use a HashSet). Pure (JUnit: PricingMatchForkTest compares every move
 * to the fork's Movement objects). Loader notes: vanilla {@code Direction} only.
 */
public record MoveShape(List<BetterBlockPos> breaks, BetterBlockPos place, Set<BetterBlockPos> valid) {

    /** The shape of {@code move} from {@code src} to {@code dest} (dest as Baritone's Moves.apply0 puts it). */
    public static MoveShape of(MoveCatalog move, BetterBlockPos src, BetterBlockPos dest) {
        switch (move) {
            case DOWNWARD:
                return new MoveShape(List.of(dest), null, ordered(src, dest));
            case PILLAR:
                return new MoveShape(List.of(src.above(2)), src, ordered(src, dest));
            case TRAVERSE_NORTH:
            case TRAVERSE_SOUTH:
            case TRAVERSE_EAST:
            case TRAVERSE_WEST:
                return new MoveShape(List.of(dest.above(), dest), dest.below(), ordered(src, dest));
            case ASCEND_NORTH:
            case ASCEND_SOUTH:
            case ASCEND_EAST:
            case ASCEND_WEST: {
                // MovementAscend: prior = src - direction, one up (direction = dest - src)
                BetterBlockPos prior = new BetterBlockPos(src.x - (dest.x - src.x), src.y - (dest.y - src.y) + 1, src.z - (dest.z - src.z));
                return new MoveShape(List.of(dest, src.above(2), dest.above()), dest.below(), ordered(src, src.above(), dest, prior, prior.above()));
            }
            case DESCEND_EAST:
            case DESCEND_WEST:
            case DESCEND_NORTH:
            case DESCEND_SOUTH:
                if (dest.y == src.y - 1) {
                    return new MoveShape(List.of(dest.above(2), dest.above(), dest), dest.below(), ordered(src, dest.above(), dest));
                }
                return fall(src, dest);
            case DIAGONAL_NORTHEAST:
            case DIAGONAL_NORTHWEST:
            case DIAGONAL_SOUTHEAST:
            case DIAGONAL_SOUTHWEST: {
                // Moves.apply0: MovementDiagonal(src, NORTH or SOUTH, EAST or WEST, dy): dir1 the z corner, dir2 the x corner
                BetterBlockPos dir1 = new BetterBlockPos(src.x, src.y, src.z + Integer.signum(move.zOffset));
                BetterBlockPos dir2 = new BetterBlockPos(src.x + Integer.signum(move.xOffset), src.y, src.z);
                List<BetterBlockPos> br = List.of(dir1, dir1.above(), dir2, dir2.above(), dest, dest.above());
                BetterBlockPos diagA = new BetterBlockPos(src.x, src.y, dest.z);
                BetterBlockPos diagB = new BetterBlockPos(dest.x, src.y, src.z);
                Set<BetterBlockPos> v;
                if (dest.y < src.y) {
                    v = ordered(src, dest.above(), diagA, diagB, dest, diagA.below(), diagB.below());
                } else if (dest.y > src.y) {
                    v = ordered(src, src.above(), diagA, diagB, dest, diagA.above(), diagB.above());
                } else {
                    v = ordered(src, dest, diagA, diagB);
                }
                return new MoveShape(br, null, v);
            }
            case PARKOUR_NORTH:
            case PARKOUR_SOUTH:
            case PARKOUR_EAST:
            case PARKOUR_WEST: {
                Direction dir = move == MoveCatalog.PARKOUR_NORTH ? Direction.NORTH : move == MoveCatalog.PARKOUR_SOUTH ? Direction.SOUTH
                        : move == MoveCatalog.PARKOUR_EAST ? Direction.EAST : Direction.WEST;
                int dist = Math.abs(dest.x - src.x) + Math.abs(dest.z - src.z);
                Set<BetterBlockPos> v = new HashSet<>();
                for (int i = 0; i <= dist; i++) {
                    for (int y = 0; y < 2; y++) {
                        v.add(src.relative(dir, i).above(y));
                    }
                }
                // MovementParkour: place = src.relative(dir, dist).below(ascend ? 0 : 1) = the cell under dest either way
                return new MoveShape(List.of(), src.relative(dir, dist).below(dest.y > src.y ? 0 : 1), v);
            }
            default:
                throw new IllegalArgumentException("no shape for " + move);
        }
    }

    /** MovementFall: the shaft at dest's x z from src.y + 1 down to dest.y, nothing placed (a bucket is not a block). */
    private static MoveShape fall(BetterBlockPos src, BetterBlockPos dest) {
        int diffY = Math.abs(src.y - dest.y);
        BetterBlockPos[] br = new BetterBlockPos[diffY + 2];
        for (int i = 0; i < br.length; i++) {
            br[i] = new BetterBlockPos(dest.x, src.y + 1 - i, dest.z);
        }
        Set<BetterBlockPos> v = new HashSet<>();
        v.add(src);
        for (int y = src.y - dest.y; y >= 0; y--) {
            v.add(dest.above(y));
        }
        return new MoveShape(List.of(br), null, v);
    }

    private static Set<BetterBlockPos> ordered(BetterBlockPos... cells) {
        Set<BetterBlockPos> out = new LinkedHashSet<>();
        for (BetterBlockPos c : cells) {
            out.add(c);
        }
        return out;
    }
}
