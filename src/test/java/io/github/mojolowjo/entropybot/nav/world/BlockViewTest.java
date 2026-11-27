package io.github.mojolowjo.entropybot.nav.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.mojolowjo.entropybot.sim.SimSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** nav step 3: the view's reads, its BlockGetter face, and the edges (build height, unloaded columns). */
class BlockViewTest {
    @BeforeAll
    static void boot() { SimSearch.boot(); }

    @Test
    void readsCellsFloorAndAirAndTheEdges() {
        GridView v = GridView.flat();
        v.set(1, 64, 1, Blocks.WATER.defaultBlockState());
        assertEquals(Blocks.STONE.defaultBlockState(), v.get0(0, 63, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), v.get0(0, 64, 0));
        assertEquals(Blocks.WATER.defaultBlockState(), v.get0(new BlockPos(1, 64, 1)));
        assertEquals(Blocks.WATER.defaultBlockState(), v.access.getBlockState(new BlockPos(1, 64, 1)), "the BlockGetter face reads the same cell");
        assertEquals(Blocks.AIR.defaultBlockState(), v.get0(0, -65, 0), "below the build height is air");
        assertEquals(Blocks.AIR.defaultBlockState(), v.get0(300, 63, 0), "an unloaded column reads air");
        assertFalse(v.isLoaded(300, 0));
        assertEquals(-64, v.access.getMinBuildHeight());
        assertEquals(384, v.access.getHeight());
    }
}
