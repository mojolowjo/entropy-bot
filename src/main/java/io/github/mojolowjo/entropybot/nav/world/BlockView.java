package io.github.mojolowjo.entropybot.nav.world;

import io.github.mojolowjo.entropybot.nav.util.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * nav step 3 (to-do/nav-steps-1-6.md): the planner's window on the world. It has the same public face as Baritone's
 * {@code BlockStateInterface} ({@code get0}, {@code isLoaded}, {@code worldContainsLoadedChunk}, the fields
 * {@code access}, {@code worldBorder}, {@code isPassableBlockPos}) so the lifted block rules read it unchanged, but it
 * is abstract: the real game view (loaded chunks copied for worker threads, step 8), Baritone's chunk cache (step 3b,
 * later), the ghost body's view and test worlds are each one subclass. One view is made per search and used by one
 * thread; {@code isPassableBlockPos} is that thread's scratch position, as in Baritone.
 *
 * <p>Pure (JUnit: BlockViewTest). Loader notes: vanilla value types only (BlockState, BlockGetter, BlockPos).
 */
public abstract class BlockView {
    public final BlockPos.MutableBlockPos isPassableBlockPos = new BlockPos.MutableBlockPos();
    public final BlockGetter access;
    public final BetterWorldBorder worldBorder;
    private final int minY;
    private final int height;

    protected BlockView(BetterWorldBorder worldBorder, int minY, int height) {
        this.worldBorder = worldBorder;
        this.minY = minY;
        this.height = height;
        this.access = new BlockViewAccess(this);
    }

    /** The block at x y z; outside the build height it is AIR, in a chunk the view has no data for it is AIR too. */
    public abstract BlockState get0(int x, int y, int z);

    public final BlockState get0(BlockPos pos) { return get0(pos.getX(), pos.getY(), pos.getZ()); }

    /** The view has data for this column (a loaded chunk, or a cached sketch of one). */
    public abstract boolean isLoaded(int x, int z);

    /** The column's chunk is really loaded in the game (not only a cached sketch). */
    public abstract boolean worldContainsLoadedChunk(int blockX, int blockZ);

    /** The lowest block y of the dimension (vanilla overworld: -64). */
    public final int minBuildHeight() { return minY; }

    /** The dimension's height in blocks (vanilla overworld: 384). */
    public final int height() { return height; }
}
