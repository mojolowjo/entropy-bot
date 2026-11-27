package io.github.mojolowjo.entropybot.nav.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import io.github.mojolowjo.entropybot.nav.world.BrokenCellView;
import io.github.mojolowjo.entropybot.nav.world.GridView;
import io.github.mojolowjo.entropybot.sim.SimSearch;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** nav step 4: SearchContext prices places and breaks as the fork's CalculationContext, and an advisor's throw is named. */
class SearchContextTest {

    @BeforeAll
    static void boot() { SimSearch.boot(); }

    @Test
    void placeAndBreakPricesEqualTheFork() throws Exception {
        NavConfig cfg = NavConfig.defaults();
        Map<String, Object> old = ForkMirror.apply(cfg);
        try {
            GridView g = GridView.flat();
            SearchContext ours = new SearchContext(cfg, g, BodySnapshot.bare());
            ForkMirror.Context fork = ForkMirror.context(ours, null);
            for (BlockState s : new BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                    Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 4), Blocks.STONE.defaultBlockState(), Blocks.KELP.defaultBlockState()}) {
                assertEquals(fork.costOfPlacingAt(0, 64, 0, s), ours.costOfPlacingAt(0, 64, 0, s), "place in " + s);
                assertEquals(fork.breakCostMultiplierAt(0, 64, 0, s), ours.breakCostMultiplierAt(0, 64, 0, s), "break " + s);
                assertEquals(fork.costOfPlacingAt(40_000_000, 64, 0, s), ours.costOfPlacingAt(40_000_000, 64, 0, s), "outside the border");
            }
            assertEquals(fork.placeBucketCost(), ours.placeBucketCost());
            assertEquals(fork.waterWalkSpeed, ours.waterWalkSpeed);
            assertEquals(ActionCosts.WALK_ONE_BLOCK_COST, ours.waterWalkSpeed, "no depth strider: Baritone's 1.0 multiplier");
        } finally {
            ForkMirror.restore(old);
        }
    }

    @Test
    void noThrowawayNoPlace() {
        BodySnapshot b = BodySnapshot.bare();
        BodySnapshot none = new BodySnapshot(b.hotbar(), 0, -1, -1, 0, 1f, 20, false, false, false);
        SearchContext c = new SearchContext(NavConfig.defaults(), GridView.flat(), none);
        assertEquals(ActionCosts.COST_INF, c.costOfPlacingAt(0, 64, 0, Blocks.AIR.defaultBlockState()));
    }

    @Test
    void anAdvisorThatThrowsForbidsTheCellAndSaysWhy() {
        CostAdvisor broken = new CostAdvisor() {
            @Override public double breakMultiplier(int x, int y, int z, BlockState s, BlockView v) { throw new IllegalStateException("policy not loaded"); }
            @Override public double placeCost(int x, int y, int z, BlockState s, BlockView v, double d) { return 3; }
        };
        NavConfig cfg = NavConfig.defaults();
        SearchContext c = new SearchContext(cfg, new BlockRules(cfg), GridView.flat(), BodySnapshot.bare(), broken);
        assertEquals(3, c.costOfPlacingAt(0, 64, 0, Blocks.AIR.defaultBlockState()));
        assertEquals(ActionCosts.COST_INF, c.breakCostMultiplierAt(1, 63, 2, Blocks.STONE.defaultBlockState()));
        assertTrue(c.advisorFailure().startsWith("breakMultiplier at 1 63 2: java.lang.IllegalStateException: policy not loaded"), c.advisorFailure());
    }

    @Test
    void rulesMustMatchTheConfig() {
        NavConfig a = NavConfig.defaults();
        NavConfig b = NavConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> new SearchContext(a, new BlockRules(b), GridView.flat(), BodySnapshot.bare(), null));
    }

    @Test
    void brokenCellViewShowsAirAtOneCell() {
        GridView g = GridView.flat();
        BrokenCellView v = new BrokenCellView(g).at(0, 63, 0);
        assertEquals(Blocks.AIR.defaultBlockState(), v.get0(0, 63, 0));
        assertEquals(Blocks.STONE.defaultBlockState(), v.get0(1, 63, 0));
    }
}
