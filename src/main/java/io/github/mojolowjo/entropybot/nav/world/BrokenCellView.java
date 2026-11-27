package io.github.mojolowjo.entropybot.nav.world;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * nav step 4: a view of another {@link BlockView} with one cell treated as air, the fork's {@code BrokenCellView}
 * (fork-only class, rewritten here): the cost advisor judges the block under a falling block as if the falling block
 * were already broken. Reused across asks by moving the cell with {@link #at}; one search thread only.
 *
 * <p>Pure (JUnit: SearchContextTest). Loader notes: Blocks.AIR (common side, Mojang names).
 */
public final class BrokenCellView extends BlockView {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private final BlockView base;
    private int bx;
    private int by;
    private int bz;

    public BrokenCellView(BlockView base) {
        super(base.worldBorder, base.minBuildHeight(), base.height());
        this.base = base;
    }

    public BrokenCellView at(int x, int y, int z) {
        bx = x;
        by = y;
        bz = z;
        return this;
    }

    @Override
    public BlockState get0(int x, int y, int z) {
        return x == bx && y == by && z == bz ? AIR : base.get0(x, y, z);
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return base.isLoaded(x, z);
    }

    @Override
    public boolean worldContainsLoadedChunk(int blockX, int blockZ) {
        return base.worldContainsLoadedChunk(blockX, blockZ);
    }
}
