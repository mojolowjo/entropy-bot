package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.FastEnd;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.sense.Seen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 seen (owner 2026-10-26 ~17:05; to-do/candidate-ore-passed.md, runs BZ + CC: 24 veins near under-ground steps, 12
 * passed by, none priced within 90 s). One cause: the running step was never asked - Steps re-priced mid-step only the stand
 * and an explore with an until word, and a seen cell told nobody. Now a new cell of a family the run's steps need asks the
 * order's price where the step runs (SeenAsk -> FactChange.seen -> Steps.reprice(true) -> Goals.reprice): collect it now
 * against finishing the step, the cheaper runs (replan.switchMarginS), said once per vein. Folded in: "explore until
 * iron_ingot" reads its word through the furnace (CC 16:42:08), and the trip key's bag part holds only the counts its legs
 * reference (carry Report open (a)). Fakes: Z77PlaybookTest's (loot, strip band 0..31 at y 16, 256 ore a chunk slab: 16
 * blocks per ore, a tunnel cell 2 breaks; stone 0.6 s, iron ore 1.15 s with the stone pickaxe). Pure (JUnit). Loader notes: none.
 */
class Z77SeenTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    static final String IRON_ORE = Z77PlaybookTest.IRON_ORE;

    /** The loot data's source blocks of a need word (the adapter's sourceBlocksOf): raw iron from the iron ores. */
    static final Function<String, Set<String>> BLOCKS = w -> w.contains("raw_iron") ? Set.of(IRON_ORE, "minecraft:deepslate_iron_ore") : Set.of();

    /**
     * Under ground at {@code feet} (64 - y of ground over them), a stone pickaxe in the bag; the walk is Baritone's fake path: a
     * cell below the feet is the dig down ((feet y - y) x 0.6 s), a cell in {@code far} 40 blocks of tunnel (40 x 2 x 0.6 =
     * 48 s), anything else the straight metres / 4.317.
     */
    static World under(int[] feet, Map<String, List<Spot>> seen, Set<String> far) {
        PathPrice paths = (x, y, z, d) -> PathPrice.Answer.priced(new PathPrice.Walk(far.contains(x + " " + y + " " + z) ? 40 * 2 * 0.6
                : y < feet[1] ? (feet[1] - y) * 0.6 : d / Unit.WALK_BPS));
        return ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), false, false, seen).withMobs(x -> List.of(), Z77PlaybookTest.book()).withPath(paths)
                .withStrip(Z77PlaybookTest::strip)
                .withWhere(new NeedChain.WhereFacts(Z77PlaybookTest::where, () -> new NeedChain.Surface(64 - feet[1], 0, 64, 0, 64 - feet[1]), () -> feet.clone(),
                        () -> new GenWhere.Base(Z77PlaybookTest.STONE, Integer.MIN_VALUE, null, "test")));
    }

    /** One order (raw_iron 3) on a board whose world is {@code w[0]} (swapped by the test as the senses change). */
    static Blackboard board(FakePort port, World[] w, int[] feet, List<Goals.Goal> gs) {
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<World>) () -> w[0]);
        bb.in.bag = Map.of("minecraft:stone_pickaxe", 1);
        bb.in.pos = feet.clone();
        bb.in.underDepth = 64 - feet[1];
        gs.add(Goals.add(bb, Z77PlaybookTest.item("raw_iron"), 3, 0, clock.now()));
        return bb;
    }

    /** The order's run started on its trip, the walk down running (one tick). */
    static Steps walking(Blackboard bb, Goals.Goal g, Z48ScenarioTest.Hands h) {
        var rt = Goals.routine(bb, g);
        assertNotNull(rt.steps(), rt.why());
        assertTrue(rt.steps().get(0) instanceof Step.ToStrip t && t.y() == 16, "the run starts with the walk down to the band: " + rt.steps());
        Steps run = new Steps(h);
        run.start("goal.raw_iron", rt.steps(), rt.uses(), 0, Goals.replanner(bb, g));
        run.tick(1);
        assertEquals(List.of(rt.steps().get(0).text()), h.started);
        return run;
    }

    /** One AA tick of the seen trigger at {@code nowMs} with the new cells (AaRuntime.seenTick's call); the ask sent, null: none. */
    static FactChange.Fact see(SeenAsk a, Steps run, int[] feet, long nowMs, SeenAsk.Cell... cells) {
        return a.tick(run, List.of(cells), feet, BLOCKS, ExploreRules::smeltInputs, Seen.range(), nowMs, Replan.everyMs());
    }

    static SeenAsk.Cell iron(int x, int y, int z) { return new SeenAsk.Cell("iron", x, y, z); }

    static boolean offer(SeenAsk a, SeenAsk.Cell c, int[] feet, List<Step> steps, int from) {
        return a.offer(c, feet, steps, from, BLOCKS, ExploreRules::smeltInputs, Seen.range());
    }

    // ---- scenario 1: a walk under ground, iron seen 3 blocks off it - collect now wins ----

    /**
     * CC 16:39:47 ("seen iron at 16 51 -9", the walk passed it at 2.2 blocks and ended 39 blocks away): the order raw_iron 3
     * (its trip: descent to y 16, one strip) walks down from y 40: to y 16 = 24 x 0.6 = 14.4 s, the strip 3 ore x 16 blocks
     * = 48 cells x 2 breaks x 0.6 + 3 x 1.15 = 61.05 s: 75.45 s. A 3-block vein of iron is seen 3 blocks off: the cell asks
     * (the strip after the walk digs iron ore), the trip is priced again WHERE THE WALK RUNS - collect it now: 3 / 4.317 +
     * 3 x 1.15 = 4.14 s against 75 s left: cheaper by more than replan.switchMarginS (10), the walk is cancelled and the collect
     * runs; the owner hears the line once.
     */
    @Test
    void cc_ironSeen3BlocksOffTheWalk_collectNowWins_theWalkIsReplaced() {
        Z77PlaybookTest.data();
        int[] feet = {0, 40, 0};
        World[] w = {under(feet, Map.of(), Set.of())};
        FakePort port = new FakePort();
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = board(port, w, feet, gs);
        Goals.Goal g = gs.get(0);
        Z48ScenarioTest.Hands h = new Z48ScenarioTest.Hands();
        Steps run = walking(bb, g, h);
        assertTrue(g.playbookRun, "the order runs its trip");
        assertEquals(75.45, g.pricedS, 0.05);
        // the senses: a 3-block vein of iron 3 blocks off the walk
        w[0] = under(feet, Map.of("raw_iron", List.of(new Spot(3, 40, 0, 3, IRON_ORE, 3))), Set.of());
        SeenAsk a = new SeenAsk();
        FactChange.Fact f = see(a, run, feet, 0, iron(3, 40, 0));
        assertEquals("seen iron at 3 40 0 (3 away) for minecraft:iron_ore", f.text(), "the strip after the walk digs iron ore");
        run.tick(2);
        assertEquals(1, h.cancels, "the walk is cut where it runs: " + port.log);
        Step now = run.steps().get(run.index());
        assertTrue(now instanceof Step.Collect c && c.item().equals("raw_iron") && c.n() == 3 && c.at()[0] == 3, "the vein is collected now: " + run.steps());
        assertEquals(List.of("seen iron at 3 40 0: collect now (4 s) vs finish to y 16 (priced path) (75 s) - collect now"),
                port.told.stream().filter(l -> l.startsWith("seen ")).toList());
        assertNull(see(a, run, feet, 60_000, iron(4, 41, 0)), "a cell of the same vein: asked once");
        assertEquals(1, a.askedCount(), "its re-price ran: the vein is asked");
    }

    // ---- scenario 1b: the same, the iron 40 blocks of tunnel away - finish wins ----

    /**
     * The same walk; a 1-block vein is seen 20 blocks off, but its walk is 40 blocks of tunnel (48 s): the trip priced again
     * where the walk runs keeps the strip (75.45 s; the vein is in no cheaper way), the walk runs on - said once, and the
     * vein's other cells ask nothing.
     */
    @Test
    void cc_ironSeen40BlocksAway_finishWins_theWalkRunsOn_saidOnce() {
        Z77PlaybookTest.data();
        int[] feet = {0, 40, 0};
        World[] w = {under(feet, Map.of(), Set.of())};
        FakePort port = new FakePort();
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = board(port, w, feet, gs);
        Goals.Goal g = gs.get(0);
        Z48ScenarioTest.Hands h = new Z48ScenarioTest.Hands();
        Steps run = walking(bb, g, h);
        w[0] = under(feet, Map.of("raw_iron", List.of(new Spot(0, 40, 20, 20, IRON_ORE, 1))), Set.of("0 40 20"));
        SeenAsk a = new SeenAsk();
        FactChange.Fact f = see(a, run, feet, 0, iron(0, 40, 20));
        assertEquals("seen iron at 0 40 20 (20 away) for minecraft:iron_ore", f.text());
        run.tick(2);
        assertEquals(0, h.cancels, "the walk runs on: " + port.log);
        assertEquals(List.of(run.steps().get(0).text()), h.started, "never restarted");
        assertEquals(List.of("seen iron at 0 40 20: collect now (not in the cheapest way: 75 s without it) vs finish to y 16 (priced path) (75 s) - the step runs on (a seen vein switches only to collect it)"),
                port.told.stream().filter(l -> l.startsWith("seen ")).toList(), "said once");
        assertNull(see(a, run, feet, 60_000, iron(1, 40, 21)), "the same vein: asked once");
        run.tick(3);
        assertEquals(1, port.told.stream().filter(l -> l.startsWith("seen ")).count(), "said once");
    }

    // ---- the ask's own rules ----

    /** Copper no step needs, iron beyond the sight reach: no ask; iron for an explore until iron_ingot: asked (through the furnace). */
    @Test
    void theAsk_onlyAFamilyTheRunNeeds_withinSight() {
        Z77PlaybookTest.data();
        List<Step> steps = List.of(new Step.ToStrip(0, 16, 0, "strip"), new Step.Strip(List.of(IRON_ORE), Map.of("iron", 3), 16, "east", 0));
        SeenAsk a = new SeenAsk();
        int[] feet = {0, 40, 0};
        assertFalse(offer(a, new SeenAsk.Cell("copper", 2, 40, 0), feet, steps, 0), "no step needs copper");
        assertFalse(offer(a, iron(30, 40, 0), feet, steps, 0), "beyond the sight reach (seen.range 24)");
        assertFalse(offer(a, iron(2, 40, 0), feet, steps, 2), "no step left that needs it");
        assertTrue(offer(a, iron(2, 40, 0), feet, steps, 0));
        assertFalse(offer(a, iron(3, 41, 0), feet, steps, 0), "its vein waits already");
        List<Step> explore = List.of(new Step.Explore(5, "minecraft:iron_ingot"));
        assertEquals("minecraft:raw_iron", SeenAsk.wants("iron", explore, 0, BLOCKS, ExploreRules::smeltInputs), "iron_ingot <- raw_iron <- iron ore");
        assertNull(SeenAsk.wants("iron", explore, 0, BLOCKS, w -> List.of()), "was: the ingot itself, no block gives it");
    }

    /**
     * The vein's walk not priced yet (CC 16:41:51: "walk: path not known yet (computing)"): the seen ask waits - the walk runs
     * on, and the next landed path asks the seen fact again (never overwritten by the landing); the priced answer switches.
     * A seen fact on a step that does not move (a craft) waits for the step boundary.
     */
    @Test
    void theAsk_waitsOnTheVeinsWalk_askedAgainOnTheNextLanding() {
        Z48ScenarioTest.Hands h = new Z48ScenarioTest.Hands();
        Steps run = new Steps(h);
        Step down = new Step.ToStrip(0, 16, 0, "strip"), strip = new Step.Strip(List.of(IRON_ORE), Map.of("iron", 3), 16, "east", 0);
        Step collect = new Step.Collect("raw_iron", 3, new int[]{3, 40, 0});
        List<FactChange.Fact> asked = new ArrayList<>();
        List<Steps.Routine> answers = new ArrayList<>(List.of(new Steps.Routine(null, FactChange.SEEN_WAITS + "seen iron at 3 40 0"), new Steps.Routine(List.of(collect), null)));
        run.start("goal.raw_iron", List.of(down, strip), 0, (failed, fact, why) -> { asked.add(fact); return answers.remove(0); });
        run.tick(1);
        FactChange.Fact seen = FactChange.seen("iron", 3, 40, 0, "seen iron at 3 40 0 (3 away) for minecraft:iron_ore");
        run.repriceAtNextStep(seen);
        run.repriceAtNextStep(FactChange.priced("walk to 9 9 9: 3 s, complete"));       // a landing before the tick: the seen ask stands
        run.tick(2);
        assertEquals(List.of(seen), asked);
        assertEquals(0, h.cancels, "waits on the vein's walk: the walk runs on");
        run.repriceAtNextStep(FactChange.priced("walk to 3 40 0: 1 s, complete"));       // the vein's walk landed
        run.tick(3);
        assertEquals(List.of(seen, seen), asked, "the landing asks the seen fact again");
        assertEquals(1, h.cancels);
        assertEquals(collect.text(), h.started.get(h.started.size() - 1));
        // a craft is not cut: the seen fact waits for the boundary
        Z48ScenarioTest.Hands h2 = new Z48ScenarioTest.Hands();
        Steps two = new Steps(h2);
        Step craft = new Step.Craft("minecraft:stick", 4);
        two.start("goal.raw_iron", List.of(craft, down), 0, (failed, fact, why) -> new Steps.Routine(List.of(collect), null));
        two.repriceAtNextStep(seen);
        two.tick(1);
        assertEquals(0, h2.cancels, "the craft is not cut");
        h2.done = craft.text();
        two.tick(2);
        assertEquals(List.of(craft.text(), collect.text()), h2.started, "priced at the boundary");
    }

    // ---- seen 2 (review): no burst of re-prices; a vein asked only when its re-price ran, per run ----

    /** The walk down and the strip of iron after it, every re-price kept (the walk runs on); {@code asked} counts the re-prices. */
    static Steps stripRun(Z48ScenarioTest.Hands h, List<FactChange.Fact> asked, String key) {
        Steps run = new Steps(h);
        run.start(key, List.of(new Step.ToStrip(0, 16, 0, "strip"), new Step.Strip(List.of(IRON_ORE), Map.of("iron", 3), 16, "east", 0)), 0,
                (failed, fact, why) -> { asked.add(fact); return new Steps.Routine(null, "kept the running pick"); });
        run.tick(1);
        return run;
    }

    /**
     * Review item 1 (a cave burst: N veins, N whole re-prices - BatchWay.forget, the chain and its path asks - on N ticks in a
     * row): 6 distinct veins seen on 6 AA ticks (250 ms apart) - one re-price for the first; the 5 seen meanwhile wait and go
     * together in ONE more ask, replan.everyMs (5 s, the planner's rolling re-score pace) after the first: 2 re-prices, not 6.
     */
    @Test
    void review1_aBurstOf6VeinsIn6Ticks_twoReprices() {
        Z48ScenarioTest.Hands h = new Z48ScenarioTest.Hands();
        List<FactChange.Fact> asked = new ArrayList<>();
        Steps run = stripRun(h, asked, "goal.raw_iron");
        SeenAsk a = new SeenAsk();
        int[] feet = {0, 40, 0};
        long t = 0;
        for (int i = 0; i < 6; i++, t += 250) {
            see(a, run, feet, t, iron(4 * i - 10, 40, 5));          // 4 blocks apart: 6 veins
            run.tick(2 + i);
        }
        assertEquals(1, asked.size(), "the burst: one re-price, the rest wait: " + asked);
        assertEquals(5, a.waiting());
        for (int k = 10; t <= Replan.everyMs() + 1000; t += 250, k++) { see(a, run, feet, t); run.tick(k); }
        assertEquals(2, asked.size(), "one more for the 5 that waited: " + asked);
        assertTrue(asked.get(1).text().endsWith("; 4 more veins seen"), asked.get(1).text());
        assertEquals(6, a.askedCount(), "every vein's re-price ran");
        assertEquals(0, h.cancels, "the walk runs on");
    }

    /**
     * Review item 2: a vein asked for order A asks again for order B (a new run clears the marks); a vein whose ask was
     * overwritten before its re-price ran (another seen fact in Steps' one slot) waits again and asks on the next turn.
     */
    @Test
    void review2_aVeinAsksAgainForTheNextOrder_andAfterItsAskWasOverwritten() {
        Z48ScenarioTest.Hands h = new Z48ScenarioTest.Hands();
        List<FactChange.Fact> asked = new ArrayList<>();
        Steps run = stripRun(h, asked, "goal.iron_helmet");
        SeenAsk a = new SeenAsk();
        int[] feet = {0, 40, 0};
        assertNotNull(see(a, run, feet, 0, iron(3, 40, 0)));
        run.tick(2);
        assertNull(see(a, run, feet, 10_000, iron(3, 40, 0)), "order A: asked");
        assertEquals(1, asked.size());
        // order B: a new run on the same Steps
        run.start("goal.iron_boots", List.of(new Step.ToStrip(0, 16, 0, "strip"), new Step.Strip(List.of(IRON_ORE), Map.of("iron", 3), 16, "east", 0)), 0,
                (failed, fact, why) -> { asked.add(fact); return new Steps.Routine(null, "kept the running pick"); });
        run.tick(3);
        FactChange.Fact b = see(a, run, feet, 20_000, iron(3, 40, 0));
        assertNotNull(b, "order B: the same vein asks again");
        assertArrayEquals(new int[]{3, 40, 0}, FactChange.seenCell(b));
        // overwritten: B's ask is replaced in Steps' slot by another seen fact before its re-price runs
        FactChange.Fact other = FactChange.seen("iron", 9, 40, 9, "seen iron at 9 40 9 (another asker)");
        run.repriceAtNextStep(other);
        run.tick(4);
        assertSame(other, asked.get(asked.size() - 1), "the other fact was priced, not B's");
        assertNull(see(a, run, feet, 20_250), "within the pace: none");
        assertEquals(1, a.waiting(), "B's vein waits again: its re-price never ran");
        FactChange.Fact again = see(a, run, feet, 20_000 + Replan.everyMs());
        assertNotNull(again, "asked on the next turn");
        assertArrayEquals(new int[]{3, 40, 0}, FactChange.seenCell(again));
        run.tick(5);
        assertSame(again, asked.get(asked.size() - 1));
        see(a, run, feet, 40_000);
        assertEquals(1, a.askedCount(), "now its re-price ran");
    }

    // ---- seen 3 (manager 2026-10-26 ~21:20, run CU): a vein asks only for an item still lacking, never while nothing walks ----

    static final String RAW = "minecraft:raw_iron";

    /** A run of {@code steps} with the plan's counts {@code uses}, every re-price kept; {@code asked} counts the re-prices. */
    static Steps planRun(Z48ScenarioTest.Hands h, List<FactChange.Fact> asked, List<Step> steps, List<Map<String, Integer>> uses) {
        Steps run = new Steps(h);
        run.start("goal.iron_helmet", steps, uses, 0, (failed, fact, why) -> { asked.add(fact); return new Steps.Routine(null, "kept the running pick"); });
        run.tick(1);
        return run;
    }

    /** {@code ticks} AA ticks 250 ms apart from {@code t0}, the vein {@code c} seen on the first; the asks sent. */
    static int seeFor(SeenAsk a, Steps run, long t0, int ticks, SeenAsk.Cell c, Map<String, Integer> bag, Map<String, Integer> furnace) {
        int sent = 0;
        for (int i = 0; i < ticks; i++) {
            List<SeenAsk.Cell> fresh = i == 0 ? List.of(c) : List.of();
            if (a.tick(run, fresh, new int[]{0, 26, 0}, BLOCKS, ExploreRules::smeltInputs, Seen.range(), t0 + 250L * i, Replan.everyMs(), bag, furnace) != null) sent++;
            run.tick(2 + i);
        }
        return sent;
    }

    /**
     * Run CU 21:00-21:05 (the slowtick probe "job smelting 8 iron"; 48 trip re-prices in 5 min): the smelt of 8 iron ingots
     * runs at the furnace here, the trip's later leg still strips iron (7 more raw iron) - a vein seen while the smelt runs
     * sends no ask (the smelt needs no walk: nothing to interrupt). The plan then walks to the furnace with its 8 raw iron
     * already inside (the furnace job's input) and a collect of 8 left in the plan: the run lacks no raw iron (needs 8, the
     * furnace holds 8) - the vein is a source, no re-price, said once. Was: one whole re-price per ask.
     */
    @Test
    void cu_rawIronInTheFurnace_aVeinSeen_noReprice() {
        Z77PlaybookTest.data();
        Z48ScenarioTest.Hands h = new Z48ScenarioTest.Hands();
        List<FactChange.Fact> asked = new ArrayList<>();
        Step smelt = new Step.Smelt("minecraft:iron_ingot", 8, true), helmet = new Step.Craft("minecraft:iron_helmet", 1);
        Step down = new Step.ToStrip(0, 16, 0, "strip"), strip = new Step.Strip(List.of(IRON_ORE), Map.of("iron", 7), 16, "east", 0);
        Step smelt7 = new Step.Smelt("minecraft:iron_ingot", 7, true);
        Steps atFurnace = planRun(h, asked, List.of(smelt, helmet, down, strip, smelt7),
                java.util.Arrays.asList(Map.of(RAW, 8), Map.of("minecraft:iron_ingot", 5), null, null, Map.of(RAW, 7)));
        SeenAsk a = new SeenAsk();
        assertEquals(0, seeFor(a, atFurnace, 0, 30, iron(2, 26, 3), Map.of(), Map.of(RAW, 8)), "the smelt runs: no ask in 7 s");
        assertEquals(0, asked.size());
        assertEquals(1, a.waiting(), "the vein waits (the trip's later strip still lacks 7)");
        // the walk to the furnace, its 8 raw iron in it already: covered
        Step walk = new Step.Walk(2, 26, -15, 1), collect = new Step.Collect("raw_iron", 8, new int[]{4, 57, -15});
        Z48ScenarioTest.Hands h2 = new Z48ScenarioTest.Hands();
        List<FactChange.Fact> asked2 = new ArrayList<>();
        Steps walking = planRun(h2, asked2, List.of(walk, collect, smelt, helmet), java.util.Arrays.asList(null, null, Map.of(RAW, 8), Map.of("minecraft:iron_ingot", 5)));
        SeenAsk b = new SeenAsk();
        assertEquals(0, seeFor(b, walking, 0, 30, iron(2, 26, 3), Map.of(), Map.of(RAW, 8)), "needs 8, the furnace holds 8: no ask");
        assertEquals(0, asked2.size(), "no re-price");
        assertEquals(List.of("seen iron at 2 26 3: a source, no re-price - the run lacks no iron (needs 8, the bag and the furnaces hold 8)"), b.notes(), "said once");
        assertEquals(0, b.waiting());
        assertEquals(0, h2.cancels, "the walk runs on");
    }

    /** Control: the same walk, the run still lacking 3 raw iron (5 in the bag, the furnace empty) - the ask fires as before. */
    @Test
    void cu_control_theRunStillLacks3RawIron_walking_theAskFires() {
        Z77PlaybookTest.data();
        Z48ScenarioTest.Hands h = new Z48ScenarioTest.Hands();
        List<FactChange.Fact> asked = new ArrayList<>();
        Step walk = new Step.Walk(2, 26, -15, 1), collect = new Step.Collect("raw_iron", 3, new int[]{4, 57, -15});
        Step smelt = new Step.Smelt("minecraft:iron_ingot", 8, true), helmet = new Step.Craft("minecraft:iron_helmet", 1);
        Steps walking = planRun(h, asked, List.of(walk, collect, smelt, helmet), java.util.Arrays.asList(null, null, Map.of(RAW, 8), Map.of("minecraft:iron_ingot", 5)));
        assertArrayEquals(new int[]{8, 5}, SeenAsk.lack("iron", walking.steps(), 0, walking::usesOf, Map.of(RAW, 5), Map.of(), BLOCKS), "needs 8, holds 5: lacks 3");
        SeenAsk a = new SeenAsk();
        assertEquals(1, seeFor(a, walking, 0, 30, iron(2, 26, 3), Map.of(RAW, 5), Map.of()), "one ask");
        assertEquals(1, asked.size(), "one re-price");
        assertEquals("seen iron at 2 26 3 (4 away) for minecraft:raw_iron", asked.get(0).text());
        assertTrue(a.notes().isEmpty());
    }

    // ---- seen 4 (manager 2026-10-27 ~00:55, run DA): a seen switch is a switch to collect the vein ----

    /**
     * Run DA 00:34:32 ("seen iron at 33 54 -12: collect now (not in the cheapest way: 742 s without it) vs finish explore 5
     * until raw_iron (415 s) - switched to collect raw_iron 19" - a spot 58 m off, not the vein; 00:30:22 the same shape
     * with the known cave): the order raw_iron 3 explores (no strip way here, no source known). A vein is seen 19 blocks off,
     * its walk 40 blocks of tunnel; with it a 3-block spot 5 blocks off becomes known too. The re-price's cheapest way digs
     * the near spot, not the vein - by the z49 rule a priced way over an explore would switch at any price; the seen ask's
     * question was the vein, so the explore runs on, said once. The near spot is the explore's own find (its until check).
     */
    @Test
    void da_aSeenVeinNotInTheCheapestWay_theExploreRunsOn() {
        Z77PlaybookTest.data();
        int[] feet = {0, 40, 0};
        World[] w = {under(feet, Map.of(), Set.of()).withStrip(x -> null)};
        FakePort port = new FakePort();
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = board(port, w, feet, gs);
        Goals.Goal g = gs.get(0);
        var rt = Goals.routine(bb, g);
        assertNotNull(rt.steps(), rt.why());
        assertTrue(rt.steps().get(0) instanceof Step.ToSurface && rt.steps().get(1) instanceof Step.Explore ex && ex.until() != null,
                "the order goes up and explores (DA: climb, explore 5 until raw_iron): " + rt.steps());
        assertTrue(g.pricedExplore, "its pick ends in an explore");
        Z48ScenarioTest.Hands h = new Z48ScenarioTest.Hands();
        Steps run = new Steps(h);
        run.start("goal.raw_iron", rt.steps(), rt.uses(), 0, Goals.replanner(bb, g));
        h.done = rt.steps().get(0).text();
        run.tick(1);
        assertTrue(run.steps().get(run.index()) instanceof Step.Explore, "the explore runs: " + h.started);
        w[0] = under(feet, Map.of("raw_iron", List.of(new Spot(5, 40, 0, 5, IRON_ORE, 3), new Spot(0, 40, 19, 19, IRON_ORE, 1))), Set.of("0 40 19")).withStrip(x -> null);
        SeenAsk a = new SeenAsk();
        FactChange.Fact f = see(a, run, feet, 0, iron(0, 40, 19));
        assertEquals("seen iron at 0 40 19 (19 away) for raw_iron", f.text(), "the explore until raw_iron needs iron");
        run.tick(2);
        assertEquals(0, h.cancels, "the explore runs on: " + port.told);
        // the cheapest way: the near spot, 5 / 4.317 + 3 x 1.15 = 4.6 s; the vein 19 m: 48 s of tunnel + 1.15 (dropped, dearer)
        assertEquals(List.of("seen iron at 0 40 19: collect now (not in the cheapest way: 5 s without it) vs finish explore 5 until raw_iron (456 s) - the step runs on (a seen vein switches only to collect it)"),
                port.told.stream().filter(l -> l.startsWith("seen ")).toList(), "said once");
        assertTrue(Goals.switches(true, 456, true, true, 742, false, Goals.switchMarginS()), "was: the z49 rule switches a priced way over an explore at any price (DA: 742 s over 415 s)");
        // the near spot is the explore's own find (Mining's until check, FactChange.found): that path switches, as before
        run.repriceAtNextStep(FactChange.found("a source of raw_iron is known: iron_ore at 5 40 0 (5 away)"));
        run.tick(3);
        assertEquals(1, h.cancels, "the explore's find switches it: " + port.log);
        assertTrue(run.steps().get(run.index()) instanceof Step.Collect c && c.at()[0] == 5, run.steps().toString());
    }

    // ---- folded in: explore until iron_ingot ----

    /**
     * CC 16:42:08 ("explore: for iron_ingot: ... no block gives it", the iron ore at 45 24 -21, 3.2 blocks off, passed): the
     * until word is read through the furnace's recipes - iron_ingot <- raw_iron (the test data's smelt) <- iron ore (the loot
     * data) - so the iron ore sensed ends the explore (its order priced again on it, as any find).
     */
    @Test
    void cc_exploreUntilIronIngot_ironOreSensed_theExploreEnds() {
        Z77PlaybookTest.data();
        String until = ExploreRules.throughSmelts("minecraft:iron_ingot", ExploreRules::smeltInputs);
        assertTrue(until.startsWith("minecraft:iron_ingot|") && until.contains("minecraft:raw_iron"), until);
        Function<String, Spot> source = word -> BLOCKS.apply(word).isEmpty() ? null : new Spot(45, 24, -21, 3.2, IRON_ORE);
        assertNull(ExploreRules.untilFound("minecraft:iron_ingot", source), "was: no block gives it, the explore ran on");
        String known = ExploreRules.untilFound(until, source);
        assertEquals("a source of minecraft:raw_iron is known: iron_ore at 45 24 -21 (3 away); " + FastEnd.FACT_ARRIVED, known);
        assertTrue(ExploreRules.untilEnds(known, null, 1), "the explore ends");
        assertEquals("#minecraft:coals", ExploreRules.throughSmelts("#minecraft:coals", ExploreRules::smeltInputs), "a tag: as it is");
    }

    // ---- folded in: the trip key's bag part ----

    /**
     * Carry Report open (a) (CB 16:04, CE 16:19-16:20: digging walks gained cobblestone or deepslate every block or two, the
     * whole bag a key fact): the kit's six orders, the bot digging down 20 Y under ground (y 40 to 20) gaining 20
     * cobblestone, nothing else changing - priced once (was: once per block of stone gained); a referenced count changing
     * (the coal the torches and the smelts burn: 9 -> 10) - one re-price.
     */
    @Test
    void cb_digging20YGaining20Cobblestone_noReprice_aReferencedCountChanging_oneReprice() {
        Z77PlaybookTest.data();
        int[] feet = {0, 40, 0};
        Map<String, Integer> bag = new HashMap<>(Z77PlaybookTest.kitBag(9));
        World[] w = {Z77CarryTest.moving(feet)};
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = Z77PlaybookTest.batchBoard(new FakePort(), new Clock.Fake(10_000_000), () -> w[0], gs);
        for (int y = 40; y >= 20; y--) {
            feet[1] = y;
            bag.put("minecraft:cobblestone", 40 - y);
            w[0] = Z77CarryTest.moving(feet).withBag(Map.copyOf(bag));
            bb.in.pos = new int[]{0, y, 0};
            bb.in.underDepth = 64 - y;
            for (Goals.Goal g : gs) Goals.chain(bb, g);
        }
        assertEquals(1, BatchWay.priced("b1"), "20 Y down, 20 cobblestone gained, nothing the trip references changed: one price");
        bag.put("minecraft:coal", 10);
        w[0] = Z77CarryTest.moving(feet).withBag(Map.copyOf(bag));
        for (Goals.Goal g : gs) Goals.chain(bb, g);
        assertEquals(2, BatchWay.priced("b1"), "the coal it burns changed: one re-price");
        for (Goals.Goal g : gs) Goals.chain(bb, g);
        assertEquals(2, BatchWay.priced("b1"), "kept");
    }
}
