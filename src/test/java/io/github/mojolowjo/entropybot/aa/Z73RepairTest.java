package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.PlanDiff;
import io.github.mojolowjo.entropybot.aa.moves.Repair;
import io.github.mojolowjo.entropybot.aa.moves.Serves;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z73 (design D2 3.4, 6 tests 14-16, Job D): the kernel check (skip ahead), the repair against the full re-plan, the
 * dry-run shortfall, and the chain's per-step seconds. Hand numbers from the design (TIME_MODEL: craft 0.3 s, place 0.15 s).
 */
class Z73RepairTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static final String STICK = "minecraft:stick", TABLE = "minecraft:crafting_table", INGOT = "minecraft:iron_ingot";

    /** Fake hands: every step starts and runs; needs as given per step text (else none); the bag's stacks as given. */
    static class Hands implements Steps.Hands {
        final Map<String, Integer> bag = new HashMap<>();
        final Map<String, StepNeeds> needs = new HashMap<>();
        final List<String> started = new ArrayList<>(), logged = new ArrayList<>();
        int tier;

        @Override public String start(Step s) { started.add(s.text()); return null; }
        @Override public Car.Status status(Step s) { return Car.Status.running("on it"); }
        @Override public void cancel(String why) {}
        @Override public void log(String line) { logged.add(line); }
        @Override public StepNeeds needs(Step s) { return needs.getOrDefault(s.text(), StepNeeds.NONE); }
        @Override public StepNeeds.Bag bag() {
            return new StepNeeds.Bag() {
                @Override public int bestTier(String kind) { return tier; }
                @Override public String bestTool(String kind) { return tier > 0 ? "minecraft:stone_pickaxe" : null; }
                @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                @Override public int count(String id) { return bag.getOrDefault(id, 0); }
                @Override public Map<String, Integer> items() { return bag; }
            };
        }
    }

    static StepNeeds uses(Map<String, Integer> m) { return new StepNeeds(null, 0, m, null); }

    static Hands pickaxeHands() {
        Hands h = new Hands();
        h.needs.put("craft crafting_table 1", uses(Map.of("minecraft:oak_planks", 4)));
        h.needs.put("craft iron_pickaxe 1", uses(Map.of(INGOT, 3, STICK, 2)));
        return h;
    }

    /**
     * Test 14 (kernel skip): [collect stick 4, craft crafting_table 1, craft iron_pickaxe 1]; the bag holds 4 sticks, a table
     * and 3 iron ingots: the latest kernel that holds is step 3's (3 ingots + 2 sticks, and what steps 1-2 were for kept: 4
     * sticks, 1 table) - steps 1-2 are skipped, each said.
     */
    @Test
    void t14_theLatestHoldingKernelSkipsAhead() {
        Hands h = pickaxeHands();
        h.bag.putAll(Map.of(STICK, 4, TABLE, 1, INGOT, 3));
        Steps run = new Steps(h);
        run.start("goal.iron_pickaxe", List.of(new Step.Collect("stick", 4), new Step.Craft("crafting_table", 1), new Step.Craft("iron_pickaxe", 1)), 0, (f, fact, why) -> null);
        assertEquals(List.of("craft iron_pickaxe 1"), h.started);
        assertEquals(List.of("step 1/3 collect stick 4 skipped: 4 stick already in the bag", "step 2/3 craft crafting_table 1 skipped: 1 crafting_table already in the bag"), h.logged);
        assertTrue(run.why().contains("(skipped steps 1-2: "), run.why());

        // the table missing: what step 2 was for is not in the bag - step 1 alone? its kernel needs 4 planks (none): no skip
        Hands h2 = pickaxeHands();
        h2.bag.putAll(Map.of(STICK, 4, INGOT, 3));
        new Steps(h2).start("goal.iron_pickaxe", List.of(new Step.Collect("stick", 4), new Step.Craft("crafting_table", 1), new Step.Craft("iron_pickaxe", 1)), 0);   // no order: the dry run's planks shortage is said, not held
        assertEquals(List.of("collect stick 4"), h2.started);
        assertTrue(h2.logged.isEmpty(), h2.logged.toString());
    }

    /** An explore's output is not known: never skipped past; a step before it may be (its kernel is known). */
    @Test
    void anExploreIsNeverSkippedPast() {
        Hands h = pickaxeHands();
        h.bag.putAll(Map.of(STICK, 4, TABLE, 1, INGOT, 3));
        Steps run = new Steps(h);
        run.start("k", List.of(new Step.Explore(5, "minecraft:iron_ore"), new Step.Collect("stick", 4), new Step.Craft("iron_pickaxe", 1)), 0, (f, fact, why) -> null);
        assertEquals(List.of("explore 5 until minecraft:iron_ore"), h.started, h.logged.toString());

        Hands h2 = pickaxeHands();
        h2.bag.putAll(Map.of(STICK, 4, INGOT, 3));
        new Steps(h2).start("k", List.of(new Step.Collect("stick", 4), new Step.Explore(5, "minecraft:iron_ore"), new Step.Craft("iron_pickaxe", 1)), 0, (f, fact, why) -> null);
        assertEquals(List.of("explore 5 until minecraft:iron_ore"), h2.started, "the collect before it skipped, the explore runs");
    }

    /** A spare tool the skipped steps make and the suffix wears is kept: one in the bag is not enough for "made + worn". */
    @Test
    void aSpareToolIsNeverSkipped() {
        ScenariosTest.world("wooden", "stone");
        assertTrue(io.github.mojolowjo.entropybot.aa.brain.MiningTiers.toolTier("minecraft:stone_pickaxe", StepNeeds.TIER_TOOL) >= 1);
        Hands h = new Hands();
        h.tier = 2;
        h.bag.putAll(Map.of("minecraft:stone_pickaxe", 1));
        h.needs.put("collect cobblestone 8", new StepNeeds(StepNeeds.TIER_TOOL, 1, Map.of(), null));
        Steps run = new Steps(h);
        run.start("k", List.of(new Step.Craft("stone_pickaxe", 1), new Step.Collect("cobblestone", 8)), 0, (f, fact, why) -> null);
        assertEquals(List.of("craft stone_pickaxe 1"), h.started, h.logged.toString());
    }

    /** Serves: a leg serves what the next non-leg step serves; a mine of a family is not known. */
    @Test
    void servesFromTheStepsOwnData() {
        List<Step> s = List.of(new Step.Walk(1, 2, 3, 0), new Step.Collect("cobblestone", 8), new Step.Mine("iron", 3, Step.Mine.How.CAVE));
        assertEquals(Map.of("minecraft:cobblestone", 8), Serves.of(s, 0));
        assertEquals(1, Serves.servedIndex(s, 0));
        assertNull(Serves.of(s, 2));
    }

    static Step strip() { return new Step.Strip(List.of("minecraft:iron_ore"), Map.of("minecraft:iron_ore", 3), 16, "north", 1L); }

    static Chain chain(List<Step> steps, double seconds, List<Double> per) {
        return new Chain(steps, Unit.blocks(seconds), "test chain", false, null, List.of(), Map.of(), List.of(), "", per);
    }

    /**
     * Test 15 (repair kept, run P shape): [strip iron 3 (failed: pickaxe broke), smelt iron_ingot 3 (30 s), craft iron_pickaxe
     * (0.3 s)]. Gap = [place crafting_table near me 0.15, craft stone_pickaxe 0.3, strip iron 3 90] = 90.45 s; tail 30.3 s;
     * REPAIR 120.75 s. FULL 115 s (5.75 cheaper, under replan.switchMarginS 10) -> REPAIR, plan diff changed 2. FULL 110 s
     * (10.75 cheaper) -> FULL.
     */
    @Test
    void t15_theRepairIsKeptUnlessTheFullRePlanIsCheaperByTheMargin() {
        Step st = strip();
        Step smelt = new Step.Smelt("iron_ingot", 3, false), craft = new Step.Craft("iron_pickaxe", 1);
        List<Step> left = List.of(st, smelt, craft);
        Repair.Split sp = Repair.split(left);
        assertNull(sp.why(), sp.why());
        assertEquals(0, sp.served());
        assertEquals("minecraft:iron_ore", sp.item());
        assertEquals(3, sp.n());
        Chain gap = chain(List.of(new Step.PlaceNear("crafting_table"), new Step.Craft("stone_pickaxe", 1), st), 90.45, List.of(0.15, 0.3, 90.0));
        Map<String, Integer> bag = Map.of(TABLE, 1);
        double margin = io.github.mojolowjo.entropybot.aa.brain.Goals.switchMarginS();
        assertEquals(10, margin);

        Repair.Pick pk = Repair.pick(left, null, sp, gap, 30.3, chain(List.of(new Step.Explore(5)), 115, List.of(115.0)), bag, margin, null);
        assertTrue(pk.repair(), pk.line());
        assertEquals(120.75, pk.repairS(), 0.01);
        assertEquals(5, pk.steps().size());
        assertEquals(2, pk.kept());
        assertTrue(pk.line().startsWith("repaired: gap 3 steps (place crafting_table near me > craft stone_pickaxe 1 > strip iron_ore at feet y 16 north for minecraft:iron_ore 3)"), pk.line());
        assertTrue(pk.line().contains("kept 2 steps (30.3 s)") && pk.line().endsWith("full re-plan 115.0 s"), pk.line());
        PlanDiff d = PlanDiff.of(left, pk.steps());
        assertEquals(2, d.changed());
        assertEquals(3, d.kept());

        Repair.Pick full = Repair.pick(left, null, sp, gap, 30.3, chain(List.of(new Step.Explore(5)), 110, List.of(110.0)), bag, margin, null);
        assertFalse(full.repair(), full.line());
        assertTrue(full.line().startsWith("full re-plan 110.0 s: cheaper than the repair "), full.line());
        assertTrue(full.line().endsWith("(replan.switchMarginS 10)"), full.line());
        // 0.39.z77 SHAPE CHANGE (owner: repair locally): a trip's repair is never priced against its whole re-plan (the
        // margin Goals passes for a trip is infinite) - the same cheaper full re-plan, the repair kept
        Repair.Pick trip = Repair.pick(left, null, sp, gap, 30.3, chain(List.of(new Step.Explore(5)), 110, List.of(110.0)), bag, Double.POSITIVE_INFINITY, null);
        assertTrue(trip.repair(), trip.line());
        assertEquals(120.75, trip.repairS(), 0.01);
    }

    /**
     * Test 16 (dry-run shortfall): [smelt iron_ingot 3 (failed: the furnace gone), craft iron_pickaxe 1 (3 iron_ingot + 2
     * stick)]; gap = [place furnace near me, smelt iron_ingot 3 here]; the bag after gap + smelt: 3 ingots, 1 stick - the
     * kept tail lacks 1 stick -> REPAIR invalid -> FULL.
     */
    @Test
    void t16_aShortKeptTailMeansTheFullRePlan() {
        Step smelt = new Step.Smelt("iron_ingot", 3, false), craft = new Step.Craft("iron_pickaxe", 1);
        List<Step> left = List.of(smelt, craft);
        List<Map<String, Integer>> leftUses = new ArrayList<>();
        leftUses.add(null);
        leftUses.add(Map.of(INGOT, 3, STICK, 2));
        Repair.Split sp = Repair.split(left);
        assertEquals(INGOT, sp.item());
        Chain gap = chain(List.of(new Step.PlaceNear("furnace"), new Step.Smelt("iron_ingot", 3, true)), 30.15, List.of(0.15, 30.0));
        Map<String, Integer> bag = Map.of("minecraft:furnace", 1, "minecraft:raw_iron", 3, STICK, 1);
        Repair.Pick pk = Repair.pick(left, leftUses, sp, gap, 0.3, chain(List.of(new Step.Explore(5)), 200, List.of(200.0)), bag, 10, null);
        assertFalse(pk.repair(), pk.line());
        assertTrue(pk.line().contains("no repair: the kept tail runs short - step 3 (craft iron_pickaxe 1) needs 2 of stick; the bag will hold 1 after step 2"), pk.line());

        Map<String, Integer> enough = Map.of("minecraft:furnace", 1, "minecraft:raw_iron", 3, STICK, 2);
        assertTrue(Repair.pick(left, leftUses, sp, gap, 0.3, chain(List.of(new Step.Explore(5)), 200, List.of(200.0)), enough, 10, null).repair(), "2 sticks: the tail stands");
    }

    /** Rule 15: no tail prices, a gap ending in an explore, no bag - each a full re-plan, said. */
    @Test
    void theRepairStepsAsideWhenSomethingIsNotKnown() {
        Step smelt = new Step.Smelt("iron_ingot", 3, false), craft = new Step.Craft("iron_pickaxe", 1);
        List<Step> left = List.of(smelt, craft);
        Repair.Split sp = Repair.split(left);
        Chain gap = chain(List.of(new Step.Smelt("iron_ingot", 3, true)), 30, List.of(30.0));
        Chain full = chain(List.of(new Step.Explore(5)), 200, List.of(200.0));
        assertTrue(Repair.pick(left, null, sp, gap, Double.NaN, full, Map.of(), 10, null).line().contains("the kept tail's prices are not known"));
        assertTrue(Repair.pick(left, null, sp, gap, 0.3, full, null, 10, null).line().contains("the bag is not readable"));
        Chain explore = new Chain(List.of(new Step.Explore(5)), 0, "x", true, null);
        assertTrue(Repair.pick(left, null, sp, explore, 0.3, full, Map.of(), 10, null).line().contains("ends in an explore"));
        assertTrue(Repair.split(List.of(new Step.Mine("iron", 3, Step.Mine.How.CAVE))).why().contains("is not known"));
    }

    /** Review 1: a kept tail step on the source the failure just dropped would fail the same way: FULL, said. */
    @Test
    void aTailOnTheDroppedSourceIsNoRepair() {
        Step collect = new Step.Collect("cobblestone", 3), craft = new Step.Craft("furnace", 1), again = new Step.Collect("cobblestone", 5);
        List<Step> left = List.of(collect, craft, again);
        Repair.Split sp = Repair.split(left);
        Chain gap = chain(List.of(new Step.Collect("cobblestone", 3)), 10, List.of(10.0));
        Chain full = chain(List.of(new Step.Explore(5)), 200, List.of(200.0));
        String src = NeedChain.sourceKey(collect);
        assertEquals("collect cobblestone", src);
        Repair.Pick pk = Repair.pick(left, null, sp, gap, 5, full, Map.of(), 10, src);
        assertFalse(pk.repair(), pk.line());
        assertTrue(pk.line().contains("no repair: the kept tail uses the dropped source collect cobblestone (step collect cobblestone 5)"), pk.line());
        assertTrue(Repair.pick(left, null, sp, gap, 5, full, Map.of(), 10, null).repair(), "no source dropped: the repair stands");
    }

    /** Review 2: a bag that cannot be read is said in the step's start line (no skip check), never a quiet "nothing holds". */
    @Test
    void anUnreadableBagIsSaid() {
        Hands h = new Hands() {
            @Override public StepNeeds.Bag bag() {
                return new StepNeeds.Bag() {
                    @Override public int bestTier(String kind) { return 0; }
                    @Override public String bestTool(String kind) { return null; }
                    @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                    @Override public int count(String id) { return 0; }
                    @Override public Map<String, Integer> items() { throw new IllegalStateException("inventory locked"); }
                };
            }
        };
        Steps run = new Steps(h);
        run.start("k", List.of(new Step.Collect("stick", 4), new Step.Craft("iron_pickaxe", 1)), 0);
        assertEquals(List.of("collect stick 4"), h.started);
        assertTrue(run.why().contains("(no skip check: the bag could not be read: java.lang.IllegalStateException: inventory locked)"), run.why());
    }

    // ---- per-step seconds: the same rough numbers split, summing to the chain's price ----

    static void sums(Chain c) {
        assertTrue(c.ok(), c.why());
        assertEquals(c.steps().size(), c.stepSeconds().size(), c.steps().toString());
        double sum = 0;
        for (double d : c.stepSeconds()) { assertTrue(d >= 0, c.stepSeconds().toString()); sum += d; }
        assertEquals(c.seconds(), sum, 1e-6, c.why() + " " + c.stepSeconds());
    }

    @Test
    void perStepSecondsSumToTheChainOnScenarioChains() {
        ScenariosTest.world("wooden");
        // S31's two chains (ReplanOnFactTest): the known table 6.32 s; making one from the tree 7.06 s
        Map<String, Integer> bag = Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2);
        Chain known = NeedChain.resolve("wooden_pickaxe", 1, ReplanOnFactTest.world(bag, ReplanOnFactTest.TABLE_20, ReplanOnFactTest.TREE_10));
        sums(known);
        assertEquals(6.32, known.seconds(), 0.1);
        Chain make = NeedChain.resolve("wooden_pickaxe", 1, ReplanOnFactTest.world(bag, null, ReplanOnFactTest.TREE_10));
        sums(make);
        assertEquals(7.06, make.seconds(), 0.1);
        // the last step a craft of 0.3 s: its own share
        assertEquals(0.3, make.stepSeconds().get(make.stepSeconds().size() - 1), 0.01, make.steps() + " " + make.stepSeconds());
        // an explore chain: its explore leg on its last step
        Chain ex = NeedChain.resolve("wooden_pickaxe", 1, ReplanOnFactTest.world(Map.of(), null, Map.of()));
        assertTrue(ex.ok() && ex.explore(), "the precondition: nothing known gives a log - the chain ends in an explore: " + ex.why());
        sums(ex);
        TestData.clear();
        ScenariosTest.world("wooden", "stone");
        Chain stone = NeedChain.resolve("stone_pickaxe", 1, Z61ReservationsTest.world(Map.of("minecraft:cobblestone", 103, STICK, 2, "minecraft:wooden_pickaxe", 1), true));
        sums(stone);
    }
}
