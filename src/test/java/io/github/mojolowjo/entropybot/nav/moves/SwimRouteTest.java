package io.github.mojolowjo.entropybot.nav.moves;

import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.BodySnapshot;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.search.AStarPathFinder;
import io.github.mojolowjo.entropybot.nav.search.Favoring;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalBlock;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.world.GridView;
import io.github.mojolowjo.entropybot.sim.SimSearch;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Steering S3: with the fork's pathUnderWater on (LiveRoute turns it on while steering drives water), a route goes up a
 * 1-wide water column and out onto the bank; with it off (the fork's default), there is no way out. No new move was
 * needed: the fork's pillar swims up water under water and its ascend steps out from the top cell.
 */
class SwimRouteTest {
    @BeforeAll static void boot() { SimSearch.boot(); }

    /** Stone floor at y 63; a stone tower x 1..5, z -2..2, top y 68; a 1-wide water column at x 0 z 0, y 64..68, walled. */
    static GridView column() {
        BlockState STONE = Blocks.STONE.defaultBlockState(), WATER = Blocks.WATER.defaultBlockState();   // after boot
        GridView g = GridView.flat();
        g.fill(1, 64, -2, 5, 68, 2, STONE);
        g.fill(-1, 64, -1, 0, 68, 1, STONE);
        g.fill(0, 64, 0, 0, 68, 0, WATER);
        return g;
    }

    static SearchContext context(GridView g, boolean underWater) {
        NavConfig d = NavConfig.defaults();
        NavConfig cfg = d.withMoves(MovesMatchForkTest.moves(d.moves(), true, false, false, underWater, 0));
        return new SearchContext(cfg, new BlockRules(cfg), g, BodySnapshot.bare(), null);
    }

    @Test void aRouteSwimsUpTheColumnAndClimbsOut() {
        SearchContext c = context(column(), true);
        BetterBlockPos start = new BetterBlockPos(0, 64, 0);
        Path p = new AStarPathFinder(start, 0, 64, 0, new GoalBlock(3, 69, 0), Favoring.none(), c).calculate(600_000, 600_000).getPath().orElse(null);
        assertNotNull(p, "no route");
        String moves = p.steps().stream().map(s -> s.move().name()).collect(Collectors.joining(" "));
        assertEquals(new BetterBlockPos(3, 69, 0), p.steps().get(p.steps().size() - 1).dest(), moves);
        // the fork's own moves do it once water under water is passable: swim up (pillar), then step out (ascend)
        assertTrue(moves.startsWith("PILLAR PILLAR PILLAR PILLAR ASCEND_EAST"), moves);
    }

    @Test void withoutOurWaterPathingTheColumnHasNoWayOut() {
        SearchContext c = context(column(), false);
        BetterBlockPos start = new BetterBlockPos(0, 64, 0);
        var r = new AStarPathFinder(start, 0, 64, 0, new GoalBlock(3, 69, 0), Favoring.none(), c).calculate(600_000, 600_000);
        assertFalse(r.getPath().map(p -> p.steps().get(p.steps().size() - 1).dest().equals(new BetterBlockPos(3, 69, 0))).orElse(false));
    }
}
