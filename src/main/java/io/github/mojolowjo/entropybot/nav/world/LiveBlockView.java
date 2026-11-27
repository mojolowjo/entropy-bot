package io.github.mojolowjo.entropybot.nav.world;

import io.github.mojolowjo.entropybot.nav.util.BetterWorldBorder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Nav step 9a: the game's blocks for our planner and drivers, read from the client level's loaded chunks (Baritone's
 * BlockStateInterface without its chunk cache and its off-thread copy). Read it on the game thread only: the step 9a
 * search runs there under a node and time budget. A column whose chunk is not loaded reads as AIR and
 * {@link #isLoaded} says so (the planner stops at it). Pure (JUnit: none, the adapter; the planner's tests use fake
 * views). Loader notes: vanilla {@code ClientLevel.getChunkSource().getChunk(x, z, FULL, false)},
 * {@code LevelChunk.getBlockState}; Fabric the same.
 */
public final class LiveBlockView extends BlockView {
    private final ClientLevel level;
    private LevelChunk last;
    private long lastKey = Long.MIN_VALUE;

    public LiveBlockView(ClientLevel level) {
        super(new BetterWorldBorder(level.getWorldBorder()), level.getMinBuildHeight(), level.getHeight());
        this.level = level;
    }

    /** Forgets the cached chunk: the pilot calls it every tick, so a chunk the server sent again is read fresh. */
    public void reset() {
        last = null;
        lastKey = Long.MIN_VALUE;
    }

    private LevelChunk chunk(int x, int z) {
        int cx = x >> 4, cz = z >> 4;                                     // structural: 16 blocks a chunk
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        if (key != lastKey || last == null) {                              // a chunk not loaded yet is asked again
            last = (LevelChunk) level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            lastKey = key;
        }
        return last;
    }

    @Override
    public BlockState get0(int x, int y, int z) {
        if (y < minBuildHeight() || y >= minBuildHeight() + height()) {
            return Blocks.AIR.defaultBlockState();
        }
        LevelChunk c = chunk(x, z);
        return c == null ? Blocks.AIR.defaultBlockState() : c.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
    }

    @Override
    public boolean isLoaded(int x, int z) { return chunk(x, z) != null; }

    @Override
    public boolean worldContainsLoadedChunk(int x, int z) { return chunk(x, z) != null; }
}
