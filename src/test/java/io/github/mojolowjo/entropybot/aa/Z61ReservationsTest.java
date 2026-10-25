package io.github.mojolowjo.entropybot.aa;

import baritone.api.process.Reserves;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.DryRun;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Reservations;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z61 (design D1 J2, docs/designs/D1-reservations.md section 6; owner's rulings: a finished order's items are safe from
 * pillaring but usable as ingredients, option c): the run's counted reserves, the plan's own counts carried, the dry run at
 * each step start, release before repair, the orders' reserves. Run P z56 23:48:55: the keep list held every stone member
 * whole with 103 cobblestone in the bag, so no pillar was priced. Hand-computed numbers; the lease math is the fork's
 * {@link Reserves}. No game: the planner on test data, the step machine on fake hands.
 */
class Z61ReservationsTest {
    static final String COBBLE = "minecraft:cobblestone", STICK = "minecraft:stick", TOOLS = "#minecraft:stone_tool_materials",
            CRAFTING = "#minecraft:stone_crafting_materials";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    /** The game's stone recipes by their tags: stone pickaxe 3 of the tool tag + 2 sticks, furnace 8 of the crafting tag; vanilla burn times. */
    static TestData stoneGame() {
        TestData d = TestData.vanilla();
        for (String s : List.of("cobblestone", "blackstone", "cobbled_deepslate")) d.tag(s, "minecraft:stone_crafting_materials");
        d.recipe("stone_pickaxe", 1, TOOLS, TOOLS, TOOLS, "stick", "stick");
        String[] in = new String[8];
        java.util.Arrays.fill(in, CRAFTING);
        d.recipe("furnace", 1, in);
        d.smelt("iron_ingot", "raw_iron");
        d.fuel("coal", 1600).fuel("stick", 100).fuel("oak_planks", 300);
        return d.install();
    }

    /** Run P's remaining chain: craft stone_pickaxe, craft furnace, place furnace, smelt iron_ingot 3. */
    static List<Step> runPChain() {
        return List.of(new Step.Craft("stone_pickaxe", 1), new Step.Craft("furnace", 1), new Step.PlaceNear("furnace"), new Step.Smelt("iron_ingot", 3, true));
    }

    static List<StepNeeds> needs(List<Step> steps) {
        List<StepNeeds> out = new ArrayList<>();
        for (Step s : steps) out.add(StepNeeds.of(s));
        return out;
    }

    static List<String> texts(List<Reserves.Reserve<String>> rs) {
        List<String> out = new ArrayList<>();
        for (var r : rs) out.add(Reservations.text(r));
        return out;
    }

    // ---- S-R1 / S-R2 / S-R9: the counts ----

    /**
     * S-R1 by hand: reserves tool materials 3, stick 2, crafting materials 8, furnace 1, raw_iron 3, fuel 600 ticks (3 x 200
     * craftJob.smeltTicks). Bag cobblestone 103, stick 3, raw_iron 3, coal 1: the stone reserves take 3 + 8 = 11 of the
     * cobblestone (its only member in the bag), so 103 - 11 = 92 may be placed.
     */
    @Test
    void sR1_runPReservesElevenStoneAndLeavesNinetyTwoToPlace() {
        stoneGame();
        List<Step> steps = runPChain();
        Reservations r = Reservations.build(steps, 0, needs(steps), List.of());
        assertEquals(List.of("#stone_tool_materials 3 (craft stone_pickaxe 1)", "stick 2 (craft stone_pickaxe 1)",
                "#stone_crafting_materials 8 (craft furnace 1)", "furnace 1 (" + steps.get(2).text() + ")", "raw_iron 3 (smelt iron_ingot 3 here)",
                "fuel 600 ticks (smelt iron_ingot 3 here)").stream().sorted().toList(), texts(r.list()).stream().sorted().toList());
        assertNull(r.unknown());
        Map<String, Integer> bag = Map.of(COBBLE, 103, STICK, 3, "minecraft:raw_iron", 3, "minecraft:coal", 1);
        assertFalse(Reserves.feasible(r.list(), bag), "the furnace is made by step 2: its reserve holds nothing now, the stone ones still count");
        assertEquals(92, Reserves.spareThrowaway(r.list(), bag, List.of(COBBLE, "minecraft:dirt")));
        assertEquals(92, Reserves.spare(r.list(), bag, COBBLE));
        assertEquals(1, Reserves.spare(r.list(), bag, STICK), "3 sticks, 2 reserved");
    }

    /** S-R2 by hand: cobblestone 9 under the 11 the stone reserves need: none to place; the dry run finds step 2 short (9 - 3 = 6 of 8). */
    @Test
    void sR2_nineCobblestoneIsNoneToSpareAndTheDryRunFindsTheFurnaceShort() {
        stoneGame();
        List<Step> steps = runPChain();
        Reservations r = Reservations.build(steps, 0, needs(steps), List.of());
        Map<String, Integer> bag = Map.of(COBBLE, 9, STICK, 2, "minecraft:raw_iron", 3, "minecraft:coal", 1);
        assertEquals(0, Reserves.spareThrowaway(r.list(), bag, List.of(COBBLE)));
        DryRun.Shortage s = DryRun.check(steps, 0, needs(steps), bag);
        assertNotNull(s);
        assertEquals(1, s.step());
        assertEquals("step 2 (craft furnace 1) needs 8 of stone_crafting_materials; the bag will hold 6 after step 1", s.text());
        assertEquals("bag stone_crafting_materials", s.key());
        assertNull(DryRun.check(steps, 0, needs(steps), Map.of(COBBLE, 11, STICK, 2, "minecraft:raw_iron", 3, "minecraft:coal", 1)), "11 is enough");
    }

    /** S-R9: run O's good behaviour at 8 (nothing to place), run N's walk may pillar what is over 8 (12 of 20). */
    @Test
    void sR9_parityWithRunsNAndO() {
        stoneGame();
        List<Step> steps = List.of(new Step.Craft("furnace", 1));
        Reservations r = Reservations.build(steps, 0, needs(steps), List.of());
        assertEquals(0, Reserves.spareThrowaway(r.list(), Map.of(COBBLE, 8), List.of(COBBLE)));
        assertEquals(12, Reserves.spareThrowaway(r.list(), Map.of(COBBLE, 20), List.of(COBBLE)));
    }

    /** S-R4: smelt 3 burns 600 ticks; oak_planks 4 (300 each) and no other fuel: 2 may leave, the third may not. */
    @Test
    void sR4_fuelByBurnTicks() {
        stoneGame();
        List<Step> steps = List.of(new Step.Smelt("iron_ingot", 3, true));
        Reservations r = Reservations.build(steps, 0, needs(steps), List.of());
        Map<String, Integer> bag = new HashMap<>(Map.of("minecraft:oak_planks", 4, "minecraft:raw_iron", 3));
        assertEquals(2, Reserves.spare(r.list(), bag, "minecraft:oak_planks"));
        bag.put("minecraft:oak_planks", 2);
        assertEquals(0, Reserves.spare(r.list(), bag, "minecraft:oak_planks"));
    }

    /** A shared planks input: sticks (2 of #planks) and a table (4 of #planks) from one bag of 6 planks, two kinds: none to spare; 7: one. */
    @Test
    void aSharedPlanksInputIsCountedOnceForBoth() {
        TestData d = TestData.vanilla();
        d.recipe("stick", 4, "#minecraft:planks", "#minecraft:planks").recipe("crafting_table", 1, "#minecraft:planks", "#minecraft:planks", "#minecraft:planks", "#minecraft:planks");
        d.install();
        List<Step> steps = List.of(new Step.Craft("stick", 4), new Step.Craft("crafting_table", 1));
        Reservations r = Reservations.build(steps, 0, needs(steps), List.of());
        assertEquals(List.of("#planks 2 (craft stick 4)", "#planks 4 (craft crafting_table 1)"), texts(r.list()));
        Map<String, Integer> bag = new HashMap<>(Map.of("minecraft:oak_planks", 4, "minecraft:birch_planks", 2));
        assertEquals(0, Reserves.spare(r.list(), bag, "minecraft:oak_planks"));
        assertEquals(0, Reserves.spare(r.list(), bag, "minecraft:birch_planks"));
        bag.put("minecraft:birch_planks", 3);
        assertEquals(1, Reserves.spare(r.list(), bag, "minecraft:oak_planks"), "either kind: one over the 6 both need");
        assertEquals(1, Reserves.spare(r.list(), bag, "minecraft:birch_planks"));
        assertNull(DryRun.check(steps, 0, needs(steps), bag));
    }

    // ---- S-R10: the plan's own counts carried ----

    static World world(Map<String, Integer> bag, boolean tableNear) {
        return FakePaths.paths(new World(new HashMap<>(bag), tableNear, false, w -> List.of(), null, null, null, 0, null,
                k -> tableNear && ItemWorth.CRAFT.equals(k) ? new Spot(2, 64, 0, 2, "minecraft:crafting_table") : null));
    }

    static int used(Chain c, String word) {
        int n = 0;
        for (var u : c.uses()) if (u != null) n += u.getOrDefault(word, 0);
        return n;
    }

    /**
     * S-R10 by hand: a wooden pickaxe from 3 oak logs, no table near: planks 3 (pickaxe) + 2 (sticks) + 4 (table) = 9 from
     * 3 logs (4 planks a log, 12 made). Chain.uses carries the simulation's takes per step; the logs and the planks are
     * reserved at the start (logs 3 until the plank crafts run).
     */
    @Test
    void sR10_planUsesAreTheSimulatedTakes() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("wooden_pickaxe", 1, world(Map.of("minecraft:oak_log", 3), false));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(c.steps().size(), c.uses().size(), "parallel to the steps");
        assertEquals(9, used(c, "minecraft:oak_planks"), c.steps() + " " + c.uses());
        assertEquals(3, used(c, "minecraft:oak_log"), c.steps() + " " + c.uses());
        for (int i = 0; i < c.steps().size(); i++)
            if (!(c.steps().get(i) instanceof Step.Craft || c.steps().get(i) instanceof Step.Smelt)) assertNull(c.uses().get(i), c.steps().get(i).text());
        List<StepNeeds> n = new ArrayList<>();
        for (int i = 0; i < c.steps().size(); i++) n.add(StepNeeds.of(c.steps().get(i)).withUses(c.uses().get(i)));
        Reservations r = Reservations.build(c.steps(), 0, n, List.of());
        int logs = 0, planks = 0;
        for (var x : r.list()) { if (x.word.equals("minecraft:oak_log")) logs += x.units; if (x.word.equals("minecraft:oak_planks")) planks += x.units; }
        assertEquals(3, logs);
        assertEquals(9, planks);
        assertNull(DryRun.check(c.steps(), 0, n, Map.of("minecraft:oak_log", 3)), "the plan's own bag passes its dry run");
    }

    /** A step the run is started with that has no fake bag: the fake hands record what the lease got. */
    static class Hands implements Steps.Hands {
        final Map<String, Integer> bag = new LinkedHashMap<>();
        final List<List<Reserves.Reserve<String>>> pushed = new ArrayList<>();
        List<Reserves.Reserve<String>> orders = List.of();
        final List<String> started = new ArrayList<>();
        Car.Status status = Car.Status.running("working");
        FactChange.Fact fact;

        @Override public String start(Step s) { started.add(s.text()); return null; }
        @Override public Car.Status status(Step s) { return status; }
        @Override public void cancel(String why) {}
        @Override public FactChange.Fact lastFact() { FactChange.Fact f = fact; fact = null; return f; }
        @Override public void reserve(List<Reserves.Reserve<String>> r) { pushed.add(r); }
        @Override public List<Reserves.Reserve<String>> orderReserves() { return orders; }
        @Override public StepNeeds.Bag bag() {
            return new StepNeeds.Bag() {
                @Override public int bestTier(String kind) { return 9; }          // a pickaxe of any tier: the tool checks pass
                @Override public String bestTool(String kind) { return "minecraft:diamond_pickaxe"; }
                @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                @Override public int count(String id) { return bag.getOrDefault(id, 0); }
                @Override public Map<String, Integer> items() { return bag; }
            };
        }

        List<Reserves.Reserve<String>> last() { return pushed.get(pushed.size() - 1); }
    }

    /**
     * Run P's chain from the planner: a stone pickaxe needs 3 cobblestone + 2 sticks, the bag holds 103 cobblestone: the
     * plan's counts go to the lease at the step's start, 100 may be placed.
     */
    @Test
    void runPTheStonePickaxeChainLeavesOneHundredToPlace() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of(COBBLE, 103, STICK, 2, "minecraft:wooden_pickaxe", 1);
        Chain c = NeedChain.resolve("stone_pickaxe", 1, world(bag, true));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(3, used(c, COBBLE), c.uses().toString());
        assertEquals(2, used(c, STICK), c.uses().toString());
        Hands h = new Hands();
        h.bag.putAll(bag);
        Steps run = new Steps(h);
        run.start("owner goal", c.steps(), c.uses(), 0, null);
        assertTrue(run.running(), run.why());
        assertEquals(100, Reserves.spareThrowaway(h.last(), bag, List.of(COBBLE, "minecraft:dirt")), texts(h.last()).toString());
        assertTrue(run.reservesText().contains("cobblestone 3 (craft stone_pickaxe 1)"), run.reservesText());
    }

    // ---- the dry run in the step machine, release before repair (S-R2, S-R7) ----

    /**
     * S-R2 in the run: the dry run finds step 2 short before step 1 starts; the order re-plans at once with the lease
     * holding only the order's reserve (release before repair), the re-plan line says what was released; the new chain
     * passes its dry run and its table is pushed at its start.
     */
    @Test
    void sR2_theDryRunReplansBeforeTheStepRunsAndReleasesFirst() {
        stoneGame();
        Hands h = new Hands();
        h.bag.putAll(Map.of(COBBLE, 9, STICK, 2));
        h.orders = List.of(Reservations.order("furnace", List.of("minecraft:furnace"), 1));
        List<Step> first = List.of(new Step.Craft("stone_pickaxe", 1), new Step.Craft("furnace", 1));
        List<List<Reserves.Reserve<String>>> heldAtReplan = new ArrayList<>();
        FactChange.Fact[] seen = new FactChange.Fact[1];
        Steps run = new Steps(h);
        run.start("owner goal", first, 0, (failed, fact, why) -> {
            heldAtReplan.add(h.last());
            seen[0] = fact;
            return new Steps.Routine(List.of(new Step.Collect("cobblestone", 2), new Step.Craft("stone_pickaxe", 1), new Step.Craft("furnace", 1)), null);
        });
        // 0.39.z62 item 3a: the shortage is looked at again one tick later before it re-plans (a bag a tick behind a pickup)
        assertEquals(0, heldAtReplan.size(), "held one tick first");
        assertEquals(List.of(), h.started);
        assertTrue(run.why().contains("looking again next tick"), run.why());
        run.tick(1);
        assertEquals(1, heldAtReplan.size());
        assertEquals(List.of("furnace 1 (order furnace)"), texts(heldAtReplan.get(0)), "only the order's reserve while it re-plans");
        assertEquals("bag stone_crafting_materials", seen[0].key());
        assertEquals(List.of("collect cobblestone 2"), h.started, "the short chain never started a step");
        assertTrue(run.running(), run.why());
        assertTrue(run.why().contains("re-planned: step 2 (craft furnace 1) needs 8 of stone_crafting_materials; the bag will hold 6 after step 1; released 3 reserves of the failed chain"), run.why());
        assertTrue(texts(h.last()).contains("#stone_crafting_materials 8 (craft furnace 1)"), texts(h.last()).toString());
    }

    /** The same shortage after the re-plan stops the run with both reasons (the existing rule); no order behind the run: said, not re-planned. */
    @Test
    void theSameShortageAgainStopsAndWithoutAnOrderItIsSaid() {
        stoneGame();
        Hands h = new Hands();
        h.bag.putAll(Map.of(COBBLE, 9, STICK, 2));
        List<Step> first = List.of(new Step.Craft("stone_pickaxe", 1), new Step.Craft("furnace", 1));
        Steps run = new Steps(h);
        run.start("owner goal", first, 0, (failed, fact, why) -> new Steps.Routine(first, null));
        run.tick(1);                             // 0.39.z62 item 3a: each shortage is looked at again a tick later
        run.tick(2);
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertTrue(run.why().contains("re-planned after \"step 2 (craft furnace 1) needs 8") && run.why().contains("the same step failed again"), run.why());
        assertEquals(List.of(), h.last(), "the run's end reserves nothing");
        Hands h2 = new Hands();
        h2.bag.putAll(Map.of(COBBLE, 9, STICK, 2));
        Steps run2 = new Steps(h2);
        run2.start("k", first, 0);
        assertTrue(run2.running(), run2.why());
        assertTrue(run2.why().contains("dry run: step 2 (craft furnace 1) needs 8 of stone_crafting_materials") && run2.why().contains("not re-planned"), run2.why());
    }

    /** S-R7: a step fails with a bag fact: the replanner is asked with only the order's reserve on the lease; the new table comes at the next start. */
    @Test
    void sR7_releaseBeforeRepair() {
        stoneGame();
        Hands h = new Hands();
        h.bag.putAll(Map.of(COBBLE, 20, STICK, 2));
        h.orders = List.of(Reservations.order("cobblestone", List.of(COBBLE), 4));
        List<List<Reserves.Reserve<String>>> heldAtReplan = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("owner goal", List.of(new Step.Craft("furnace", 1)), 0, (failed, fact, why) -> {
            heldAtReplan.add(h.last());
            return new Steps.Routine(List.of(new Step.Craft("furnace", 1)), null);
        });
        assertEquals(List.of("#stone_crafting_materials 8 (craft furnace 1)", "cobblestone 4 (order cobblestone)"), texts(h.last()));
        h.status = Car.Status.failed("ingredients ran out");
        h.fact = new FactChange.Fact("bag cobblestone", "cobblestone: 2 of 8 in the bag");
        run.tick(1);
        assertEquals(List.of(List.of("cobblestone 4 (order cobblestone)")), heldAtReplan.stream().map(Z61ReservationsTest::texts).toList());
        assertTrue(run.why().contains("released 1 reserve of the failed chain"), run.why());
        assertEquals(List.of("#stone_crafting_materials 8 (craft furnace 1)", "cobblestone 4 (order cobblestone)"), texts(h.last()), "the new table at the new start");
    }

    // ---- S-R6: the orders ----

    static Goals.Goal goal(String path, int n) {
        Thing.Result r = new Thing.Result(path, path, false, List.of("minecraft:" + path), List.of(path), 0, null, null, null);
        return new Goals.Goal(r, n, 0, 0);
    }

    /** S-R6: aa want cobblestone 64, collecting, bag 70: 6 may be placed; at 64 none. */
    @Test
    void sR6_theRunningOrdersTargetIsNotPillared() {
        var rs = Goals.orderReserves(goal("cobblestone", 64), new ArrayList<>(), Map.of());
        assertEquals(List.of("cobblestone 64 (order cobblestone)"), texts(rs));
        assertEquals(Reserves.Kind.ORDER, rs.get(0).kind);
        assertEquals(6, Reserves.spareThrowaway(rs, Map.of(COBBLE, 70), List.of(COBBLE)));
        assertEquals(0, Reserves.spareThrowaway(rs, Map.of(COBBLE, 64), List.of(COBBLE)));
    }

    /**
     * Ruling 1, option c: a finished order's items stay safe from pillaring; a craft that uses some lowers its claim to what
     * the bag still holds (usable as ingredients); at 0 it goes.
     */
    @Test
    void aFinishedOrdersItemsAreSafeFromPillaringButUsableAsIngredients() {
        List<Goals.Finished> fin = new ArrayList<>(List.of(new Goals.Finished("cobblestone", List.of(COBBLE), 64, false)));
        var rs = Goals.orderReserves(null, fin, Map.of("cobblestone", 70));
        assertEquals(List.of("cobblestone 64 (order cobblestone (finished))"), texts(rs));
        assertEquals(6, Reserves.spareThrowaway(rs, Map.of(COBBLE, 70), List.of(COBBLE)));
        rs = Goals.orderReserves(null, fin, Map.of("cobblestone", 40));       // a furnace and more took 30 as ingredients
        assertEquals(List.of("cobblestone 40 (order cobblestone (finished))"), texts(rs));
        assertEquals(40, fin.get(0).n, "the claim only goes down");
        rs = Goals.orderReserves(null, fin, Map.of("cobblestone", 50));
        assertEquals(40, rs.get(0).units, "never back up");
        Goals.orderReserves(null, fin, Map.of());
        assertTrue(fin.isEmpty(), "none left in the bag: the entry goes");
        // the dry run never counts an order: the chain may use them as ingredients
        stoneGame();
        List<Step> steps = List.of(new Step.Craft("furnace", 1));
        assertNull(DryRun.check(steps, 0, needs(steps), Map.of(COBBLE, 8)));
    }

    // ---- S-R8: rule 15 ----

    /** S-R8: no recipe data: the start line says the reserves are not loaded and nothing is read as "none"; data lands: counts at the next start. */
    @Test
    void sR8_noRecipeDataIsSaidInTheStartLine() {
        TestData.clear();
        Hands h = new Hands();
        h.status = Car.Status.done("ok");
        Steps run = new Steps(h);
        run.start("k", List.of(new Step.Walk(0, 64, 0, 0), new Step.Craft("furnace", 1)), 0);
        assertTrue(run.why().contains("reserves not loaded: step 2 (craft furnace 1): no recipe data"), run.why());
        stoneGame();
        run.tick(1);
        assertEquals(List.of("#stone_crafting_materials 8 (craft furnace 1)"), texts(h.last()), "the data landed: counted at the next start");
        assertFalse(run.why().contains("reserves not loaded"), run.why());
    }

    /** A step whose yield is not known (a mine) ends the dry run there: nothing after it is called short. */
    @Test
    void theDryRunStopsAtAStepWhoseYieldIsNotKnown() {
        stoneGame();
        List<Step> steps = List.of(new Step.Mine("iron", 3, Step.Mine.How.KNOWN), new Step.Smelt("iron_ingot", 3, true));
        assertNull(DryRun.check(steps, 0, needs(steps), Map.of()));
        List<Step> collected = List.of(new Step.Collect("cobblestone", 8), new Step.Craft("furnace", 1));
        assertNull(DryRun.check(collected, 0, needs(collected), Map.of()), "a collect's n is in the bag after it");
        List<Step> unburnt = List.of(new Step.Smelt("iron_ingot", 3, true));
        assertEquals("step 1 (smelt iron_ingot 3 here) needs 600 burn ticks of fuel; the bag holds 100 now",
                DryRun.check(unburnt, 0, needs(unburnt), Map.of("minecraft:raw_iron", 3, STICK, 1)).text());
    }
}
