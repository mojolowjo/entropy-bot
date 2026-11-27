package io.github.mojolowjo.entropybot.nav.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mojolowjo.entropybot.sim.SimSearch;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** nav step 3: the cached byte equals a fresh compute for every block state, and the fluid facts are right. */
class FluidFlagsTest {
    @BeforeAll
    static void boot() { SimSearch.boot(); }

    @Test
    void cacheMatchesComputeForEveryState() {
        int n = 0;
        for (BlockState s : Block.BLOCK_STATE_REGISTRY) {
            assertEquals(FluidFlags.compute(s), FluidFlags.of(s), s.toString());
            assertEquals(FluidFlags.compute(s), FluidFlags.of(s), "second read, from the cache: " + s);
            n++;
        }
        assertTrue(n > 20000, "the registry holds every block state: " + n);
    }

    @Test
    void factsOfWaterLavaAndWaterlogged() {
        BlockState water = Blocks.WATER.defaultBlockState();
        assertTrue(FluidFlags.isWater(water));
        assertFalse(FluidFlags.possiblyFlowing(water), "a source block is level 8");
        assertTrue(FluidFlags.possiblyFlowing(water.setValue(LiquidBlock.LEVEL, 3)));
        assertTrue(FluidFlags.isLava(Blocks.LAVA.defaultBlockState()));
        assertTrue(FluidFlags.isEmpty(Blocks.STONE.defaultBlockState()));
        BlockState wetSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true);
        assertTrue(FluidFlags.waterlogged(wetSlab));
        assertTrue(FluidFlags.isWater(wetSlab));
        assertFalse(FluidFlags.waterlogged(Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, false)));
    }
}
