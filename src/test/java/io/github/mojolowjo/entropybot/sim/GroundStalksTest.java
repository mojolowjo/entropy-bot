package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import io.github.mojolowjo.entropybot.world.SurfaceHeightLive;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-hold-sight send-back item 3: the hole rule (SurfaceHeight.pitDepth, walled in) over the ground reader's block test
 * (SurfaceHeightLive.notGround, the same one SurfaceHeightLive.ground walks the column with). A bamboo grove, a fence
 * line, a wall, cactus on the real surface stand on the ground and are no wall of a hole; stone the same height is.
 * The game's own block states (the registries booted as the simulator does).
 */
class GroundStalksTest {
    private static final int GROUND = 69;           // the first open cell over the grass
    private static final int MIN_Y = -64;

    @BeforeAll
    static void boot() { SimSearch.boot(); }

    /** The 8 columns round the feet carry {@code what} from the ground up {@code tall} blocks; the rest is flat grass. */
    private static IntBinaryOperator flanked(BlockState what, int tall) {
        return (x, z) -> {
            boolean post = Math.max(Math.abs(x), Math.abs(z)) == 1;
            int top = post ? GROUND + tall : GROUND;                   // MOTION_BLOCKING: the first cell over the top blocker
            return SurfaceHeight.noLeaves(true, top, MIN_Y, y -> SurfaceHeightLive.notGround(post && y >= GROUND ? what : Blocks.GRASS_BLOCK.defaultBlockState()));
        };
    }

    @Test
    void stoneAllRound_isAHole() {
        assertEquals(6, SurfaceHeight.pitDepth(new int[]{0, GROUND, 0}, flanked(Blocks.STONE.defaultBlockState(), 6)), "the rule itself: walled in by stone");
    }

    @Test
    void stalksAndPostsAllRound_areTheSurface() {
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, GROUND, 0}, flanked(Blocks.BAMBOO.defaultBlockState(), 12)), "a bamboo grove");
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, GROUND, 0}, flanked(Blocks.OAK_FENCE.defaultBlockState(), 2)), "a fence line (a village alley)");
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, GROUND, 0}, flanked(Blocks.COBBLESTONE_WALL.defaultBlockState(), 2)), "a wall");
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, GROUND, 0}, flanked(Blocks.CACTUS.defaultBlockState(), 3)), "cactus");
        assertEquals(0, SurfaceHeight.pitDepth(new int[]{0, GROUND, 0}, flanked(Blocks.MUSHROOM_STEM.defaultBlockState(), 5)), "a huge mushroom's stem");
        // trunks are walked past by the game's LOGS tag, which no JUnit binds: not tested here
    }

    @Test
    void groundBlocks_stayGround() {
        for (BlockState st : new BlockState[]{Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.DIRT_PATH.defaultBlockState(), Blocks.FARMLAND.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()})
            assertFalse(SurfaceHeightLive.notGround(st), st.toString());
    }
}
