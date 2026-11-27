package io.github.mojolowjo.entropybot.nav.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.BodySnapshot;
import io.github.mojolowjo.entropybot.nav.rules.CostAdvisor;
import io.github.mojolowjo.entropybot.nav.rules.ForkMirror;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalBlock;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalNear;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalXZ;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalYLevel;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.BetterWorldBorder;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import io.github.mojolowjo.entropybot.sim.Scenario;
import io.github.mojolowjo.entropybot.sim.SimSearch;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * nav step 6, the milestone (to-do/nav-steps-1-6.md): on the sim's real game worlds (the 100 cubes Tester 2 saved,
 * 65 x 65 x 65 around each spawn, every block state as the game had it), our A* finds the same route at the same cost
 * as the fork jar's A*: the same result kind, the same stop reason, the same cells in the same order, the same step
 * prices, the same node count. Both sides see the same world and price digging with the same tool (ours, through
 * {@link ForkMirror}). Each search is bounded by nodes, not the clock, so it is repeatable. Two setups: Baritone's
 * defaults; and parkour and diagonals on with a cost advisor and the first search's path favoured (backtracking).
 */
class SearchMatchForkTest {

    static final int MAX_NODES = 20_000;
    static final long NO_CLOCK_MS = 600_000;

    @BeforeAll
    static void boot() { SimSearch.boot(); }

    /** A scenario cube as a BlockView: the saved states; a cell the file holds none for is bedrock (as sim/SimSearch). */
    static final class ScenarioView extends BlockView {
        private final Scenario s;
        private final BlockState[] cells;

        ScenarioView(Scenario s) {
            super(new BetterWorldBorder(new WorldBorder()), -64, 384);
            this.s = s;
            Map<String, BlockState> parsed = new HashMap<>();
            cells = new BlockState[s.size()];
            for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
                String id = s.blockAt(s.x0 + x, s.y0 + y, s.z0 + z);
                cells[s.index(s.x0 + x, s.y0 + y, s.z0 + z)] = parsed.computeIfAbsent(id, ScenarioView::parse);
            }
        }

        static BlockState parse(String id) {
            if (Scenario.NOT_LOADED.equals(id)) return Blocks.BEDROCK.defaultBlockState();
            try {
                return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), id, false).blockState();
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                throw new IllegalStateException("a saved block state the game cannot read: " + id, e);
            }
        }

        @Override public BlockState get0(int x, int y, int z) {
            int i = s.index(x, y, z);
            return i < 0 ? Blocks.BEDROCK.defaultBlockState() : cells[i];
        }
        @Override public boolean isLoaded(int x, int z) { return true; }
        @Override public boolean worldContainsLoadedChunk(int x, int z) { return true; }
    }

    record GoalPair(String name, Goal ours, baritone.api.pathing.goals.Goal fork) {}

    static List<GoalPair> goals(Scenario s, ScenarioView v, BlockRules rules) {
        List<GoalPair> out = new ArrayList<>();
        int fx = s.feet[0], fy = s.feet[1], fz = s.feet[2];
        Random r = new Random(fx * 31L + fz * 17L + fy);
        int found = 0;
        for (int tries = 0; tries < 4000 && found < 5; tries++) {
            int x = fx + r.nextInt(57) - 28, z = fz + r.nextInt(57) - 28, y = fy + r.nextInt(21) - 10;
            if (rules.canWalkOn(v, x, y - 1, z) && rules.canWalkThrough(v, x, y, z) && rules.canWalkThrough(v, x, y + 1, z)) {
                out.add(new GoalPair("block " + x + " " + y + " " + z, new GoalBlock(x, y, z), new baritone.api.pathing.goals.GoalBlock(x, y, z)));
                found++;
            }
        }
        out.add(new GoalPair("y " + (fy - 12), new GoalYLevel(fy - 12), new baritone.api.pathing.goals.GoalYLevel(fy - 12)));
        out.add(new GoalPair("y " + (fy + 9), new GoalYLevel(fy + 9), new baritone.api.pathing.goals.GoalYLevel(fy + 9)));
        out.add(new GoalPair("near", new GoalNear(new net.minecraft.core.BlockPos(fx + 20, fy, fz - 20), 3),
                new baritone.api.pathing.goals.GoalNear(new net.minecraft.core.BlockPos(fx + 20, fy, fz - 20), 3)));
        out.add(new GoalPair("xz far", new GoalXZ(fx - 200, fz + 150), new baritone.api.pathing.goals.GoalXZ(fx - 200, fz + 150)));
        return out;
    }

    static final class OurAdvisor implements CostAdvisor {
        @Override public double breakMultiplier(int x, int y, int z, BlockState s, BlockView v) {
            return v.get0(x, y + 1, z).getBlock() instanceof FallingBlock ? ActionCosts.COST_INF : ((x + z) & 3) == 0 ? 3 : Double.NaN;
        }
        @Override public double placeCost(int x, int y, int z, BlockState s, BlockView v, double d) { return ((x ^ z) & 1) == 0 ? 9 : Double.NaN; }
        @Override public double moveMultiplier(int x, int y, int z) { return ((x * 7 + z) & 7) == 0 ? 1.5 : Double.NaN; }
        @Override public double moveExtra(int x, int y, int z, BlockView v) { return y < 60 ? 2 : Double.NaN; }
    }

    static final class ForkAdvisor implements baritone.pathing.movement.CostAdvisor {
        @Override public Object begin() { return null; }
        @Override public double breakMultiplier(Object t, int x, int y, int z, BlockState s, baritone.utils.BlockStateInterface v) {
            return v.get0(x, y + 1, z).getBlock() instanceof FallingBlock ? ActionCosts.COST_INF : ((x + z) & 3) == 0 ? 3 : Double.NaN;
        }
        @Override public double placeCost(Object t, int x, int y, int z, BlockState s, baritone.utils.BlockStateInterface v, double d) {
            return ((x ^ z) & 1) == 0 ? 9 : Double.NaN;
        }
        @Override public double moveMultiplier(Object t, int x, int y, int z) { return ((x * 7 + z) & 7) == 0 ? 1.5 : Double.NaN; }
        @Override public double moveExtra(Object t, int x, int y, int z, baritone.utils.BlockStateInterface v) { return y < 60 ? 2 : Double.NaN; }
    }

    static NavConfig parkourConfig() {
        NavConfig d = NavConfig.defaults();
        NavConfig.Moves m = d.moves();
        return d.withMoves(new NavConfig.Moves(true, true, true, true, true, true, m.allowDownward(), true /* the fork reads its world otherwise */,
                m.allowVines(), m.allowWalkOnBottomSlab(), m.allowWalkOnMagmaBlocks(), m.assumeWalkOnWater(), m.assumeWalkOnLava(), m.allowWaterBucketFall(),
                m.maxFallHeightNoWater(), m.maxFallHeightBucket(), m.jumpPenalty(), m.walkOnWaterOnePenalty(), m.pathUnderWater(), m.lavaCellCost(),
                m.strictLiquidCheck(), m.blocksToAvoid()));
    }

    record Answer(String type, String stop, List<String> cells, List<Double> stepCosts, double ticks, int nodes) {
        String brief() { return type + "/" + stop + " " + cells.size() + " cells " + ticks + " ticks " + nodes + " nodes"; }
    }

    static Answer ours(PathCalculationResult r, AStarPathFinder f) {
        List<String> cells = new ArrayList<>();
        List<Double> steps = new ArrayList<>();
        Path p = r.getPath().orElse(null);
        if (p != null) {
            for (BetterBlockPos b : p.positions()) cells.add(b.x + " " + b.y + " " + b.z);
            for (Step s : p.steps()) steps.add(s.cost());
        }
        return new Answer(r.getType().name(), f.stop().name(), cells, steps, p == null ? Double.NaN : p.ticksRemainingFrom(0), f.nodesOpened());
    }

    static Answer fork(baritone.api.utils.PathCalculationResult r, baritone.pathing.calc.AStarPathFinder f) {
        List<String> cells = new ArrayList<>();
        List<Double> steps = new ArrayList<>();
        IPath p = r.getPath().orElse(null);
        if (p != null) {
            for (baritone.api.utils.BetterBlockPos b : p.positions()) cells.add(b.x + " " + b.y + " " + b.z);
            for (IMovement m : p.movements()) steps.add(m.getCost());
        }
        return new Answer(r.getType().name(), f.stop().name(), cells, steps, p == null ? Double.NaN : p.ticksRemainingFrom(0), f.nodesOpened());
    }

    @Test
    void ourSearchFindsTheForksRoutesOnTheSimWorlds() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(SearchMatchForkTest.class.getResource("/scenarios/worlds").toURI());
        List<java.nio.file.Path> files;
        try (var st = Files.list(dir)) {
            files = st.filter(f -> f.toString().endsWith(".json.gz")).sorted().toList();
        }
        assertTrue(files.size() >= 100, "the sim's worlds: " + files.size());
        BodySnapshot body = BodySnapshot.holding(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.IRON_AXE));
        List<String> diffs = new ArrayList<>();
        Map<String, Integer> kinds = new TreeMap<>();
        int searches = 0;
        long cellsCompared = 0;
        for (int fi = 0; fi < files.size(); fi += 4) { // every 4th world: 25 worlds, all biomes of the batch
            Scenario s = Scenario.read(files.get(fi));
            ScenarioView view = new ScenarioView(s);
            String world = files.get(fi).getFileName().toString();
            NavConfig d = NavConfig.defaults();
            NavConfig pk = parkourConfig();
            BlockRules dRules = new BlockRules(d);
            BlockRules pkRules = new BlockRules(pk);
            for (GoalPair g : goals(s, view, dRules)) {
                BetterBlockPos start = new BetterBlockPos(s.feet[0], s.feet[1], s.feet[2]);
                baritone.api.utils.BetterBlockPos fstart = new baritone.api.utils.BetterBlockPos(s.feet[0], s.feet[1], s.feet[2]);
                // setup 1: defaults
                Map<String, Object> old = ForkMirror.apply(d);
                Path ourFirst;
                IPath forkFirst;
                try {
                    SearchContext oc = new SearchContext(d, dRules, view, body, null);
                    ForkMirror.Context fc = ForkMirror.context(oc, null);
                    AStarPathFinder of = new AStarPathFinder(start, start.x, start.y, start.z, g.ours(), Favoring.none(), oc).costQuery(MAX_NODES, Double.POSITIVE_INFINITY);
                    baritone.pathing.calc.AStarPathFinder ff = new baritone.pathing.calc.AStarPathFinder(fstart, fstart.x, fstart.y, fstart.z, g.fork(),
                            new baritone.utils.pathing.Favoring(null, fc), fc);
                    ff.costQuery(MAX_NODES, Double.POSITIVE_INFINITY);
                    PathCalculationResult orr = of.calculate(NO_CLOCK_MS, NO_CLOCK_MS);
                    baritone.api.utils.PathCalculationResult frr = ff.calculate(NO_CLOCK_MS, NO_CLOCK_MS);
                    Answer a = ours(orr, of), b = fork(frr, ff);
                    searches++;
                    cellsCompared += b.cells().size();
                    kinds.merge("defaults " + b.type(), 1, Integer::sum);
                    if (!a.equals(b)) diffs.add(world + " defaults " + g.name() + ": fork " + b.brief() + " | ours " + a.brief() + (orr.failure() == null ? "" : " " + orr.failure()));
                    ourFirst = orr.getPath().orElse(null);
                    forkFirst = frr.getPath().orElse(null);
                } finally {
                    ForkMirror.restore(old);
                }
                // setup 2: parkour, diagonals, advisor, the first path favoured
                old = ForkMirror.apply(pk);
                try {
                    SearchContext oc = new SearchContext(pk, pkRules, view, body, new OurAdvisor());
                    ForkMirror.Context fc = ForkMirror.context(oc, new ForkAdvisor());
                    Favoring ofav = new Favoring(ourFirst == null ? null : ourFirst.positions(), pk.search().backtrackCostFavoringCoefficient());
                    AStarPathFinder of = new AStarPathFinder(start, start.x, start.y, start.z, g.ours(), ofav, oc).costQuery(MAX_NODES, Double.POSITIVE_INFINITY);
                    baritone.pathing.calc.AStarPathFinder ff = new baritone.pathing.calc.AStarPathFinder(fstart, fstart.x, fstart.y, fstart.z, g.fork(),
                            new baritone.utils.pathing.Favoring(forkFirst, fc), fc);
                    ff.costQuery(MAX_NODES, Double.POSITIVE_INFINITY);
                    PathCalculationResult orr = of.calculate(NO_CLOCK_MS, NO_CLOCK_MS);
                    baritone.api.utils.PathCalculationResult frr = ff.calculate(NO_CLOCK_MS, NO_CLOCK_MS);
                    Answer a = ours(orr, of), b = fork(frr, ff);
                    searches++;
                    cellsCompared += b.cells().size();
                    kinds.merge("parkour+advisor " + b.type(), 1, Integer::sum);
                    if (!a.equals(b)) diffs.add(world + " parkour+advisor " + g.name() + ": fork " + b.brief() + " | ours " + a.brief() + (orr.failure() == null ? "" : " " + orr.failure()));
                    if (oc.advisorFailure() != null) diffs.add(world + " advisor failed: " + oc.advisorFailure());
                } finally {
                    ForkMirror.restore(old);
                }
            }
        }
        System.out.println("[nav milestone] " + searches + " searches, " + cellsCompared + " path cells compared, kinds " + kinds + ", differences " + diffs.size());
        assertTrue(kinds.getOrDefault("defaults SUCCESS_TO_GOAL", 0) >= 50, "too few searches reach their goal to test much: " + kinds);
        assertTrue(diffs.isEmpty(), diffs.size() + " of " + searches + " searches differ from the fork, first ones:\n"
                + String.join("\n", diffs.subList(0, Math.min(20, diffs.size()))));
    }
}
