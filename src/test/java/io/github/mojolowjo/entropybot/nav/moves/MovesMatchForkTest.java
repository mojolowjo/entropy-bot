package io.github.mojolowjo.entropybot.nav.moves;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import baritone.pathing.movement.Moves;
import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.cost.MutableMoveResult;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.BodySnapshot;
import io.github.mojolowjo.entropybot.nav.rules.CostAdvisor;
import io.github.mojolowjo.entropybot.nav.rules.ForkMirror;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import io.github.mojolowjo.entropybot.nav.world.GridView;
import io.github.mojolowjo.entropybot.sim.SimSearch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * nav step 5 check (to-do/nav-steps-1-6.md): every entry of our {@link MoveCatalog} gives the same destination and
 * the same cost, to the last bit, as the fork jar's {@code Moves} from the same cell. The cells: every cell of a
 * 10x10x10 box in 60 seeded random worlds of awkward blocks (water still and flowing, lava, gravel and sand, ladders,
 * vines, soul sand, magma, slabs, stairs, snow layers, lily pads, carpets, doors, glass, ice, leaves, farmland), under
 * four setups (Baritone's defaults; the fork's water and lava switches; parkour and diagonals on, no sprint, a water
 * bucket; a cost advisor that prices breaks and places).
 */
class MovesMatchForkTest {

    @BeforeAll
    static void boot() { SimSearch.boot(); }

    static BlockState[] palette() {
        return new BlockState[]{
                Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.SAND.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 2),
                Blocks.LAVA.defaultBlockState(), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 4),
                Blocks.LADDER.defaultBlockState(), Blocks.VINE.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(),
                Blocks.STONE_SLAB.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
                Blocks.OAK_STAIRS.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST),
                Blocks.SNOW.defaultBlockState(), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3),
                Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 6), Blocks.LILY_PAD.defaultBlockState(),
                Blocks.WHITE_CARPET.defaultBlockState(), Blocks.OAK_DOOR.defaultBlockState(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true),
                Blocks.IRON_DOOR.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.ICE.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.FARMLAND.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(), Blocks.OAK_FENCE_GATE.defaultBlockState(),
                Blocks.COBWEB.defaultBlockState(), Blocks.TORCH.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(), Blocks.BEDROCK.defaultBlockState()};
    }

    static GridView world(long seed) {
        Random r = new Random(seed);
        BlockState[] p = palette();
        GridView g = GridView.flat();
        int airBias = r.nextInt(4); // some worlds open, some packed
        for (int x = -1; x <= 10; x++)
            for (int y = 60; y <= 72; y++)
                for (int z = -1; z <= 10; z++) {
                    BlockState s = r.nextInt(4) < airBias ? Blocks.AIR.defaultBlockState() : p[r.nextInt(p.length)];
                    g.set(x, y, z, s);
                }
        return g;
    }

    static NavConfig.Moves moves(NavConfig.Moves m, boolean sprint, boolean parkour, boolean diagonals, boolean underWater, double lavaCell) {
        return new NavConfig.Moves(sprint, parkour, parkour, parkour, diagonals, diagonals, m.allowDownward(), parkour /* jump at build limit: the fork reads its world otherwise */,
                m.allowVines(), m.allowWalkOnBottomSlab(), m.allowWalkOnMagmaBlocks(), m.assumeWalkOnWater(), m.assumeWalkOnLava(), m.allowWaterBucketFall(),
                m.maxFallHeightNoWater(), m.maxFallHeightBucket(), m.jumpPenalty(), m.walkOnWaterOnePenalty(), underWater, lavaCell, m.strictLiquidCheck(),
                m.blocksToAvoid());
    }

    record Setup(String name, NavConfig cfg, BodySnapshot body, boolean advisor) {}

    static List<Setup> setups() {
        NavConfig d = NavConfig.defaults();
        BodySnapshot tools = BodySnapshot.holding(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.IRON_AXE));
        BodySnapshot bucket = new BodySnapshot(tools.hotbar(), 0, -1, -1, 0, 1f, 20, true, true, false);
        BodySnapshot frost = new BodySnapshot(tools.hotbar(), 0, -1, -1, 2, 1f, 20, false, true, false);
        NavConfig.Breaking b = d.breaking();
        NavConfig nextTo = d.withMoves(moves(d.moves(), true, false, false, true, 6.0)).withBreaking(new NavConfig.Breaking(b.allowBreak(), b.allowBreakAnyway(),
                b.blockBreakAdditionalPenalty(), b.blocksToDisallowBreaking(), b.blocksToAvoidBreaking(), b.avoidBreakingMultiplier(), true, true,
                b.avoidUpdatingFallingBlocks(), b.considerPotionEffects(), b.useSwordToMine(), b.itemSaver(), b.itemSaverThreshold(), b.autoTool(),
                b.assumeExternalAutoTool(), b.preferSilkTouch()));
        return List.of(new Setup("defaults", d, tools, false),
                new Setup("fork water/lava switches, frost walker", nextTo, frost, false),
                new Setup("parkour + diagonals, no sprint, bucket", d.withMoves(moves(d.moves(), false, true, true, false, 0)), bucket, false),
                new Setup("parkour + diagonals + sprint, advisor", d.withMoves(moves(d.moves(), true, true, true, false, 0)), tools, true));
    }

    /** Ours: forbids breaking under a falling block (judged in the view it is shown), doubles other breaks, places for 7. */
    static final class OurAdvisor implements CostAdvisor {
        @Override public double breakMultiplier(int x, int y, int z, BlockState s, BlockView v) {
            return v.get0(x, y + 1, z).getBlock() instanceof FallingBlock ? ActionCosts.COST_INF : ((x + z) & 1) == 0 ? 2 : Double.NaN;
        }
        @Override public double placeCost(int x, int y, int z, BlockState s, BlockView v, double d) { return ((x ^ z) & 1) == 0 ? 7 : Double.NaN; }
    }

    /** The same advisor in the fork's shape. */
    static final class ForkAdvisor implements baritone.pathing.movement.CostAdvisor {
        @Override public Object begin() { return null; }
        @Override public double breakMultiplier(Object t, int x, int y, int z, BlockState s, baritone.utils.BlockStateInterface v) {
            return v.get0(x, y + 1, z).getBlock() instanceof FallingBlock ? ActionCosts.COST_INF : ((x + z) & 1) == 0 ? 2 : Double.NaN;
        }
        @Override public double placeCost(Object t, int x, int y, int z, BlockState s, baritone.utils.BlockStateInterface v, double d) {
            return ((x ^ z) & 1) == 0 ? 7 : Double.NaN;
        }
    }

    @Test
    void theCatalogIsTheForksMovesInOrder() {
        assertArrayEquals(Arrays.stream(Moves.values()).map(Enum::name).toArray(), Arrays.stream(MoveCatalog.values()).map(Enum::name).toArray());
        for (int i = 0; i < Moves.values().length; i++) {
            Moves f = Moves.values()[i];
            MoveCatalog o = MoveCatalog.values()[i];
            assertArrayEquals(new Object[]{f.xOffset, f.yOffset, f.zOffset, f.dynamicXZ, f.dynamicY},
                    new Object[]{o.xOffset, o.yOffset, o.zOffset, o.dynamicXZ, o.dynamicY}, o.name());
        }
    }

    @Test
    void everyMovePricesAsTheFork() throws Exception {
        List<String> diffs = new ArrayList<>();
        long compared = 0, finite = 0;
        MoveCatalog[] ours = MoveCatalog.values();
        Moves[] forks = Moves.values();
        for (Setup setup : setups()) {
            Map<String, Object> old = ForkMirror.apply(setup.cfg());
            try {
                BlockRules rules = new BlockRules(setup.cfg());
                for (long seed = 1; seed <= 60; seed++) {
                    GridView g = world(seed * 7919 + setup.name().length());
                    SearchContext oc = new SearchContext(setup.cfg(), rules, g, setup.body(), setup.advisor() ? new OurAdvisor() : null);
                    ForkMirror.Context fc = ForkMirror.context(oc, setup.advisor() ? new ForkAdvisor() : null);
                    MutableMoveResult or = new MutableMoveResult();
                    baritone.utils.pathing.MutableMoveResult fr = new baritone.utils.pathing.MutableMoveResult();
                    for (int x = 0; x < 10; x++)
                        for (int y = 61; y < 71; y++)
                            for (int z = 0; z < 10; z++)
                                for (int i = 0; i < ours.length; i++) {
                                    or.reset();
                                    fr.reset();
                                    ours[i].apply(oc, x, y, z, or);
                                    forks[i].apply(fc, x, y, z, fr);
                                    compared++;
                                    if (or.cost < ActionCosts.COST_INF) finite++;
                                    if (or.x != fr.x || or.y != fr.y || or.z != fr.z || Double.compare(or.cost, fr.cost) != 0) {
                                        diffs.add(setup.name() + " seed " + seed + " " + ours[i] + " from " + x + " " + y + " " + z + ": fork " + fr.x + " "
                                                + fr.y + " " + fr.z + " cost " + fr.cost + ", ours " + or.x + " " + or.y + " " + or.z + " cost " + or.cost);
                                    }
                                }
                    if (oc.advisorFailure() != null) diffs.add("advisor failed: " + oc.advisorFailure());
                }
            } finally {
                ForkMirror.restore(old);
            }
        }
        assertTrue(finite > 50_000, "only " + finite + " finite prices of " + compared + ": the worlds test too little");
        assertTrue(diffs.isEmpty(), diffs.size() + " of " + compared + " moves differ from the fork (" + finite + " finite), first ones:\n"
                + String.join("\n", diffs.subList(0, Math.min(25, diffs.size()))));
    }
}
