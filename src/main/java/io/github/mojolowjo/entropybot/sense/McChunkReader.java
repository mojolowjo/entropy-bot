package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.poi.PoiScanner;
import io.github.mojolowjo.entropybot.strip.OreSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 0.28.0: the game side of a chunk read: a client {@link LevelChunk}'s sections as a {@link SummaryBuilder.ChunkSource}
 * (a section is walked only when its palette may hold a wanted state: {@code maybeHas}), and the block-state classifier
 * (cached per state): an ore is a block id ending "_ore" (or ancient debris, {@link OreSpec#oreId}) or in the
 * {@code c:ores} tag, its family {@link OreSpec#family}; a POI kind from {@link PoiScanner#kinds()}; a lava source.
 *
 * <p>Loader notes (docs/PLANNING.md): reads are vanilla only ({@code LevelChunk.getSections}, {@code LevelChunkSection
 * .hasOnlyAir/maybeHas/getBlockState}, {@code ChunkAccess.getHeight(Heightmap.Types.WORLD_SURFACE)},
 * {@code BuiltInRegistries.BLOCK}, {@code BlockState.is(TagKey)}). The one loader type is the ore tag: NeoForge
 * {@code net.neoforged.neoforge.common.Tags.Blocks.ORES} (= {@code c:ores}); Fabric: {@code ConventionalBlockTags.ORES}
 * (the same {@code c:ores} tag). Client (game) thread only.
 */
public final class McChunkReader {
    private final Map<BlockState, SummaryBuilder.Cls> cache = new IdentityHashMap<>();
    private final SummaryBuilder.Classifier<BlockState> classifier = this::cls;

    /** What a block state is to the pass (cached; never null). */
    public SummaryBuilder.Cls cls(BlockState st) {
        if (st == null) return SummaryBuilder.NONE;
        SummaryBuilder.Cls c = cache.get(st);
        if (c == null) {
            c = compute(st);
            if (cache.size() > 100_000) cache.clear();
            cache.put(st, c);
        }
        return c;
    }

    private static SummaryBuilder.Cls compute(BlockState st) {
        if (st.isAir()) return SummaryBuilder.NONE;
        Block b = st.getBlock();
        String id = BuiltInRegistries.BLOCK.getKey(b).toString();
        String fam = OreSpec.oreId(id) || st.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) ? OreSpec.family(id) : null;
        String poi = PoiScanner.kinds().get(b);
        boolean lava = b == Blocks.LAVA && st.getFluidState().isSource();
        if (ChunkSummary.LAVA_LAKE_KIND.equals(poi)) poi = null;          // lava: counted per section instead
        if (fam == null && poi == null && !lava) return SummaryBuilder.NONE;
        return new SummaryBuilder.Cls(fam, id, poi, lava);
    }

    /** Reads the whole chunk into a summary. Throws on any failure (the caller leaves the chunk unknown). */
    public ChunkSummary read(String dim, LevelChunk chunk, long now) {
        LevelChunkSection[] secs = chunk.getSections();
        int minSection = chunk.getMinSection();
        SummaryBuilder.ChunkSource<BlockState> src = new SummaryBuilder.ChunkSource<>() {
            @Override public int sections() { return secs.length; }

            @Override public int minSection() { return minSection; }

            @Override public boolean skip(int i) {
                LevelChunkSection s = secs[i];
                return s == null || s.hasOnlyAir() || !s.maybeHas(st -> !cls(st).none());
            }

            @Override public BlockState state(int i, int lx, int ly, int lz) { return secs[i].getBlockState(lx, ly, lz); }

            @Override public int top(int lx, int lz) { return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz); }
        };
        return SummaryBuilder.build(dim, chunk.getPos().x, chunk.getPos().z, src, classifier, now);
    }

    /** The column's top now (after a change): the live heightmap. */
    public static int top(LevelChunk chunk, int x, int z) { return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15); }
}
