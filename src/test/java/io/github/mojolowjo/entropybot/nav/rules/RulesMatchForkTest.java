package io.github.mojolowjo.entropybot.nav.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import baritone.pathing.movement.MovementHelper;
import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.world.GridView;
import io.github.mojolowjo.entropybot.sim.SimSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * nav step 4 check (to-do/nav-steps-1-6.md): on the same cells, every planning answer of our {@link BlockRules} and
 * {@link SearchContext} equals the fork jar's {@code MovementHelper} / {@code CalculationContext}. The cells: every
 * block in the registry (up to 12 of its states each) in eight neighbourhoods (open ground, water above, water and
 * lava beside, an unsupported falling block beside, stacked on itself, a low ceiling, next to a deep snow pile, a
 * cached-only column), read at the cell, above and below it, under three configs (Baritone's defaults; the fork's
 * water, lava and under-water switches on; walk-on-water, walk-on-lava, strict liquids and the block lists changed).
 */
class RulesMatchForkTest {

    private static final int X = 0, Y = 64, Z = 0, SKETCH_X = 200;

    @BeforeAll
    static void boot() { SimSearch.boot(); }

    static List<BlockState> sampleStates() {
        List<BlockState> out = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            List<BlockState> all = b.getStateDefinition().getPossibleStates();
            int step = Math.max(1, all.size() / 12);
            for (int i = 0; i < all.size(); i += step) out.add(all.get(i));
            out.add(b.defaultBlockState());
        }
        return out;
    }

    /** The neighbourhoods: each puts the sampled state at the cell (cx Y Z) and its surroundings. */
    static List<BiConsumer<GridView, BlockState>> setups() {
        BlockState WATER = Blocks.WATER.defaultBlockState();
        BlockState LAVA = Blocks.LAVA.defaultBlockState();
        BlockState FLOWING_WATER = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
        return List.of(
                (g, s) -> g.set(X, Y, Z, s),
                (g, s) -> g.set(X, Y, Z, s).set(X, Y + 1, Z, WATER),
                (g, s) -> g.set(X, Y, Z, s).set(X + 1, Y, Z, WATER).set(X - 1, Y, Z, LAVA).set(X, Y, Z + 1, FLOWING_WATER),
                (g, s) -> g.set(X, Y, Z, s).set(X + 1, Y, Z, Blocks.SAND.defaultBlockState()).set(X + 1, Y - 1, Z, Blocks.AIR.defaultBlockState())
                        .set(X, Y + 1, Z, Blocks.GRAVEL.defaultBlockState()),
                (g, s) -> g.set(X, Y, Z, s).set(X, Y + 1, Z, s).set(X, Y - 1, Z, s),
                (g, s) -> g.set(X, Y, Z, s).set(X, Y + 2, Z, Blocks.STONE.defaultBlockState()),
                (g, s) -> g.set(X, Y, Z, s).set(X + 1, Y - 1, Z, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 7))
                        .set(X, Y - 1, Z, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 8)),
                (g, s) -> g.set(SKETCH_X, Y, Z, s));
    }

    static List<NavConfig> configs() {
        NavConfig d = NavConfig.defaults();
        NavConfig.Moves m = d.moves();
        NavConfig.Breaking b = d.breaking();
        NavConfig forkSwitches = d.withMoves(new NavConfig.Moves(m.allowSprint(), m.allowParkour(), m.allowParkourPlace(), m.allowParkourAscend(),
                        m.allowDiagonalDescend(), m.allowDiagonalAscend(), m.allowDownward(), m.allowJumpAtBuildLimit(), m.allowVines(),
                        m.allowWalkOnBottomSlab(), m.allowWalkOnMagmaBlocks(), m.assumeWalkOnWater(), m.assumeWalkOnLava(), m.allowWaterBucketFall(),
                        m.maxFallHeightNoWater(), m.maxFallHeightBucket(), m.jumpPenalty(), m.walkOnWaterOnePenalty(),
                        true, 5.0, m.strictLiquidCheck(), m.blocksToAvoid()))
                .withBreaking(new NavConfig.Breaking(b.allowBreak(), b.allowBreakAnyway(), b.blockBreakAdditionalPenalty(), b.blocksToDisallowBreaking(),
                        b.blocksToAvoidBreaking(), b.avoidBreakingMultiplier(), true, true, b.avoidUpdatingFallingBlocks(), b.considerPotionEffects(),
                        b.useSwordToMine(), b.itemSaver(), b.itemSaverThreshold(), b.autoTool(), b.assumeExternalAutoTool(), b.preferSilkTouch()));
        NavConfig odd = d.withMoves(new NavConfig.Moves(false, true, true, false, true, true, false, true, true,
                        false, true, true, true, false, 5, 30, 1.0, 1.0, false, 0.0, true, List.of(Blocks.COBBLESTONE, Blocks.TRIPWIRE)))
                .withBreaking(new NavConfig.Breaking(false, List.of(Blocks.DIRT), 3.0, List.of(Blocks.GRAVEL), b.blocksToAvoidBreaking(), 4.0,
                        false, false, false, b.considerPotionEffects(), b.useSwordToMine(), b.itemSaver(), b.itemSaverThreshold(), b.autoTool(),
                        b.assumeExternalAutoTool(), b.preferSilkTouch()));
        return List.of(d, forkSwitches, odd);
    }

    @Test
    void everyRuleAnswersAsTheFork() throws Exception {
        List<String> diffs = new ArrayList<>();
        long compared = 0;
        BodySnapshot body = BodySnapshot.holding(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.IRON_AXE));
        body = new BodySnapshot(body.hotbar(), 0, -1, -1, 2, 1f, 20, false, true, false); // frost walker II
        List<BlockState> states = sampleStates();
        List<NavConfig> configs = configs();
        List<BiConsumer<GridView, BlockState>> setups = setups();
        for (NavConfig cfg : configs) {
            Map<String, Object> old = ForkMirror.apply(cfg);
            try {
                BlockRules rules = new BlockRules(cfg);
                for (BiConsumer<GridView, BlockState> setup : setups) {
                    for (BlockState s : states) {
                        GridView g = GridView.flat().liveWithin(100);
                        setup.accept(g, s);
                        SearchContext ours = new SearchContext(cfg, rules, g, body, null);
                        ForkMirror.Context fork = ForkMirror.context(ours, null);
                        ForkMirror.Bsi fb = (ForkMirror.Bsi) fork.bsi;
                        int cx = g.get0(SKETCH_X, Y, Z) == s && s != Blocks.AIR.defaultBlockState() ? SKETCH_X : X;
                        for (int dy = -1; dy <= 1; dy++) {
                            int x = cx, y = Y + dy, z = Z;
                            BlockState at = g.get0(x, y, z);
                            String where = BuiltInRegistries.BLOCK.getKey(s.getBlock()) + " " + s + " cfg#" + configs.indexOf(cfg)
                                    + " setup#" + setups.indexOf(setup) + " dy " + dy + ": ";
                            compared += 22;
                            same(diffs, where + "canWalkThrough", MovementHelper.canWalkThrough(fb, x, y, z), rules.canWalkThrough(g, x, y, z));
                            same(diffs, where + "canWalkOn", MovementHelper.canWalkOn(fb, x, y, z), rules.canWalkOn(g, x, y, z));
                            same(diffs, where + "canWalkThroughBlockState", MovementHelper.canWalkThroughBlockState(at).name(), rules.canWalkThroughBlockState(at).name());
                            same(diffs, where + "canWalkOnBlockState", MovementHelper.canWalkOnBlockState(at).name(), rules.canWalkOnBlockState(at).name());
                            same(diffs, where + "fullyPassableBlockState", MovementHelper.fullyPassableBlockState(at).name(), BlockRules.fullyPassableBlockState(at).name());
                            same(diffs, where + "avoidBreaking", MovementHelper.avoidBreaking(fb, x, y, z, at), rules.avoidBreaking(g, x, y, z, at));
                            same(diffs, where + "avoidBreakingWhy", MovementHelper.avoidBreakingWhy(fb, x, y, z, at), rules.avoidBreakingWhy(g, x, y, z, at));
                            same(diffs, where + "avoidWalkingInto", MovementHelper.avoidWalkingInto(at), rules.avoidWalkingInto(at));
                            same(diffs, where + "isReplaceable", MovementHelper.isReplaceable(x, y, z, at, fb), BlockRules.isReplaceable(x, y, z, at, g));
                            same(diffs, where + "canPlaceAgainst", MovementHelper.canPlaceAgainst(fb, x, y, z), BlockRules.canPlaceAgainst(g, x, y, z));
                            same(diffs, where + "isFlowing", MovementHelper.isFlowing(x, y, z, at, fb), BlockRules.isFlowing(x, y, z, at, g));
                            same(diffs, where + "isWater", MovementHelper.isWater(at), BlockRules.isWater(at));
                            same(diffs, where + "isLava", MovementHelper.isLava(at), BlockRules.isLava(at));
                            same(diffs, where + "isLiquid", MovementHelper.isLiquid(at), BlockRules.isLiquid(at));
                            same(diffs, where + "isBlockNormalCube", MovementHelper.isBlockNormalCube(at), BlockRules.isBlockNormalCube(at));
                            same(diffs, where + "isTransparent+isClimbable+isBottomSlab",
                                    "" + MovementHelper.isTransparent(at.getBlock()) + MovementHelper.isClimbable(at.getBlock()) + MovementHelper.isBottomSlab(at),
                                    "" + BlockRules.isTransparent(at.getBlock()) + BlockRules.isClimbable(at.getBlock()) + BlockRules.isBottomSlab(at));
                            same(diffs, where + "ctx canWalkThrough", MovementHelper.canWalkThrough(fork, x, y, z), BlockRules.canWalkThrough(ours, x, y, z));
                            same(diffs, where + "ctx canWalkOn", MovementHelper.canWalkOn(fork, x, y, z), BlockRules.canWalkOn(ours, x, y, z));
                            same(diffs, where + "ctx fullyPassable", MovementHelper.fullyPassable(fork, x, y, z), BlockRules.fullyPassable(ours, x, y, z));
                            same(diffs, where + "mustBeSolidToWalkOn", MovementHelper.mustBeSolidToWalkOn(fork, x, y, z, at), BlockRules.mustBeSolidToWalkOn(ours, x, y, z, at));
                            same(diffs, where + "canUseFrostWalker", MovementHelper.canUseFrostWalker(fork, at), BlockRules.canUseFrostWalker(ours, at));
                            same(diffs, where + "getMiningDurationTicks", MovementHelper.getMiningDurationTicks(fork, x, y, z, true),
                                    BlockRules.getMiningDurationTicks(ours, x, y, z, true));
                        }
                    }
                }
            } finally {
                ForkMirror.restore(old);
            }
        }
        assertTrue(compared > 100_000, "compared only " + compared);
        assertTrue(diffs.isEmpty(), diffs.size() + " of " + compared + " answers differ from the fork, first ones:\n"
                + String.join("\n", diffs.subList(0, Math.min(25, diffs.size()))));
    }

    @Test
    void keptCellIsNeverBrokenLikeTheFork() throws Exception {
        NavConfig cfg = NavConfig.defaults();
        Map<String, Object> old = ForkMirror.apply(cfg);
        Object owner = new Object();
        try {
            baritone.pathing.movement.KeepCells.hold(owner, new BlockPos(X, Y, Z));
            GridView g = GridView.flat();
            g.set(X, Y, Z, Blocks.STONE.defaultBlockState());
            BlockRules rules = new BlockRules(cfg, (x, y, z) -> x == X && y == Y && z == Z);
            ForkMirror.Bsi fb = ForkMirror.bsi(g);
            BlockState stone = Blocks.STONE.defaultBlockState();
            assertTrue(rules.avoidBreaking(g, X, Y, Z, stone));
            assertEquals(MovementHelper.avoidBreakingWhy(fb, X, Y, Z, stone), rules.avoidBreakingWhy(g, X, Y, Z, stone));
            assertEquals(MovementHelper.avoidBreaking(fb, X + 1, Y, Z, stone), rules.avoidBreaking(g, X + 1, Y, Z, stone));
        } finally {
            baritone.pathing.movement.KeepCells.release(owner);
            ForkMirror.restore(old);
        }
    }

    @Test
    void miningUnderABrokenFallingBlockAsksTheAdvisorAsTheFork() throws Exception {
        NavConfig cfg = NavConfig.defaults();
        Map<String, Object> old = ForkMirror.apply(cfg);
        try {
            GridView g = GridView.flat();
            g.set(X, Y, Z, Blocks.DIRT.defaultBlockState()).set(X, Y + 1, Z, Blocks.SAND.defaultBlockState());
            // the advisor forbids breaking any block with a falling block above it (in the view it is shown)
            CostAdvisor oursA = new CostAdvisor() {
                @Override public double breakMultiplier(int x, int y, int z, BlockState s, io.github.mojolowjo.entropybot.nav.world.BlockView v) {
                    return v.get0(x, y + 1, z).getBlock() instanceof net.minecraft.world.level.block.FallingBlock ? 1_000_000 : 2;
                }
                @Override public double placeCost(int x, int y, int z, BlockState s, io.github.mojolowjo.entropybot.nav.world.BlockView v, double d) { return Double.NaN; }
            };
            baritone.pathing.movement.CostAdvisor forkA = new baritone.pathing.movement.CostAdvisor() {
                @Override public Object begin() { return null; }
                @Override public double breakMultiplier(Object t, int x, int y, int z, BlockState s, baritone.utils.BlockStateInterface v) {
                    return v.get0(x, y + 1, z).getBlock() instanceof net.minecraft.world.level.block.FallingBlock ? 1_000_000 : 2;
                }
                @Override public double placeCost(Object t, int x, int y, int z, BlockState s, baritone.utils.BlockStateInterface v, double d) { return Double.NaN; }
            };
            SearchContext ours = new SearchContext(cfg, new BlockRules(cfg), g, BodySnapshot.holding(new ItemStack(Items.IRON_SHOVEL)), oursA);
            ForkMirror.Context fork = ForkMirror.context(ours, forkA);
            BlockState dirt = Blocks.DIRT.defaultBlockState();
            double f = MovementHelper.getMiningDurationTicksUnderBroken(fork, X, Y, Z, dirt);
            double o = BlockRules.getMiningDurationTicksUnderBroken(ours, X, Y, Z, dirt);
            assertEquals(f, o);
            assertTrue(o < 1_000_000, "judged with the sand already broken: " + o);
            assertEquals(MovementHelper.getMiningDurationTicks(fork, X, Y, Z, dirt, false), BlockRules.getMiningDurationTicks(ours, X, Y, Z, dirt, false));
            assertEquals(1_000_000, BlockRules.getMiningDurationTicks(ours, X, Y, Z, dirt, false), "seen with the sand: forbidden");
            assertEquals(null, ours.advisorFailure());
        } finally {
            ForkMirror.restore(old);
        }
    }

    private static void same(List<String> diffs, String what, Object fork, Object ours) {
        if (!Objects.equals(fork, ours)) diffs.add(what + " fork=" + fork + " ours=" + ours);
    }
}
