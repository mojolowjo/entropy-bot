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
 * Modified for Entropy Bot 2026-11-27: BlockRules's planning half as an instance over NavConfig (no global settings); the driving half (tool switch, moveTowards, doors, placing, ctx overloads) left for the pilot; the fork's changes ported (KeepCells, avoidBreakingWhy, break next to water/lava, snow layers, lava cells, under water, FluidFlags, mining under a broken block)
 */

package io.github.mojolowjo.entropybot.nav.rules;

import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.util.Ternary;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import io.github.mojolowjo.entropybot.nav.world.FluidFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.level.pathfinder.PathComputationType;

import static io.github.mojolowjo.entropybot.nav.cost.ActionCosts.COST_INF;
import static io.github.mojolowjo.entropybot.nav.util.Ternary.*;

/**
 * Static helpers for cost calculation
 *
 * <p>Entropy Bot (nav step 4): Baritone's {@code MovementHelper}, planning half only. The rules that read a setting
 * are instance methods of one {@link BlockRules} built from a {@link NavConfig} (no global settings); the ones that
 * take a {@link SearchContext} stay static and reach the rules through {@code context.rules}, so the lifted moves
 * call them as before; the pure block facts stay static. Names and order are Baritone's. Left for the pilot (step 8):
 * the player-context overloads, tool switching, moveTowards, doors and gates, block placing, steppingOnBlocks.
 * The fork's changes (1.11.3-entropy) are in and marked "fork": kept cells, {@link #avoidBreakingWhy}, breaking next
 * to water or lava, snow layers, lava cells, water under water, the cached fluid facts, and mining under a broken
 * falling block. {@code RulesMatchForkTest} checks every answer against the fork jar's MovementHelper.
 *
 * <p>Pure (JUnit: RulesMatchForkTest, BlockRulesTest). Loader notes: block classes, BlockState, FluidTags,
 * BuiltInRegistries (common side, Mojang names).
 *
 * @author leijurv
 */
public final class BlockRules implements ActionCosts {

    /** Cells the route must not break (fork: KeepCells, the block a running job is for). */
    @FunctionalInterface
    public interface Cells {
        Cells NONE = (x, y, z) -> false;

        boolean has(int x, int y, int z);
    }

    public final NavConfig config;
    final PrecomputedData precomputed;
    private final NavConfig.Moves moves;
    private final NavConfig.Breaking breaking;
    private final Cells kept;

    public BlockRules(NavConfig config) {
        this(config, Cells.NONE);
    }

    public BlockRules(NavConfig config, Cells kept) {
        this.config = config;
        this.moves = config.moves();
        this.breaking = config.breaking();
        this.kept = kept;
        this.precomputed = new PrecomputedData(this);
    }

    public boolean avoidBreaking(BlockView bsi, int x, int y, int z, BlockState state) {
        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return true;
        }
        Block b = state.getBlock();
        return breaking.blocksToDisallowBreaking().contains(b)
                || kept.has(x, y, z) // fork: the block a running job is for (KeepCells)
                || b == Blocks.ICE // ice becomes water, and water can mess up the path
                || b instanceof InfestedBlock // obvious reasons
                // call context.get directly with x,y,z. no need to make 5 new BlockPos for no reason
                || avoidAdjacentBreaking(bsi, x, y + 1, z, true)
                || avoidAdjacentBreaking(bsi, x + 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x - 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x, y, z + 1, false)
                || avoidAdjacentBreaking(bsi, x, y, z - 1, false);
    }

    /**
     * Fork: why {@link #avoidBreaking} says no, as text for the owner ("ice (it turns to water)"), or null when it
     * would allow the break.
     */
    public String avoidBreakingWhy(BlockView bsi, int x, int y, int z, BlockState state) {
        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return "outside the world border";
        }
        Block b = state.getBlock();
        if (breaking.blocksToDisallowBreaking().contains(b)) {
            return "on blocksToDisallowBreaking";
        }
        if (kept.has(x, y, z)) {
            return "the block a running job is for (kept)";
        }
        if (b == Blocks.ICE) {
            return "ice (it turns to water)";
        }
        if (b instanceof InfestedBlock) {
            return "an infested block";
        }
        int[][] around = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            int nx = x + d[0];
            int ny = y + d[1];
            int nz = z + d[2];
            if (avoidAdjacentBreaking(bsi, nx, ny, nz, d[1] == 1)) {
                BlockState n = bsi.get0(nx, ny, nz);
                String kind = n.getFluidState().isEmpty() && n.getBlock() instanceof FallingBlock ? "an unsupported falling block" : "liquid";
                return "an avoided neighbour: " + kind + " (" + BuiltInRegistries.BLOCK.getKey(n.getBlock()) + ") at "
                        + nx + " " + ny + " " + nz + (d[1] == 1 ? " above it" : " beside it");
            }
        }
        return null;
    }

    public boolean avoidAdjacentBreaking(BlockView bsi, int x, int y, int z, boolean directlyAbove) {
        // returns true if you should avoid breaking a block that's adjacent to this one (e.g. lava that will start flowing if you give it a path)
        // this is only called for north, south, east, west, and up. this is NOT called for down.
        // we assume that it's ALWAYS okay to break the block thats ABOVE liquid
        BlockState state = bsi.get0(x, y, z);
        Block block = state.getBlock();
        if (!directlyAbove // it is fine to mine a block that has a falling block directly above, this (the cost of breaking the stacked fallings) is included in cost calculations
                // therefore if directlyAbove is true, we will actually ignore if this is falling
                && block instanceof FallingBlock // obviously, this check is only valid for falling blocks
                && breaking.avoidUpdatingFallingBlocks() // and if the setting is enabled
                && FallingBlock.isFree(bsi.get0(x, y - 1, z))) { // and if it would fall (i.e. it's unsupported)
            return true; // dont break a block that is adjacent to unsupported gravel because it can cause really weird stuff
        }
        // only pure liquids for now
        // waterlogged blocks can have closed bottom sides and such
        if (block instanceof LiquidBlock) {
            // fork: allowBreakNextToWater / allowBreakNextToLava let the route open a face onto that liquid
            if (breaking.allowBreakNextToWater() && state.getFluidState().is(FluidTags.WATER)) {
                return false;
            }
            if (breaking.allowBreakNextToLava() && state.getFluidState().is(FluidTags.LAVA)) {
                return false;
            }
            if (directlyAbove || moves.strictLiquidCheck()) {
                return true;
            }
            int level = state.getValue(LiquidBlock.LEVEL);
            if (level == 0) {
                return true; // source blocks like to flow horizontally
            }
            // everything else will prefer flowing down
            return !(bsi.get0(x, y - 1, z).getBlock() instanceof LiquidBlock); // assume everything is in a static state
        }
        return !FluidFlags.isEmpty(state);
    }

    public boolean canWalkThrough(BlockView bsi, int x, int y, int z) {
        return canWalkThrough(bsi, x, y, z, bsi.get0(x, y, z));
    }

    public static boolean canWalkThrough(SearchContext context, int x, int y, int z, BlockState state) {
        return context.precomputedData.canWalkThrough(context.bsi, x, y, z, state);
    }

    public static boolean canWalkThrough(SearchContext context, int x, int y, int z) {
        return context.precomputedData.canWalkThrough(context.bsi, x, y, z, context.get(x, y, z));
    }

    public boolean canWalkThrough(BlockView bsi, int x, int y, int z, BlockState state) {
        Ternary canWalkThrough = canWalkThroughBlockState(state);
        if (canWalkThrough == YES) {
            return true;
        }
        if (canWalkThrough == NO) {
            return false;
        }
        return canWalkThroughPosition(bsi, x, y, z, state);
    }

    public Ternary canWalkThroughBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return YES;
        }
        if (block instanceof BaseFireBlock || block == Blocks.COBWEB || block == Blocks.END_PORTAL || block == Blocks.COCOA || block instanceof AbstractSkullBlock || block == Blocks.BUBBLE_COLUMN || block instanceof ShulkerBoxBlock || block instanceof SlabBlock || block instanceof TrapDoorBlock || block == Blocks.HONEY_BLOCK || block == Blocks.END_ROD || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POINTED_DRIPSTONE || block instanceof AmethystClusterBlock || block instanceof AzaleaBlock) {
            return NO;
        }
        if (block == Blocks.BIG_DRIPLEAF) {
            return NO;
        }
        if (block == Blocks.POWDER_SNOW) {
            return NO;
        }
        if (moves.blocksToAvoid().contains(block)) {
            return NO;
        }
        if (block instanceof DoorBlock) {
            return DoorBlock.isWoodenDoor(state) ? YES : NO;
        }
        if (block instanceof FenceGateBlock) {
            return YES;
        }
        if (block instanceof CarpetBlock) {
            return MAYBE;
        }
        if (block instanceof SnowLayerBlock) {
            // snow layers cached as the top layer of a packed chunk have no metadata, we can't make a decision based on their depth here
            // it would otherwise make long distance pathing through snowy biomes impossible
            return MAYBE;
        }
        FluidState fluidState = state.getFluidState();
        if (!FluidFlags.isEmpty(state)) {
            if (fluidState.getType().getAmount(fluidState) != 8) {
                return NO;
            } else {
                return MAYBE;
            }
        }
        if (block instanceof CauldronBlock) {
            return NO;
        }
        if (state.isPathfindable(PathComputationType.LAND)) {
            return YES;
        } else {
            return NO;
        }
    }

    public boolean canWalkThroughPosition(BlockView bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();

        if (block instanceof CarpetBlock) {
            return canWalkOn(bsi, x, y - 1, z);
        }

        if (block instanceof SnowLayerBlock) {
            // if they're cached as a top block, we don't know their metadata
            // default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            // the check in BlockSnow.isPassable is layers < 5
            // while actually, we want < 3 because 3 or greater makes it impassable in a 2 high ceiling
            // fork (snow runs): up to 4 layers is walkable, 3+ needs head room above, 5+ is a block; a layer 2+ next
            // to a 5+ pile is refused when the step between them is more than half a block
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            if (layers > 4) {
                return false;
            }
            if (layers >= 3 && !bsi.get0(x, y + 2, z).isPathfindable(PathComputationType.LAND)) {
                return false;
            }
            if (layers >= 2) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if ((dx | dz) == 0) {
                            continue;
                        }
                        BlockState n = bsi.get0(x + dx, y - 1, z + dz);
                        if (n.getBlock() instanceof SnowLayerBlock) {
                            int a = n.getValue(SnowLayerBlock.LAYERS);
                            if (a >= 5 && 8 + (layers - a) > 4) {
                                return false;
                            }
                        }
                    }
                }
            }
            // ok, it's low enough we could walk through it, but is it supported?
            return canWalkOn(bsi, x, y - 1, z);
        }

        FluidState fluidState = state.getFluidState();
        if (!FluidFlags.isEmpty(state)) {
            if (isFlowing(x, y, z, state, bsi)) {
                return false;
            }
            if (moves.lavaCellCost() > 0 && fluidState.is(FluidTags.LAVA)) {
                return true; // fork (LavaWade): still lava is a cell the search may price (lavaCellCost per cell)
            }
            // Everything after this point has to be a special case as it relies on the water not being flowing, which means a special case is needed.
            if (moves.assumeWalkOnWater()) {
                return false;
            }

            BlockState up = bsi.get0(x, y + 1, z);
            if (up.getBlock() instanceof WaterlilyBlock) {
                return false;
            }
            if (!up.getFluidState().isEmpty() && !underWaterOk(fluidState, up)) {
                return false; // fork (UnderWater): with pathUnderWater, water under water is passable
            }
            return fluidState.getType() instanceof WaterFluid;
        }

        return state.isPathfindable(PathComputationType.LAND);
    }

    /** Fork (UnderWater): with pathUnderWater, a water cell with water above it is passable (swim, not stand). */
    public boolean underWaterOk(FluidState here, BlockState up) {
        return moves.pathUnderWater() && here.getType() instanceof WaterFluid && up.getFluidState().getType() instanceof WaterFluid;
    }

    public static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) { // early return for most common case
            return YES;
        }
        // exceptions - blocks that are isPassable true, but we can't actually jump through
        if (block instanceof BaseFireBlock
                || block == Blocks.TRIPWIRE
                || block == Blocks.COBWEB
                || block == Blocks.VINE
                || block == Blocks.LADDER
                || block == Blocks.COCOA
                || block instanceof AzaleaBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof SnowLayerBlock
                || !FluidFlags.isEmpty(state)
                || block instanceof TrapDoorBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                || block instanceof ShulkerBoxBlock) {
            return NO;
        }
        // door, fence gate, liquid, trapdoor have been accounted for, nothing else uses the world or pos parameters
        // at least in 1.12.2 vanilla, that is.....
        if (state.isPathfindable(PathComputationType.LAND)) {
            return YES;
        } else {
            return NO;
        }
    }

    /**
     * canWalkThrough but also won't impede movement at all. so not including doors or fence gates (we'd have to right click),
     * not including water, and not including ladders or vines or cobwebs (they slow us down)
     */
    public static boolean fullyPassable(SearchContext context, int x, int y, int z) {
        return fullyPassable(context, x, y, z, context.get(x, y, z));
    }

    public static boolean fullyPassable(SearchContext context, int x, int y, int z, BlockState state) {
        return context.precomputedData.fullyPassable(context.bsi, x, y, z, state);
    }

    /**
     * params retained for backwards compatibility
     */
    public static boolean fullyPassablePosition(BlockView bsi, int x, int y, int z, BlockState state) {
        return state.isPathfindable(PathComputationType.LAND);
    }

    public static boolean isReplaceable(int x, int y, int z, BlockState state, BlockView bsi) {
        // for MovementTraverse and MovementAscend
        // block double plant defaults to true when the block doesn't match, so don't need to check that case
        // all other overrides just return true or false
        // the only case to deal with is snow
        /*
         *  public boolean isReplaceable(IBlockAccess worldIn, BlockPos pos)
         *     {
         *         return ((Integer)worldIn.getBlockState(pos).getValue(LAYERS)).intValue() == 1;
         *     }
         */
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            // early return for common cases hehe
            return true;
        }
        if (block instanceof SnowLayerBlock) {
            // as before, default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            return state.getValue(SnowLayerBlock.LAYERS) == 1;
        }
        if (block == Blocks.LARGE_FERN || block == Blocks.TALL_GRASS) {
            return true;
        }
        return state.canBeReplaced();
    }

    public static boolean isDoorPassable(BlockState state, Direction side) {
        boolean open = state.getValue(DoorBlock.OPEN);
        Direction closedFacing = state.getValue(HorizontalDirectionalBlock.FACING);
        Direction openFacing = state.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT
                ? closedFacing.getClockWise()
                : closedFacing.getCounterClockWise();

        return side != (open ? openFacing : closedFacing);
    }

    public boolean avoidWalkingInto(BlockState state) {
        Block block = state.getBlock();
        return !FluidFlags.isEmpty(state)
                || (block == Blocks.MAGMA_BLOCK && !moves.allowWalkOnMagmaBlocks())
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block instanceof BaseFireBlock
                || block == Blocks.END_PORTAL
                || block == Blocks.COBWEB
                || block == Blocks.BUBBLE_COLUMN;
    }

    /**
     * Can I walk on this block without anything weird happening like me falling
     * through? Includes water because we know that we automatically jump on
     * water
     * <p>
     * If changing something in this function remember to also change it in precomputed data
     *
     * @param bsi   Block state provider
     * @param x     The block's x position
     * @param y     The block's y position
     * @param z     The block's z position
     * @param state The state of the block at the specified location
     * @return Whether or not the specified block can be walked on
     */
    public boolean canWalkOn(BlockView bsi, int x, int y, int z, BlockState state) {
        Ternary canWalkOn = canWalkOnBlockState(state);
        if (canWalkOn == YES) {
            return true;
        }
        if (canWalkOn == NO) {
            return false;
        }
        return canWalkOnPosition(bsi, x, y, z, state);
    }

    public Ternary canWalkOnBlockState(BlockState state) {
        Block block = state.getBlock();
        if (isBlockNormalCube(state) && (block != Blocks.MAGMA_BLOCK || moves.allowWalkOnMagmaBlocks()) && block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) {
            return YES;
        }
        if (block instanceof AzaleaBlock) {
            return YES;
        }
        if (block == Blocks.LADDER || (isClimbable(block) && moves.allowVines())) { // TODO reconsider this
            return YES;
        }
        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH || block == Blocks.SOUL_SAND) {
            return YES;
        }
        if (block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return YES;
        }
        if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
            return YES;
        }
        if (block instanceof StairBlock) {
            return YES;
        }
        if (isWater(state)) {
            return MAYBE;
        }
        if (BlockRules.isLava(state) && moves.assumeWalkOnLava()) {
            return MAYBE;
        }
        if (block instanceof SnowLayerBlock) {
            return MAYBE; // fork: 5+ layers stand like a block, decided per cell in canWalkOnPosition
        }
        if (block instanceof SlabBlock) {
            if (!moves.allowWalkOnBottomSlab()) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return YES;
                }
                return NO;
            }
            return YES;
        }
        return NO;
    }

    public boolean canWalkOnPosition(BlockView bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (isWater(state)) {
            // since this is called literally millions of times per second, the benefit of not allocating millions of useless "pos.up()"
            // BlockPos s that we'd just garbage collect immediately is actually noticeable. I don't even think its a decrease in readability
            BlockState upState = bsi.get0(x, y + 1, z);
            Block up = upState.getBlock();
            if (up == Blocks.LILY_PAD || up instanceof CarpetBlock) {
                return true;
            }
            if (BlockRules.isFlowing(x, y, z, state, bsi) || upState.getFluidState().getType() == Fluids.FLOWING_WATER) {
                // the only scenario in which we can walk on flowing water is if it's under still water with jesus off
                return isWater(upState) && !moves.assumeWalkOnWater();
            }
            // if assumeWalkOnWater is on, we can only walk on water if there isn't water above it
            // if assumeWalkOnWater is off, we can only walk on water if there is water above it
            return isWater(upState) ^ moves.assumeWalkOnWater();
        }

        if (BlockRules.isLava(state) && !BlockRules.isFlowing(x, y, z, state, bsi) && moves.assumeWalkOnLava()) { // if we get here it means that assumeWalkOnLava must be true, so put it last
            return true;
        }

        if (block instanceof SnowLayerBlock) { // fork: a 5+ layer pile in a loaded chunk is walked on like a block
            return bsi.worldContainsLoadedChunk(x, z) && state.getValue(SnowLayerBlock.LAYERS) >= 5;
        }

        return false; // If we don't recognise it then we want to just return false to be safe.
    }

    public static boolean canWalkOn(SearchContext context, int x, int y, int z, BlockState state) {
        return context.precomputedData.canWalkOn(context.bsi, x, y, z, state);
    }

    public static boolean canWalkOn(SearchContext context, int x, int y, int z) {
        return canWalkOn(context, x, y, z, context.get(x, y, z));
    }

    public boolean canWalkOn(BlockView bsi, int x, int y, int z) {
        return canWalkOn(bsi, x, y, z, bsi.get0(x, y, z));
    }

    public static boolean canUseFrostWalker(SearchContext context, BlockState state) {
        return context.frostWalker != 0
                && state == FrostedIceBlock.meltsInto()
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /**
     * If movements make us stand/walk on this block, will it have a top to walk on?
     */
    public static boolean mustBeSolidToWalkOn(SearchContext context, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (isClimbable(block)) {
            return false;
        }
        if (!FluidFlags.isEmpty(state)) {
            // used for frostwalker so only includes blocks where we are still on ground when leaving them to any side
            if (block instanceof SlabBlock) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return true;
                }
            } else if (block instanceof StairBlock) {
                if (state.getValue(StairBlock.HALF) == Half.TOP) {
                    return true;
                }
                StairsShape shape = state.getValue(StairBlock.SHAPE);
                if (shape == StairsShape.INNER_LEFT || shape == StairsShape.INNER_RIGHT) {
                    return true;
                }
            } else if (block instanceof TrapDoorBlock) {
                if (!state.getValue(TrapDoorBlock.OPEN) && state.getValue(TrapDoorBlock.HALF) == Half.TOP) {
                    return true;
                }
            } else if (block == Blocks.SCAFFOLDING) {
                return true;
            } else if (block instanceof LeavesBlock) {
                return true;
            }
            if (context.assumeWalkOnWater) {
                return false;
            }
            Block blockAbove = context.getBlock(x, y + 1, z);
            if (blockAbove instanceof LiquidBlock) {
                return false;
            }
        }
        return true;
    }

    public static boolean canPlaceAgainst(BlockView bsi, int x, int y, int z) {
        return canPlaceAgainst(bsi, x, y, z, bsi.get0(x, y, z));
    }

    public static boolean canPlaceAgainst(BlockView bsi, BlockPos pos) {
        return canPlaceAgainst(bsi, pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean canPlaceAgainst(BlockView bsi, int x, int y, int z, BlockState state) {
        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return false;
        }
        // can we look at the center of a side face of this block and likely be able to place?
        // (thats how this check is used)
        // therefore dont include weird things that we technically could place against (like carpet) but practically can't
        return isBlockNormalCube(state) || state.getBlock() == Blocks.GLASS || state.getBlock() instanceof StainedGlassBlock;
    }

    /**
     * Can we climb up this block by pressing space while inside it?
     * Also doubles as "If I start a movement on this, can weird things happen?"
     * because movements can end/start on these blocks despite them not being canWalkOn.
     */
    public static boolean isClimbable(Block block) {
        return block == Blocks.LADDER
            || block == Blocks.VINE
            || block == Blocks.WEEPING_VINES
            || block == Blocks.WEEPING_VINES_PLANT
            || block == Blocks.TWISTING_VINES
            || block == Blocks.TWISTING_VINES_PLANT;
    }

    public static double getMiningDurationTicks(SearchContext context, int x, int y, int z, boolean includeFalling) {
        return getMiningDurationTicks(context, x, y, z, context.get(x, y, z), includeFalling);
    }

    public static double getMiningDurationTicks(SearchContext context, int x, int y, int z, BlockState state, boolean includeFalling) {
        Block block = state.getBlock();
        if (!canWalkThrough(context, x, y, z, state)) {
            if (!FluidFlags.isEmpty(state)) {
                return COST_INF;
            }
            double mult = context.breakCostMultiplierAt(x, y, z, state);
            if (mult >= COST_INF) {
                return COST_INF;
            }
            if (context.rules.avoidBreaking(context.bsi, x, y, z, state)) {
                return COST_INF;
            }
            double strVsBlock = context.toolSet.getStrVsBlock(state);
            if (strVsBlock <= 0) {
                return COST_INF;
            }
            double result = 1 / strVsBlock;
            result += context.breakBlockAdditionalCost;
            result *= mult;
            if (includeFalling) {
                BlockState above = context.get(x, y + 1, z);
                if (above.getBlock() instanceof FallingBlock) {
                    result += getMiningDurationTicks(context, x, y + 1, z, above, true);
                }
            }
            return result;
        }
        return 0; // we won't actually mine it, so don't check fallings above
    }

    /**
     * Fork: the mining time of x y z when the falling block above it will be broken first (MovementTraverse's lower
     * cell): the cost advisor judges it with the upper cell already air.
     */
    public static double getMiningDurationTicksUnderBroken(SearchContext context, int x, int y, int z, BlockState state) {
        BlockState upper = context.get(x, y + 1, z);
        if (context.advisor != null && upper.getBlock() instanceof FallingBlock && !canWalkThrough(context, x, y + 1, z, upper)) {
            context.advisorSeesBrokenAt(x, y + 1, z);
            try {
                return getMiningDurationTicks(context, x, y, z, state, false);
            } finally {
                context.advisorSeesTheMap();
            }
        }
        return getMiningDurationTicks(context, x, y, z, state, false);
    }

    public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /**
     * Returns whether or not the specified block is
     * water, regardless of whether or not it is flowing.
     *
     * @param state The block state
     * @return Whether or not the block is water
     */
    public static boolean isWater(BlockState state) {
        return FluidFlags.isWater(state); // fork: cached fluid facts
    }

    public static boolean isLava(BlockState state) {
        return FluidFlags.isLava(state); // fork: cached fluid facts
    }

    public static boolean isLiquid(BlockState blockState) {
        return !FluidFlags.isEmpty(blockState);
    }

    public static boolean possiblyFlowing(BlockState state) {
        return FluidFlags.possiblyFlowing(state); // fork: cached fluid facts
    }

    public static boolean isFlowing(int x, int y, int z, BlockState state, BlockView bsi) {
        FluidState fluidState = state.getFluidState();
        if (!(fluidState.getType() instanceof FlowingFluid)) {
            return false;
        }
        if (fluidState.getType().getAmount(fluidState) != 8) {
            return true;
        }
        return possiblyFlowing(bsi.get0(x + 1, y, z))
                || possiblyFlowing(bsi.get0(x - 1, y, z))
                || possiblyFlowing(bsi.get0(x, y, z + 1))
                || possiblyFlowing(bsi.get0(x, y, z - 1));
    }

    public static boolean isBlockNormalCube(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BambooStalkBlock
                || block instanceof MovingPistonBlock
                || block instanceof ScaffoldingBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof AmethystClusterBlock) {
            return false;
        }
        try {
            return Block.isShapeFullBlock(state.getCollisionShape(null, null));
        } catch (Exception e) {
            // silent: Baritone's rule, a block whose collision shape needs a real world is treated as not a cube
        }
        return false;
    }

    public static boolean isTransparent(Block b) {

        return b instanceof AirBlock ||
                b == Blocks.LAVA ||
                b == Blocks.WATER;
    }
}
