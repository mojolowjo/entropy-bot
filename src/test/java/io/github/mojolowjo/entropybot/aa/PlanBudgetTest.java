package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.commands.SyncPlanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-plan-budget (worlds run 4: w003, w052, w066, w075, w079, w080, w084 out of a 3 GB heap while the bot stood waiting
 * on the planner; w084:3109 and w052:1700 log cobblestone orders priced through diamond_pickaxe &lt;- iron_pickaxe &lt;-
 * stone_pickaxe &lt;- wooden_pickaxe, "wooden_pickaxe: not priced (the search's bound: needChain.maxTrials, 4000 ways a
 * chain)"): w084's shape - the bot 30 blocks under ground at y 70, the walk up out for the order, no pickaxe, no placeable
 * block, w084's sources (trees on the surface, stone under 19 blocks of cover, the iron and diamond bands, a cave for
 * each). On c606aca8 the cobblestone order's resolve made 6098 nodes and torch 4 made 8525 (every pickaxe tier's whole
 * tree priced again inside every other's); with a pickaxe need inside a pickaxe's price left to that pickaxe's own way
 * (NeedChain.pickInside) the same answers take a few hundred. Each runs through the tick and the worker path (SyncPlanner:
 * the snapshot, PlannerWorker.resolve, the port's check). Hand numbers: the test's own world.
 */
class PlanBudgetTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        PlanNodes.forgetMine();
        BatchWay.clear();
    }

    /** w084's feet (its y; x z the tests' own). */
    static final int[] FEET = {-181, 70, 27};

    /** The game's pickaxes tagged, every wood's planks standing in (as SimGame's data), the stone pickaxe from the game's tag. */
    static void data() {
        TestData d = TestData.planner();
        d.recipes.remove(TestData.id("stick"));
        d.recipes.remove(TestData.id("crafting_table"));
        d.recipe("stick", 4, "#minecraft:planks", "#minecraft:planks");
        d.recipe("crafting_table", 1, "#minecraft:planks", "#minecraft:planks", "#minecraft:planks", "#minecraft:planks");
        for (String m : List.of("wooden", "stone", "iron", "golden", "diamond")) d.tag(m + "_pickaxe", "minecraft:pickaxes");
        // w084's loop text: "stone_pickaxe > #stone_tool_materials"
        d.recipes.remove(TestData.id("stone_pickaxe"));
        d.recipe("stone_pickaxe", 1, "#minecraft:stone_tool_materials", "#minecraft:stone_tool_materials", "#minecraft:stone_tool_materials", "stick", "stick");
        d.install();
    }

    static LootBook book() {
        return new LootBook(List.of(new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:iron_ore", "minecraft:raw_iron", 1, 1, false, null),
                new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:diamond_ore", "minecraft:diamond", 1, 1, false, null),
                new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:stone", "minecraft:cobblestone", 1, 1, false, null)), "test");
    }

    static GenWhere.Fact where(String word) {
        if (word.contains("log") || word.contains("planks")) return new GenWhere.Fact(GenWhere.Kind.SURFACE, 0, 0, "minecraft:oak_log", "minecraft:trees_plains", null);
        if (word.contains("raw_iron") || word.contains("iron_ore")) return new GenWhere.Fact(GenWhere.Kind.BAND, -24, 56, "minecraft:iron_ore", "minecraft:ore_iron_middle", null,
                List.of(new GenWhere.Band(-24, 56, 90, "minecraft:ore_iron_middle", new io.github.mojolowjo.entropybot.aa.brain.OreBand.Feature(-24, 56, 0, 10, 9))));
        if (word.contains("diamond")) return new GenWhere.Fact(GenWhere.Kind.BAND, -64, 16, "minecraft:diamond_ore", "minecraft:ore_diamond", null,
                List.of(new GenWhere.Band(-64, 16, 90, "minecraft:ore_diamond", new io.github.mojolowjo.entropybot.aa.brain.OreBand.Feature(-64, 16, 0, 7, 4))));
        return GenWhere.unknown("test");
    }

    static int[] band(String word) {
        if (word.contains("raw_iron") || word.contains("iron_ore")) return new int[]{-24, 56};
        if (word.contains("diamond")) return new int[]{-64, 16};
        return null;
    }

    /** w084's known caves: one 229 m off for diamond, one 185 m off for iron (thin: dear, as the log's). */
    static NeedChain.CaveWay cave(String word) {
        boolean dia = word.contains("diamond"), iron = word.contains("raw_iron") || word.contains("iron_ore");
        if (!dia && !iron) return null;
        int x = dia ? 48 : -3, y = dia ? -20 : 40, z = dia ? 229 : 185;
        return new NeedChain.CaveWay(x, y, z, dia ? 229.0 : 185.0, dia ? "minecraft:diamond_ore" : "minecraft:iron_ore", dia ? "diamond" : "iron", 0.9, 0.0,
                null, 1.92, List.of(), List.of(new int[]{x, y, z}))
                .withPassages(List.of(new io.github.mojolowjo.entropybot.cave.Passages.Passage(0, -1, new int[]{x, y, z}, new int[]{x, y, z}, 100, 1, 4, List.of(), 200, 0,
                        io.github.mojolowjo.entropybot.cave.Passages.EndKind.DEAD_END)))
                .withDensity(io.github.mojolowjo.entropybot.cave.CaveWalk.Density.flat(0.001, "test: w084's thin caves"));
    }

    /** The sources w084 knew: three trees on the surface, stone under 19 blocks of cover. */
    static Map<String, List<NeedChain.Spot>> seen() {
        return Map.of(
                "oak_log", List.of(new NeedChain.Spot(-141, 100, 27, 40, "minecraft:oak_log", 5)),
                "birch_log", List.of(new NeedChain.Spot(-151, 101, 47, 50, "minecraft:birch_log", 5)),
                "spruce_log", List.of(new NeedChain.Spot(-201, 102, 57, 60, "minecraft:spruce_log", 6)),
                "cobblestone", List.of(new NeedChain.Spot(-176, 51, 30, 20, "minecraft:stone", 40, "minecraft:cobblestone", 0, false, 19, "minecraft:stone")));
    }

    /** {@code depth} blocks under ground (0: on the surface); under ground the walk up is unreachable; every other walk by distance. */
    static NeedChain.World world(Map<String, Integer> bag, Map<String, List<NeedChain.Spot>> seen, int depth) {
        data();
        PathPrice byDist = FakePaths.byDistance();
        int top = FEET[1] + depth;
        return ScenariosTest.w(bag, false, false, seen).withMobs(x -> List.of(), book()).withBand(PlanBudgetTest::band).withCave(PlanBudgetTest::cave)
                .withWhere(new NeedChain.WhereFacts(PlanBudgetTest::where, () -> new NeedChain.Surface(depth, FEET[0], top, FEET[2], depth), FEET::clone))
                .withPath((x, y, z, d) -> depth > 0 && y == top ? PathPrice.Answer.unreachable("no path") : byDist.price(x, y, z, d));
    }

    /** Runs {@code r} on a thread of the worker's stack, at most {@code s} seconds (an out-of-heap or a throw is the answer). */
    static Object bounded(Supplier<?> r, int s) throws Exception {
        Object[] out = new Object[1];
        Thread t = new Thread(null, () -> {
            try { out[0] = r.get(); } catch (Throwable e) { out[0] = e; }
        }, "plan-budget", 64L << 20);      // structural: the worker's stack (PlannerWorker.STACK)
        t.setDaemon(true);
        t.start();
        t.join(s * 1000L);
        if (t.isAlive()) { t.interrupt(); return "still resolving after " + s + " s"; }
        return out[0];
    }

    /** {@code what n} on {@code w}: on the tick (NeedChain.resolve) and through the worker path (SyncPlanner's own ask); {tick, worker, tick nodes, worker nodes}. */
    static Object[] both(String what, int n, NeedChain.World w) throws Exception {
        List<String> said = new ArrayList<>();
        Object[] out = new Object[4];
        Object r = bounded(() -> {
            NeedChain.mostWays(true);
            Chain tick = NeedChain.resolve(what, n, w);
            out[2] = PlanNodes.last().nodes();
            PlanNodes.forgetMine();
            Chain worker = new SyncPlanner(said::add).own("goal." + what, new BatchWay.OwnAsk(what, List.of(), n, false), w);
            out[3] = PlanNodes.last().nodes();
            out[0] = tick;
            out[1] = worker;
            return worker;
        }, 120);       // structural: a test's time bound
        assertTrue(r instanceof Chain, "the resolve ended: " + r);
        assertTrue(said.isEmpty(), "no planner fault on the worker path: " + said);
        return out;
    }

    /**
     * w084's shape, the cobblestone order and torch 4 (the trip's two orders): the same answers on the tick and on the
     * worker, each in a few hundred nodes (c606aca8: 6098 and 8525), no node budget near.
     */
    @Test
    void w084_underGround_noPickaxe_theOrdersStaySmall_tickAndWorker() throws Exception {
        NeedChain.World w = world(Map.of(), seen(), 30);
        for (var o : List.of(Map.entry("cobblestone", 32), Map.entry("torch", 4))) {
            Object[] r = both(o.getKey(), o.getValue(), w);
            Chain tick = (Chain) r[0], worker = (Chain) r[1];
            assertEquals(tick.why(), worker.why(), "one answer whoever resolves");
            assertFalse(tick.ok(), "no pickaxe can be had from under ground with the walk up out: " + tick.why());
            assertTrue(tick.missing().startsWith(NeedChain.UNDER_GROUND), tick.missing());
            assertTrue((Integer) r[2] <= 500 && (Integer) r[3] <= 500, o + ": nodes " + r[2] + " / " + r[3]);      // c606aca8: 6098 / 8525
            assertTrue(NeedChain.mostWays(false) < NeedChain.maxWays() / 10, "far under the budget: " + NeedChain.mostWays(false));
            assertTrue(tick.why().length() < 2000, "the answer's text: " + tick.why().length());
        }
    }

    /** No source known at all (the job's first case): the climb's pickaxe-first has nothing to make it from; said, small. */
    @Test
    void underGround_nothingKnown_cobblestone_saidSmall() throws Exception {
        Object[] r = both("cobblestone", 63, world(Map.of(), Map.of(), 30));
        Chain tick = (Chain) r[0];
        assertEquals(tick.why(), ((Chain) r[1]).why());
        assertFalse(tick.ok());
        assertTrue(tick.missing().contains("no climb either: no pickaxe and 0 spare blocks of 0 in the bag") && tick.missing().contains("make a pickaxe first"), tick.missing());
        assertTrue((Integer) r[2] <= 100, "nodes " + r[2]);
    }

    /**
     * On the surface with the same sources the pickaxe chain stands (a wooden pickaxe from the tree, as before); the iron and
     * diamond pickaxes' ways are dropped with the reason - each needs a pickaxe that meets this need itself first.
     */
    @Test
    void onTheSurface_aPickaxeInsideAPickaxesPrice_isThatPickaxesOwnWay_said() throws Exception {
        Map<String, List<NeedChain.Spot>> seen = new java.util.HashMap<>(seen());
        seen.put("raw_iron", List.of(new NeedChain.Spot(-171, 66, 27, 11, "minecraft:iron_ore", 4, "minecraft:raw_iron", 0, false, 0, null)));
        Object[] r = both("cobblestone", 3, world(Map.of(), seen, 0));
        Chain tick = (Chain) r[0];
        assertEquals(tick.why(), ((Chain) r[1]).why());
        assertTrue(tick.ok(), tick.why());
        assertTrue(tick.why().contains("with wooden_pickaxe"), tick.why());
        assertTrue((Integer) r[2] <= 500, "nodes " + r[2]);
        // aa debug's tree (the whole thinking, rule 7): the iron pickaxe's dig of raw_iron needs a pickaxe of tier 2 inside the
        // price of the tier-1 one - said there, the stone pickaxe being that requirement's own way
        NeedChain.Trace t = NeedChain.trace();
        Chain traced = NeedChain.traced(t, () -> NeedChain.resolve("cobblestone", 3, world(Map.of(), seen, 0)));
        assertEquals(tick.why(), traced.why(), "the same chain traced");
        assertTrue(String.join("\n", t.lines(0)).contains(NeedChain.pickInside(2, 1)), "said in aa debug: " + String.join("\n", t.lines(0)));
    }

    /**
     * The bound (rule 7): past planner.maxWays the resolve fails "plan too large (N ways ...): the deepest chain ...", on the
     * tick and on the worker path alike, never an out-of-heap and never a thrown error.
     */
    @Test
    void pastTheBudget_theResolveFailsSaid_tickAndWorker() throws Exception {
        NeedChain.World w = world(Map.of(), seen(), 30);
        Tuning.set("planner.maxWays", 20);          // structural: a test's bound far under the order's ways
        Object[] r = both("torch", 4, w);
        for (int i = 0; i < 2; i++) {
            Chain c = (Chain) r[i];
            assertFalse(c.ok(), c.why());
            assertTrue(c.missing().startsWith(NeedChain.TOO_LARGE + "21 ways, planner.maxWays 20): the deepest chain torch"), c.missing());
            assertTrue(c.steps().isEmpty(), c.steps().toString());
        }
        assertEquals(((Chain) r[0]).why(), ((Chain) r[1]).why());
    }

    /**
     * The three-order line under ground (OrderLineSim: the real brain and Goals, the orders' own chains through the worker
     * path): cobblestone 32, oak_log 16 and stick 4 on one -d line, the bot 30 under ground, no pickaxe, nothing in the bag.
     * Every re-plan stays far under the budget.
     */
    @Test
    void threeOrderLine_underGround_everyReplanSmall() {
        OrderLineSim s = new OrderLineSim();
        s.under(30);
        s.path = (x, y, z, d) -> y == s.feet[1] + 30 ? PathPrice.Answer.unreachable("no path") : FakePaths.byDistance().price(x, y, z, d);
        s.seen.put("oak_log", List.of(new NeedChain.Spot(40, 94, 0, 40, "minecraft:oak_log", 5)));
        s.seen.put("cobblestone", List.of(new NeedChain.Spot(5, 45, 5, 20, "minecraft:stone", 40, "minecraft:cobblestone", 0, false, 19, "minecraft:stone")));
        s.bb.fact(BatchWay.PLANNER_FACT, s.sync);         // the trip's legs and the own chains through the worker's path (as SimGame)
        s.order("cobblestone", 32, "b1", true);
        s.order("oak_log", 16, "b1", true);
        s.order("stick", 4, "b1", true);
        NeedChain.mostWays(true);
        s.run(10, 1000);          // structural: ten passes a second apart
        assertTrue(s.sync.drops.isEmpty(), "no planner fault: " + s.sync.drops);
        // no way from down here (the walk up out, no pickaxe to be had): each order parks with that reason, said
        assertTrue(s.ordersLeft().stream().allMatch(g -> g.parked != null), "parked: " + s.brain.whyLines(6));
        assertTrue(s.brain.whyLines(6).toString().contains("no climb either: no pickaxe"), s.brain.whyLines(6).toString());
        int most = NeedChain.mostWays(false);
        assertTrue(most > 0 && most < NeedChain.maxWays() / 10, "ways a resolve: " + most + "; log " + s.port.log);
        assertTrue(s.port.log.stream().noneMatch(l -> l.contains(NeedChain.TOO_LARGE)), "never too large: " + s.port.log);
    }
}
