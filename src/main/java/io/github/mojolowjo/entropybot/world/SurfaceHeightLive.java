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
