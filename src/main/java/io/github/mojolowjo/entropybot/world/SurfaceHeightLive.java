package io.github.mojolowjo.entropybot.world;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 0.39.z76 surface 2: the ONE place this mod reads the game's heightmaps (Z76HeightmapReadsTest fails on a read anywhere
 * else). The client keeps only MOTION_BLOCKING and WORLD_SURFACE (the two the server sends with a chunk);
 * MOTION_BLOCKING_NO_LEAVES is never filled there and is not offered here. {@link #noLeaves} and {@link #top} answer rule
 * 15's way ({@link SurfaceHeight#NOT_KNOWN} for a column not loaded or holding no data, never the world's bottom); the raw
 * {@link #worldSurface} / {@link #motionBlocking} reads are the heightmap's own value, as their callers (the map and view
 * exports, the chunk pass's shapes) used it before. Client thread. Loader notes: vanilla {@code LevelReader#getHeight},
 * {@code ChunkAccess#getHeight}, {@code Heightmap.Types}, {@code BlockTags.LEAVES}, {@code BlockState#blocksMotion};
 * Fabric: the same calls under Yarn names (the pure rule is {@link SurfaceHeight}).
 */
public final class SurfaceHeightLive {
    private SurfaceHeightLive() {}

    /** The leaf-free surface over x z ({@link SurfaceHeight#noLeaves} on the client's MOTION_BLOCKING); NOT_KNOWN: not known. */
    public static int noLeaves(LevelReader level, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, 0, z);
        return SurfaceHeight.noLeaves(level.hasChunk(x >> 4, z >> 4), level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), level.getMinBuildHeight(), y -> {
            m.setY(y);
            var st = level.getBlockState(m);
            return st.is(BlockTags.LEAVES) || !st.blocksMotion();
        });
    }

    /**
     * job-hold-sight: the ground over x z under any tree - {@link #noLeaves} with the logs walked past too (the game's
     * {@code BlockTags.LOGS}): a trunk beside the feet is no wall of a hole (SurfaceHeight.pitDepth reads this). NOT_KNOWN:
     * not known (rule 15). Send-back item 3: and past every block {@link #notGround} names (a bamboo grove's stalks, a
     * fence, a cactus are no wall of a hole either).
     */
    public static int ground(LevelReader level, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, 0, z);
        return SurfaceHeight.noLeaves(level.hasChunk(x >> 4, z >> 4), level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), level.getMinBuildHeight(), y -> {
            m.setY(y);
            return notGround(level.getBlockState(m));
        });
    }

    /**
     * job-hold-sight send-back item 3 (the reviewer: a bamboo jungle or a village alley read "under ground" on the real
     * surface by the hole rule): a block the ground reader walks past - leaves, logs (the game's tags), no motion
     * blocked, and the stalks, posts and plant blocks that stand on the ground rather than being it: bamboo, cactus,
     * chorus, huge mushroom blocks (stems and caps, a tree's kind), fences, fence gates, walls, panes and bars. Read by the
     * block's class as well as its tag (Loader notes: vanilla {@code FenceBlock}, {@code FenceGateBlock}, {@code WallBlock},
     * {@code IronBarsBlock}, {@code BambooStalkBlock}, {@code CactusBlock}, {@code ChorusPlantBlock}, {@code
     * HugeMushroomBlock}, {@code BlockTags.FENCES / FENCE_GATES / WALLS}; a mod's fence or wall extends the same classes or
     * joins the same tags; the classes also answer in a JUnit with no tags bound).
     */
    public static boolean notGround(net.minecraft.world.level.block.state.BlockState st) {
        if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || !st.blocksMotion()) return true;
        if (st.is(BlockTags.FENCES) || st.is(BlockTags.FENCE_GATES) || st.is(BlockTags.WALLS)) return true;
        var b = st.getBlock();
        return b instanceof net.minecraft.world.level.block.FenceBlock || b instanceof net.minecraft.world.level.block.FenceGateBlock
                || b instanceof net.minecraft.world.level.block.WallBlock || b instanceof net.minecraft.world.level.block.IronBarsBlock
                || b instanceof net.minecraft.world.level.block.BambooStalkBlock || b instanceof net.minecraft.world.level.block.CactusBlock
                || b instanceof net.minecraft.world.level.block.ChorusPlantBlock || b instanceof net.minecraft.world.level.block.HugeMushroomBlock;
    }

    /** The first cell over the column's top blocking block (leaves count), NOT_KNOWN when not loaded or no data (rule 15). */
    public static int top(LevelReader level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return SurfaceHeight.NOT_KNOWN;
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return h <= level.getMinBuildHeight() ? SurfaceHeight.NOT_KNOWN : h;
    }

    /** The raw WORLD_SURFACE height at x z (the heightmap's own value). */
    public static int worldSurface(LevelReader level, int x, int z) { return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z); }

    /** The raw WORLD_SURFACE height at the chunk-local lx lz. */
    public static int worldSurface(ChunkAccess chunk, int lx, int lz) { return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz); }

    /** The raw MOTION_BLOCKING height at x z (the heightmap's own value). */
    public static int motionBlocking(LevelReader level, int x, int z) { return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z); }
}
