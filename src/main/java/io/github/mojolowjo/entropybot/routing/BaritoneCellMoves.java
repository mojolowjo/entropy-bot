package io.github.mojolowjo.entropybot.routing;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.Moves;
import baritone.utils.pathing.MutableMoveResult;
import io.github.mojolowjo.entropybot.route.CellMoves;
import io.github.mojolowjo.entropybot.route.MoveSink;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link CellMoves} on Baritone's own cost model (review R4): one {@link CalculationContext} made on the game thread with
 * {@code forUseOnAnotherThread = true}, so its {@code BlockStateInterface} reads a thread-safe copy of the client's
 * chunk array ({@code IClientChunkProvider.createThreadSafeCopy()}) and, where a chunk isn't loaded, Baritone's
 * {@code CachedWorld} (2 bits a block: the coarse quality). This is exactly how Baritone's own path thread reads the
 * world. No mixin, no snapshot of our own.
 *
 * <p>Moves: every {@link Moves} value through {@code Moves.apply(ctx, x, y, z, MutableMoveResult)}, the call
 * {@code AStarPathFinder.calculate0} makes per node; impossible moves ({@code ActionCosts.COST_INF}) are dropped.
 * Parkour moves come back impossible because the context's {@code allowParkour} is the bot's setting (off).
 *
 * <p>One thread only: the BlockStateInterface caches the last chunk, and {@link MutableMoveResult} is reused.
 *
 * <p>Verified with javap on {@code baritone-unoptimized-neoforge-1.11.3.jar} (2026-10-04): {@code CalculationContext(IBaritone,
 * boolean)} public; its public final fields {@code allowBreak}, {@code hasThrowaway}, {@code canSprint},
 * {@code allowParkour}, {@code bsi}; {@code Moves.apply(CalculationContext,int,int,int,MutableMoveResult)} and
 * {@code Moves.cost(CalculationContext,int,int,int)} public; {@code MutableMoveResult} public {@code x,y,z,cost} and
 * {@code reset()}; {@code MovementHelper.canWalkOn(CalculationContext,int,int,int)},
 * {@code canWalkThrough(CalculationContext,int,int,int)}, {@code isWater(BlockState)}, {@code avoidWalkingInto(BlockState)}
 * public static; {@code BlockStateInterface(IPlayerContext, boolean)} throws "must be constructed on the main thread"
 * off the game thread (so the context must be made there).
 */
public final class BaritoneCellMoves implements CellMoves {
    private static final Moves[] MOVES = Moves.values();

    private final CalculationContext ctx;
    private final MutableMoveResult res = new MutableMoveResult();

    private BaritoneCellMoves(CalculationContext ctx) {
        this.ctx = ctx;
    }

    /** Makes the context. Game thread only (Baritone reads the player, inventory and settings, and checks the thread). */
    public static BaritoneCellMoves create(IBaritone baritone) {
        return new BaritoneCellMoves(new CalculationContext(baritone, true));
    }

    /**
     * True when the context is walking-only: no breaking, no placing blocks (review R5). A box built from any other
     * context is never stored.
     */
    public boolean walkingOnly() {
        return !ctx.allowBreak && !ctx.hasThrowaway;
    }

    /**
     * False when the bot can't sprint now (food 6 or below) although the settings allow it: costs would be the slower
     * walking ones, an overestimate the heuristic must not learn. The builder waits for such a context.
     */
    public boolean sprintAsSettings(boolean allowSprintSetting) {
        return ctx.canSprint == allowSprintSetting;
    }

    @Override
    public boolean standable(int x, int y, int z) {
        BlockState feet = ctx.get(x, y, z);
        boolean through = MovementHelper.canWalkThrough(ctx, x, y, z);
        if (!through) return false;
        boolean head = MovementHelper.canWalkThrough(ctx, x, y + 1, z);
        if (!head) return false;
        boolean water = MovementHelper.isWater(feet);
        boolean climb = feet.getBlock() instanceof LadderBlock || feet.getBlock() instanceof VineBlock;
        return RouteRules.standable(MovementHelper.canWalkOn(ctx, x, y - 1, z), water, climb, true, true);
    }

    @Override
    public void forEachMove(int x, int y, int z, MoveSink s) {
        for (Moves m : MOVES) {
            res.reset();
            m.apply(ctx, x, y, z, res);
            if (RouteRules.usableMove(res.cost, ActionCosts.COST_INF, x, y, z, res.x, res.y, res.z))
                s.move(res.x, res.y, res.z, res.cost);
        }
    }
}
