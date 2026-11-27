package io.github.mojolowjo.entropybot.nav.price;

import baritone.api.pathing.calc.IPath;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.moves.MoveShape;
import io.github.mojolowjo.entropybot.nav.price.PathBuild.Seen;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.BodySnapshot;
import io.github.mojolowjo.entropybot.nav.rules.ForkMirror;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.search.AStarPathFinder;
import io.github.mojolowjo.entropybot.nav.search.Favoring;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.SearchMatchForkTest;
import io.github.mojolowjo.entropybot.nav.search.SearchMatchForkTest.GoalPair;
import io.github.mojolowjo.entropybot.nav.search.SearchMatchForkTest.ScenarioView;
import io.github.mojolowjo.entropybot.nav.search.Step;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.Ternary;
import io.github.mojolowjo.entropybot.sim.Scenario;
import io.github.mojolowjo.entropybot.sim.SimSearch;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nav step 7's check: on the routes of the step 6 milestone (every 4th sim world, its goals, defaults and parkour + advisor),
 * our pricing pieces give what the fork jar's give. Per route: (1) every move's cells (MoveShape against the fork's Movement
 * objects: toBreakAll in order, placeAt, getValidPositions in iteration order); (2) the kept path built from the segment
 * (our PathBuild over our Path against the fork's PathBuild over its IPath: cells, costs along, each move's positions, every
 * watched cell with its last move, its read as Seen and its planned break or place; the cells read with our BlockRules and
 * with the fork's MovementHelper); (3) the path memory over that path (ours against the fork's PathMemory): an ask from
 * every 3rd cell, from two blocks off the path, and after a watched cell changes, each decision's kind, price, index,
 * cells and drop reason. The fork's PricedPath itself needs a running Baritone; these are the parts it is built from.
 */
class PricingMatchForkTest {

    @BeforeAll
    static void boot() { SimSearch.boot(); }

    static final int MAX_NODES = 20_000;
    static final long NO_CLOCK_MS = 600_000;

    private static String txt(int x, int y, int z) { return x + " " + y + " " + z; }

    /** Our facts: BlockRules over our view. */
    static PathBuild.CellFacts ourFacts(BlockRules rules, io.github.mojolowjo.entropybot.nav.world.BlockView v) {
        return KeptAnswers.facts(rules, v);
    }

    /** The fork's facts: MovementHelper over the fork's view of the same cells (its settings applied by ForkMirror). */
    static baritone.behavior.PathBuild.CellFacts forkFacts(baritone.utils.BlockStateInterface bsi) {
        return new baritone.behavior.PathBuild.CellFacts() {
            @Override public boolean walkThrough(BlockState s, int x, int y, int z) { return MovementHelper.canWalkThrough(bsi, x, y, z, s); }
            @Override public boolean fullyPassable(BlockState s, int x, int y, int z) {
                baritone.pathing.precompute.Ternary t = MovementHelper.fullyPassableBlockState(s);
                return t == baritone.pathing.precompute.Ternary.YES || t == baritone.pathing.precompute.Ternary.MAYBE && s.isPathfindable(PathComputationType.LAND);
            }
            @Override public boolean walkOn(BlockState s, int x, int y, int z) { return MovementHelper.canWalkOn(bsi, x, y, z, s); }
            @Override public boolean avoidWalkingInto(BlockState s) { return MovementHelper.avoidWalkingInto(s); }
        };
    }

    static String seen(Seen s) { return s == null ? "null" : s.bits() + "/" + s.block(); }
    static String seen(baritone.behavior.PathBuild.Seen s) { return s == null ? "null" : s.bits() + "/" + s.block(); }

    /** (1) the move's cells. */
    static String shapeDiff(Step s, Movement m) {
        MoveShape sh = MoveShape.of(s.move(), s.src(), s.dest());
        List<String> ob = new ArrayList<>(), fb = new ArrayList<>(), ov = new ArrayList<>(), fv = new ArrayList<>();
        for (BetterBlockPos p : sh.breaks()) ob.add(txt(p.x, p.y, p.z));
        for (net.minecraft.core.BlockPos p : m.toBreakAll()) fb.add(txt(p.getX(), p.getY(), p.getZ()));
        for (BetterBlockPos p : sh.valid()) ov.add(txt(p.x, p.y, p.z));
        for (baritone.api.utils.BetterBlockPos p : m.getValidPositions()) fv.add(txt(p.x, p.y, p.z));
        String op = sh.place() == null ? "none" : txt(sh.place().x, sh.place().y, sh.place().z);
        String fp = m.placeAt() == null ? "none" : txt(m.placeAt().x, m.placeAt().y, m.placeAt().z);
        if (!ob.equals(fb)) return s.move() + " breaks " + ob + " vs fork " + fb;
        if (!op.equals(fp)) return s.move() + " place " + op + " vs fork " + fp;
        if (!ov.equals(fv)) return s.move() + " valid " + ov + " vs fork " + fv;
        return null;
    }

    /** The build's numbers as one comparable text per part. */
    static List<String> ours(PathBuild.Built<Seen> b) {
        List<String> out = new ArrayList<>();
        StringBuilder cells = new StringBuilder();
        for (PathMemory.Cell c : b.cells()) cells.append(c.text()).append(';');
        out.add("cells " + cells);
        StringBuilder cum = new StringBuilder();
        for (int i = 0; i < b.path().size(); i++) cum.append(b.path().cum(i)).append(';');
        out.add("cum " + cum);
        out.add("segOf " + java.util.Arrays.toString(b.segOf()) + " posOf " + java.util.Arrays.toString(b.posOf()));
        StringBuilder w = new StringBuilder();
        for (Map.Entry<PathMemory.Cell, PathMemory.Watch<Seen>> e : b.path().watched().entrySet()) {
            PathMemory.Watch<Seen> x = e.getValue();
            w.append(e.getKey().text()).append('=').append(x.lastMove()).append(',').append(seen(x.was())).append(',').append(x.breaks()).append(',').append(x.places()).append(';');
        }
        out.add("watch " + w);
        return out;
    }

    static List<String> fork(baritone.behavior.PathBuild.Built<baritone.behavior.PathBuild.Seen> b) {
        List<String> out = new ArrayList<>();
        StringBuilder cells = new StringBuilder();
        for (baritone.api.pathing.calc.PathMemory.Cell c : b.cells()) cells.append(c.text()).append(';');
        out.add("cells " + cells);
        StringBuilder cum = new StringBuilder();
        for (int i = 0; i < b.path().size(); i++) cum.append(b.path().cum(i)).append(';');
        out.add("cum " + cum);
        out.add("segOf " + java.util.Arrays.toString(b.segOf()) + " posOf " + java.util.Arrays.toString(b.posOf()));
        StringBuilder w = new StringBuilder();
        for (Map.Entry<baritone.api.pathing.calc.PathMemory.Cell, baritone.api.pathing.calc.PathMemory.Watch<baritone.behavior.PathBuild.Seen>> e : b.path().watched().entrySet()) {
            var x = e.getValue();
            w.append(e.getKey().text()).append('=').append(x.lastMove()).append(',').append(seen(x.was())).append(',').append(x.breaks()).append(',').append(x.places()).append(';');
        }
        out.add("watch " + w);
        return out;
    }

    static String decision(PathMemory.Decision<Object> d) {
        StringBuilder c = new StringBuilder();
        if (d.cells() != null) for (PathMemory.Cell x : d.cells()) c.append(x.text()).append(';');
        return d.kind() + " " + d.answer() + " i" + d.index() + " [" + c + "] " + d.dropped();
    }

    static String decision(baritone.api.pathing.calc.PathMemory.Decision<Object> d) {
        StringBuilder c = new StringBuilder();
        if (d.cells() != null) for (baritone.api.pathing.calc.PathMemory.Cell x : d.cells()) c.append(x.text()).append(';');
        return d.kind() + " " + d.answer() + " i" + d.index() + " [" + c + "] " + d.dropped();
    }

    long routes, moves, watched, asks;

    /** One route on both sides: the moves' cells, the build, the memory. Adds the differences found. */
    void compare(String where, Path op, AStarPathFinder of, IPath fp, baritone.pathing.calc.AStarPathFinder ff,
                 BlockRules rules, ScenarioView view, baritone.utils.BlockStateInterface fbsi, List<String> diffs) {
        routes++;
        for (int i = 0; i < op.steps().size(); i++) {
            moves++;
            String d = shapeDiff(op.steps().get(i), (Movement) fp.movements().get(i));
            if (d != null) { diffs.add(where + " move " + i + ": " + d); return; }
        }
        Map<PathMemory.Cell, BlockState> changed = new HashMap<>();
        Map<String, BlockState> fchanged = new HashMap<>();
        PathBuild.CellFacts of0 = ourFacts(rules, view);
        baritone.behavior.PathBuild.CellFacts ff0 = forkFacts(fbsi);
        Function<PathMemory.Cell, Seen> oread = c -> PathBuild.classify(changed.getOrDefault(c, view.get0(c.x(), c.y(), c.z())), c.x(), c.y(), c.z(), true, of0);
        Function<baritone.api.pathing.calc.PathMemory.Cell, baritone.behavior.PathBuild.Seen> fread = c -> baritone.behavior.PathBuild.classify(
                fchanged.getOrDefault(c.text(), view.get0(c.x(), c.y(), c.z())), c.x(), c.y(), c.z(), true, ff0);
        double[] oa = PathBuild.along(of::nodeCost, op);
        double[] fa = baritone.behavior.PathBuild.along(ff::nodeCost, fp);
        PathBuild.Built<Seen> ob = PathBuild.build(List.of(op), List.of(oa), List.of(PathBuild.snapshot(op, oread)));
        baritone.behavior.PathBuild.Built<baritone.behavior.PathBuild.Seen> fb =
                baritone.behavior.PathBuild.build(List.of(fp), List.of(fa), List.of(baritone.behavior.PathBuild.snapshot(fp, fread)));
        watched += ob.path().watched().size();
        List<String> a = ours(ob), b = fork(fb);
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                diffs.add(where + " build " + a.get(i).split(" ")[0] + ":\n  ours " + a.get(i) + "\n  fork " + b.get(i));
                return;
            }
        }
        // (3) the memory over that path
        PathMemory<String, Object, Seen> om = new PathMemory<>();
        baritone.api.pathing.calc.PathMemory<String, Object, baritone.behavior.PathBuild.Seen> fm = new baritone.api.pathing.calc.PathMemory<>();
        IPricedPath.Limits ol = new IPricedPath.Limits(6000, 1200, 16, 4);
        baritone.api.pathing.calc.IPricedPath.Limits fl = new baritone.api.pathing.calc.IPricedPath.Limits(6000, 1200, 16, 4);
        PathMemory.Stamp os = new PathMemory.Stamp(Map.of("canSprint", true), Map.of(), Map.of());
        baritone.api.pathing.calc.PathMemory.Stamp fs = new baritone.api.pathing.calc.PathMemory.Stamp(Map.of("canSprint", true), Map.of(), Map.of());
        BetterBlockPos s0 = op.getSrc();
        PathMemory.Cell oc0 = new PathMemory.Cell(s0.x, s0.y, s0.z);
        baritone.api.pathing.calc.PathMemory.Cell fc0 = new baritone.api.pathing.calc.PathMemory.Cell(s0.x, s0.y, s0.z);
        Function<int[], PathMemory.Now<Seen>> onow = p -> new PathMemory.Now<>(new PathMemory.Cell(p[0], p[1], p[2]), op.getGoal() != null && op.getGoal().isInGoal(p[0], p[1], p[2]),
                "w", () -> os, 1, () -> PathBuild.blocks(oread::apply));
        Function<int[], baritone.api.pathing.calc.PathMemory.Now<baritone.behavior.PathBuild.Seen>> fnow = p -> new baritone.api.pathing.calc.PathMemory.Now<>(
                new baritone.api.pathing.calc.PathMemory.Cell(p[0], p[1], p[2]), fp.getGoal() != null && fp.getGoal().isInGoal(p[0], p[1], p[2]),
                "w", () -> fs, 1, () -> baritone.behavior.PathBuild.blocks(fread::apply));
        int[] start = {s0.x, s0.y, s0.z};
        om.ask("G", onow.apply(start), ol);
        fm.ask("G", fnow.apply(start), fl);
        om.started("G", oc0, "run");
        fm.started("G", fc0, "run");
        om.landed("G", "run", IPathCost.Answer.complete(ob.path().total()), ob.path(), oc0, "w", os, 1, null);
        fm.landed("G", "run", baritone.api.pathing.calc.IPathCost.Answer.complete(fb.path().total()), fb.path(), fc0, "w", fs, 1, null);
        List<int[]> at = new ArrayList<>();
        for (int i = 0; i < op.positions().size(); i += 3) { BetterBlockPos p = op.positions().get(i); at.add(new int[]{p.x, p.y, p.z}); }
        BetterBlockPos mid = op.positions().get(op.positions().size() / 2);
        at.add(new int[]{mid.x + 2, mid.y, mid.z});
        for (int[] p : at) {
            asks++;
            String x = decision(om.ask("G", onow.apply(p), ol)), y = decision(fm.ask("G", fnow.apply(p), fl));
            if (!x.equals(y)) { diffs.add(where + " ask at " + txt(p[0], p[1], p[2]) + ":\n  ours " + x + "\n  fork " + y); return; }
        }
        // a watched cell of a later move turns to glass (or air, for glass): both memories judge it at the next ask
        List<PathMemory.Cell> keys = new ArrayList<>(ob.path().watched().keySet());
        if (keys.isEmpty()) return;
        PathMemory.Cell c = keys.get((keys.size() * 2) / 3);
        BlockState now = view.get0(c.x(), c.y(), c.z()).is(Blocks.GLASS) ? Blocks.AIR.defaultBlockState() : Blocks.GLASS.defaultBlockState();
        changed.put(c, now);
        fchanged.put(c.text(), now);
        asks++;
        String x = decision(om.ask("G", onow.apply(start), ol)), y = decision(fm.ask("G", fnow.apply(start), fl));
        if (!x.equals(y)) diffs.add(where + " ask after " + c.text() + " changed:\n  ours " + x + "\n  fork " + y);
    }

    @Test
    void ourPricingPiecesGiveTheForksOnTheMilestoneRoutes() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(PricingMatchForkTest.class.getResource("/scenarios/worlds").toURI());
        List<java.nio.file.Path> files;
        try (var st = Files.list(dir)) {
            files = st.filter(f -> f.toString().endsWith(".json.gz")).sorted().toList();
        }
        BodySnapshot body = BodySnapshot.holding(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.IRON_AXE));
        List<String> diffs = new ArrayList<>();
        for (int fi = 0; fi < files.size(); fi += 4) {
            Scenario s = Scenario.read(files.get(fi));
            ScenarioView view = new ScenarioView(s);
            String world = files.get(fi).getFileName().toString();
            BetterBlockPos start = new BetterBlockPos(s.feet[0], s.feet[1], s.feet[2]);
            baritone.api.utils.BetterBlockPos fstart = new baritone.api.utils.BetterBlockPos(s.feet[0], s.feet[1], s.feet[2]);
            for (int setup = 0; setup < 2; setup++) {
                NavConfig cfg = setup == 0 ? NavConfig.defaults() : SearchMatchForkTest.parkourConfig();
                BlockRules rules = new BlockRules(cfg);
                for (GoalPair g : SearchMatchForkTest.goals(s, view, new BlockRules(NavConfig.defaults()))) {
                    Map<String, Object> old = ForkMirror.apply(cfg);
                    try {
                        SearchContext oc = new SearchContext(cfg, rules, view, body, setup == 0 ? null : new SearchMatchForkTest.OurAdvisor());
                        ForkMirror.Context fc = ForkMirror.context(oc, setup == 0 ? null : new SearchMatchForkTest.ForkAdvisor());
                        AStarPathFinder of = new AStarPathFinder(start, start.x, start.y, start.z, g.ours(), Favoring.none(), oc).costQuery(MAX_NODES, Double.POSITIVE_INFINITY);
                        baritone.pathing.calc.AStarPathFinder ff = new baritone.pathing.calc.AStarPathFinder(fstart, fstart.x, fstart.y, fstart.z, g.fork(),
                                new baritone.utils.pathing.Favoring(null, fc), fc);
                        ff.costQuery(MAX_NODES, Double.POSITIVE_INFINITY);
                        Path op = of.calculate(NO_CLOCK_MS, NO_CLOCK_MS).getPath().orElse(null);
                        IPath fp = ff.calculate(NO_CLOCK_MS, NO_CLOCK_MS).getPath().orElse(null);
                        String where = world + (setup == 0 ? " defaults " : " parkour+advisor ") + g.name();
                        if ((op == null) != (fp == null) || op != null && op.positions().size() != fp.positions().size()) {
                            diffs.add(where + ": the searches differ (SearchMatchForkTest says where)");
                            continue;
                        }
                        if (op != null && op.positions().size() > 1) {
                            compare(where, op, of, fp, ff, rules, view, ForkMirror.bsi(view), diffs);
                        }
                    } finally {
                        ForkMirror.restore(old);
                    }
                }
            }
        }
        System.out.println("[nav step 7] " + routes + " routes, " + moves + " moves, " + watched + " watched cells, " + asks + " memory asks compared, differences " + diffs.size());
        assertTrue(routes >= 200, "too few routes to test much: " + routes);
        assertTrue(diffs.isEmpty(), diffs.size() + " routes differ from the fork, first ones:\n" + String.join("\n", diffs.subList(0, Math.min(10, diffs.size()))));
    }
}
