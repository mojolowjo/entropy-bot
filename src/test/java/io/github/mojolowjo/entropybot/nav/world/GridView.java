package io.github.mojolowjo.entropybot.nav.world;

import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.BetterWorldBorder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

/**
 * Test world for the nav tests: a flat floor of {@code floor} at y &lt;= {@code floorY}, air above, any cell set by
 * hand, loaded within {@code radius} blocks of 0 0 (beyond it nothing is loaded and get0 answers AIR, as an unloaded
 * Baritone view does). Overworld height (-64, 384).
 */
public final class GridView extends BlockView {
    private final Long2ObjectOpenHashMap<BlockState> cells = new Long2ObjectOpenHashMap<>();
    private final BlockState floor;
    private final int floorY;
    private final int radius;

    public GridView(BlockState floor, int floorY, int radius) {
        super(new BetterWorldBorder(new WorldBorder()), -64, 384);
        this.floor = floor;
        this.floorY = floorY;
        this.radius = radius;
    }

    public static GridView flat() { return new GridView(Blocks.STONE.defaultBlockState(), 63, 256); }

    public GridView set(int x, int y, int z, BlockState s) { cells.put(BetterBlockPos.longHash(x, y, z), s); return this; }

    public GridView fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, s);
        return this;
    }

    @Override
    public BlockState get0(int x, int y, int z) {
        if (y < minBuildHeight() || y >= minBuildHeight() + height() || !isLoaded(x, z)) return Blocks.AIR.defaultBlockState();
        BlockState s = cells.get(BetterBlockPos.longHash(x, y, z));
        if (s != null) return s;
        return y <= floorY ? floor : Blocks.AIR.defaultBlockState();
    }

    @Override
    public boolean isLoaded(int x, int z) { return Math.abs(x) <= radius && Math.abs(z) <= radius; }

    @Override
    public boolean worldContainsLoadedChunk(int blockX, int blockZ) { return isLoaded(blockX, blockZ); }
}
