package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docs/SCENARIOS.md S1-S8: the chains the owner worked by hand (docs/TIME_MODEL.md), each built from the scenario's facts. The
 * hand number is the truth: a price is asserted within 0.1 s of it (the walk is the straight line x 1.3 / 4.317, so 30 m is
 * 9.03 s where the hand says 9.0). No game; the break times are the game's formula from the block data the test installs.
 */
class ScenariosTest {
    static final int[] CELL = {3, 64, 1};
    static final double TOL = 0.1;

    @AfterEach
    void clear() { TestData.clear(); }

    /** The planner data (recipes, furnace recipes, ore drops) with the pickaxes tag and the gold the scenarios mention. */
    static TestData world(String... pickaxes) {
        TestData d = TestData.planner();
        for (String m : pickaxes) d.tag(m + "_pickaxe", "minecraft:pickaxes");
        d.recipe("golden_pickaxe", 1, "gold_ingot", "gold_ingot", "gold_ingot", "stick", "stick");
        d.tableItems.add(TestData.id("golden_pickaxe"));
        d.block("gold_ore", 3, 0);
        d.install();
        return d;
    }

    static Spot tree(double dist, int logs) { return new Spot((int) dist, 64, 0, dist, "minecraft:oak_log", logs); }

    static final Spot STONE_12 = new Spot(12, 60, 3, 12, "minecraft:stone", 40);

    static World w(Map<String, Integer> bag, boolean table, boolean furnace, Map<String, List<Spot>> seen) {
        return new World(bag, table, CELL, word -> null, 0, word -> seen.getOrDefault(word, List.of()), furnace);
    }

    static String chainPart(String why) { int k = why.indexOf(" | dropped:"); return k < 0 ? why : why.substring(0, k); }

    static long crafts(Chain c, String item) { return c.steps().stream().filter(s -> s instanceof Step.Craft k && k.item().contains(item)).count(); }

    @Test
    void s1_aLogInTheBagMakesThePlanksInOneCraft() {
        world("wooden");
        Chain c = NeedChain.resolve("planks", 4, w(Map.of("minecraft:oak_log", 1), false, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(0.3, c.seconds(), TOL, c.why());
        assertTrue(c.why().startsWith("planks <- craft: 1 log: 0.3 s"), c.why());
        assertEquals(1, c.steps().size(), c.steps().toString());
        assertTrue(c.dropped().isEmpty(), c.dropped().toString());
    }

    @Test
    void s2_aTreeSeenTenMetresIsWalkPlusBreakAndNoExplore() {
        world("wooden");
        Chain c = NeedChain.resolve("log", 1, w(Map.of(), false, false, Map.of("log", List.of(tree(10, 4)))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(6.0, c.seconds(), TOL, c.why());
        assertTrue(c.why().startsWith("log <- cut tree seen 10 m: 6.0 s"), c.why());
        assertTrue(c.dropped().isEmpty(), c.dropped().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore), c.steps().toString());
    }

    @Test
    void s3_nothingSeenIsTheExploreStepWithNoPriceAndNeverNoWay() {
        world("wooden");
        Chain c = NeedChain.resolve("log", 1, w(Map.of(), false, false, Map.of()));
        assertTrue(c.ok(), "never a no way: " + c.why());
        assertTrue(c.explore(), c.why());
        assertEquals(List.of(new Step.Explore(NeedChain.exploreMinutes())), c.steps());
        assertTrue(c.why().startsWith("log <- explore ("), c.why());
        assertFalse(c.why().matches("(?s).*\\d+(\\.\\d+)? s\\b.*"), "no price in the why line: " + c.why());
        assertFalse(c.why().contains("no way"), c.why());
    }

    static Map<String, List<Spot>> s4Seen() { return Map.of("log", List.of(tree(30, 6)), "cobblestone", List.of(STONE_12)); }

    @Test
    void s4_threeCobblestoneTakeTheWoodenPickaxeAndDropTheLoopAndTheUnknownSources() {
        world("wooden", "stone", "iron", "golden");
        Chain c = NeedChain.resolve("cobblestone", 3, w(Map.of(), false, false, s4Seen()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(28.05, c.seconds(), TOL, c.why());
        String chain = chainPart(c.why());
        assertTrue(chain.startsWith("cobblestone <- dig cobblestone seen 12 m with wooden_pickaxe"), c.why());
        assertTrue(chain.contains("craft: 3 planks") && chain.contains("2 stick") && chain.contains("table") && chain.contains("tree seen 30 m"), c.why());
        assertFalse(chain.contains("gold") || chain.contains("iron") || chain.contains("stone_pickaxe"), "no other pickaxe in the chain: " + c.why());
        Step.ChopTree chop = c.steps().stream().filter(s -> s instanceof Step.ChopTree).map(s -> (Step.ChopTree) s).findFirst().orElseThrow();
        assertEquals(3, chop.logs(), "9 planks = 3 logs: " + c.steps());
        assertEquals(6, c.steps().stream().filter(s -> s instanceof Step.Craft).count(), "3 planks, sticks, table, pickaxe: " + c.steps());
        assertEquals(3, c.dropped().size(), c.dropped().toString());
        assertTrue(c.dropped().get(0).startsWith("golden_pickaxe: no known source"), c.dropped().toString());
        assertTrue(c.dropped().get(1).startsWith("iron_pickaxe: no known source"), c.dropped().toString());
        assertTrue(c.dropped().get(2).startsWith("stone_pickaxe: loop"), c.dropped().toString());
        assertTrue(c.dropped().stream().noneMatch(d -> d.contains(" blocks")), "one unit, seconds: " + c.dropped());
    }

    @Test
    void s5_theStonePickaxeAddsTheWalkBackToTheTableAndOneCraftSticksAreReused() {
        world("wooden", "stone", "iron", "golden");
        Chain c = NeedChain.resolve("stone_pickaxe", 1, w(Map.of(), false, false, s4Seen()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(31.95, c.seconds(), TOL, c.why());
        assertTrue(c.why().startsWith("stone_pickaxe <- craft: 3 cobblestone <- dig cobblestone seen 12 m with wooden_pickaxe"), c.why());
        assertEquals(1, crafts(c, "stick"), "4 sticks made, 2 used, the 2 left are the stone pickaxe's: " + c.steps());
        assertEquals("stone_pickaxe", ((Step.Craft) c.steps().get(c.steps().size() - 1)).item(), c.steps().toString());
        assertFalse(chainPart(c.why()).contains("gold"), c.why());
    }

    @Test
    void s6_twoTreesTheNearOneIsCutAndTheFarOneIsDroppedWithItsSeconds() {
        world("wooden");
        Chain c = NeedChain.resolve("log", 1, w(Map.of(), false, false, Map.of("log", List.of(tree(40, 5), tree(10, 5)))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(6.0, c.seconds(), TOL, c.why());
        assertTrue(c.why().startsWith("log <- cut tree seen 10 m: 6.0 s"), c.why());
        assertEquals(1, c.dropped().size(), c.dropped().toString());
        assertTrue(c.dropped().get(0).startsWith("tree 40 m: 15.0 s"), c.dropped().toString());
    }

    @Test
    void s7_afterTheLogsAreInTheBagTheChainReadsFromPlanksAndNothingIsCountedTwice() {
        world("wooden", "stone", "iron", "golden");
        Map<String, List<Spot>> seen = s4Seen();
        Chain before = NeedChain.resolve("cobblestone", 3, w(Map.of(), false, false, seen));
        Chain after = NeedChain.resolve("cobblestone", 3, w(Map.of("minecraft:oak_log", 3), false, false, seen));
        assertTrue(after.ok() && !after.explore(), after.why());
        assertEquals(10.05, after.seconds(), TOL, after.why());
        assertEquals(18.0, before.seconds() - after.seconds(), TOL, "the tree term (9.0 + 3 x 3.0) is gone");
        assertTrue(after.steps().stream().noneMatch(s -> s instanceof Step.ChopTree), after.steps().toString());
        assertFalse(chainPart(after.why()).contains("tree"), after.why());
    }

    @Test
    void s8_twoIngotsAndFourNuggetsCountThreeOreAndTheLoopGuardKeepsTheIngotsOutOfTheNuggets() {
        TestData d = world("wooden", "stone", "iron");
        d.recipe("gold_nugget", 9, "gold_ingot");
        d.recipe("gold_ingot", 1, "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget");
        d.recipe("gold_trinket", 1, "gold_ingot", "gold_ingot", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget");
        d.install();
        Spot gold = new Spot(20, 40, 0, 20, "minecraft:gold_ore", 3);
        Chain c = NeedChain.resolve("gold_trinket", 1, w(Map.of("minecraft:iron_pickaxe", 1, "minecraft:coal", 8), true, true, Map.of("raw_gold", List.of(gold))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(44.85, c.seconds(), TOL, c.why());
        String chain = chainPart(c.why());
        assertFalse(chain.contains("needs itself"), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore), c.steps().toString());
        assertTrue(c.steps().contains(new Step.Collect("raw_gold", 3)), "3 ore in one collect: " + c.steps());
        assertEquals(3, c.steps().stream().filter(s -> s instanceof Step.Smelt).mapToInt(s -> ((Step.Smelt) s).n()).sum(), "3 ingots smelted: " + c.steps());
        assertEquals(1, crafts(c, "gold_nugget"), c.steps().toString());
        assertEquals(1, crafts(c, "gold_trinket"), c.steps().toString());
    }
}
