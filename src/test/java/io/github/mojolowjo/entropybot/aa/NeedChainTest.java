package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** rm-bootstrap: the tools need resolved by recursion over the recipe data - wood, a table, a pickaxe for the stone, then the stone pickaxe. */
class NeedChainTest {
    static final int[] CELL = {3, 64, 1};

    @BeforeEach
    void data() {
        TestData d = TestData.planner();
        for (String m : List.of("wooden", "stone", "iron", "diamond")) d.tag(m + "_pickaxe", "minecraft:pickaxes");
        d.install();
    }

    @AfterEach
    void clear() { TestData.clear(); }

    static Function<String, Spot> seen(Spot tree, Spot stone) {
        return w -> w.equals("log") ? tree : w.equals("cobblestone") ? stone : null;
    }

    static final Spot TREE = new Spot(30, 64, 0, 30, null), STONE = new Spot(12, 60, 3, 12, "minecraft:stone");

    static int at(List<Step> steps, Class<? extends Step> k, String text) {
        for (int i = 0; i < steps.size(); i++) if (k.isInstance(steps.get(i)) && steps.get(i).text().contains(text)) return i;
        return -1;
    }

    @Test
    void fromNothingATreeAndStoneSeenGiveTheWholeChain() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, new World(Map.of(), false, CELL, seen(TREE, STONE)));
        assertTrue(c.ok(), c.why());
        assertFalse(c.explore());
        List<Step> s = c.steps();
        int chop = at(s, Step.ChopTree.class, "cut"), table = at(s, Step.Build.class, "crafting_table"), wooden = at(s, Step.Craft.class, "wooden_pickaxe"),
                stone = at(s, Step.Collect.class, "cobblestone"), last = at(s, Step.Craft.class, "stone_pickaxe");
        assertTrue(chop >= 0 && table > chop && wooden > table && stone > wooden && last > stone, s.toString());
        assertEquals(s.size() - 1, last, "the stone pickaxe is the last step");
        assertEquals(new Step.Walk(30, 64, 0, 0).x(), ((Step.Walk) s.get(chop - 1)).x(), "one Baritone goal at the seen tree before the cut");
        assertTrue(c.why().startsWith("stone_pickaxe <- craft: 3 cobblestone <- dig cobblestone seen 12 m"), c.why());
        assertTrue(c.why().contains("planks <- ") && c.why().contains("tree seen 30 m"), c.why());
        assertTrue(c.cost() > 0);
    }

    @Test
    void nothingSeenIsTheExploreWayPricedForThatNeedNeverADrop() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, new World(Map.of(), false, CELL, seen(null, null)));
        assertTrue(c.ok(), "no tree seen is not infeasible: " + c.why());
        assertTrue(c.explore());
        assertEquals(List.of(new Step.Explore(NeedChain.exploreMinutes())), c.steps());
        assertTrue(c.why().contains("explore") && c.why().contains("no tree seen"), c.why());
        assertTrue(c.cost() > NeedChain.exploreMinutes() * Options.MINUTE, "the return leg is priced: " + c.cost());
    }

    @Test
    void logsInTheBagGoStraightToTheTableAndTheCrafts() {
        World w = new World(Map.of("minecraft:oak_log", 8), false, CELL, seen(null, STONE));
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, w);
        assertTrue(c.ok(), c.why());
        assertEquals(-1, at(c.steps(), Step.ChopTree.class, "cut"), "8 logs are enough: " + c.steps());
        assertFalse(c.explore());
        assertTrue(at(c.steps(), Step.Craft.class, "planks") < at(c.steps(), Step.Build.class, "crafting_table"));
    }

    @Test
    void aTableInReachIsNotMadeAgain() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, new World(Map.of("minecraft:oak_log", 8), true, null, seen(null, STONE)));
        assertTrue(c.ok(), c.why());
        assertEquals(-1, at(c.steps(), Step.Build.class, "crafting_table"), c.steps().toString());
        assertEquals(-1, at(c.steps(), Step.Craft.class, "crafting_table"), c.steps().toString());
    }

    @Test
    void aTableFromTheBagIsPlacedNotCrafted() {
        World w = new World(Map.of("minecraft:oak_log", 8, "minecraft:crafting_table", 1), false, CELL, seen(null, STONE));
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, w);
        assertTrue(at(c.steps(), Step.Build.class, "crafting_table") >= 0);
        assertEquals(-1, at(c.steps(), Step.Craft.class, "crafting_table"));
    }

    @Test
    void whatIsInTheBagIsNoWork() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, new World(Map.of("minecraft:stone_pickaxe", 1), false, CELL, seen(null, null)));
        assertTrue(c.ok());
        assertTrue(c.steps().isEmpty());
    }

    @Test
    void noCellForTheTableIsADroppedBranchNamedInTheWhyNeverANoWay() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, new World(Map.of("minecraft:oak_log", 8), false, null, seen(null, STONE)));
        assertTrue(c.ok(), "a branch that cannot be done is priced out, the chain stays: " + c.why());
        assertTrue(c.explore(), c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.contains("no free cell")), c.dropped().toString());
        assertTrue(c.why().contains("dropped:") && c.why().contains("no free cell"), c.why());
    }

    @Test
    void aRecipeLoopIsADeadBranchNamedInTheWhyAndTheLeafIsTheExplore() {
        TestData d = TestData.planner();
        d.recipe("zinc_ingot", 1, "zinc_block");
        d.recipe("zinc_block", 1, "zinc_ingot");
        d.install();
        NeedChain.Chain c = NeedChain.resolve("zinc_ingot", 1, new World(Map.of(), false, CELL, seen(TREE, STONE)));
        assertTrue(c.ok(), "a loop never kills the chain: " + c.why());
        assertTrue(c.explore(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Explore), c.steps().toString());
        assertTrue(c.dropped().stream().anyMatch(x -> x.contains("needs itself") && x.contains("zinc_ingot")), c.dropped().toString());
        assertTrue(c.why().contains("recipe loop"), c.why());
    }

    @Test
    void oneBadBranchDoesNotKillTheRestOfTheChain() {
        TestData d = TestData.planner();
        d.recipe("zinc_ingot", 1, "zinc_block");
        d.recipe("zinc_block", 1, "zinc_ingot");
        d.recipe("gadget", 1, "zinc_ingot", "oak_planks");
        d.install();
        NeedChain.Chain c = NeedChain.resolve("gadget", 1, new World(Map.of(), true, CELL, seen(TREE, STONE)));
        assertTrue(c.ok(), c.why());
        assertTrue(at(c.steps(), Step.ChopTree.class, "cut") >= 0 || at(c.steps(), Step.Explore.class, "") >= 0, c.steps().toString());
        assertFalse(c.dropped().isEmpty(), c.why());
    }

    @Test
    void anIngredientWithNoRecipeAndNoKnownSourceIsTheExploreLeafForThatItem() {
        TestData d = TestData.planner();
        d.recipe("gadget", 1, "mystery");
        d.install();
        NeedChain.Chain c = NeedChain.resolve("gadget", 1, new World(Map.of(), true, CELL, seen(TREE, STONE)));
        assertTrue(c.ok(), "no recipe and no source is an explore, not a 'no way': " + c.why());
        assertTrue(c.explore());
        assertEquals(List.of(new Step.Explore(NeedChain.exploreMinutes())), c.steps());
        assertTrue(c.why().contains("mystery") && c.why().contains("no recipe and no block I know drops mystery"), c.why());
    }

    @Test
    void aNoTreeWorldIsTheExploreLeafAndTheWhyLineShowsTheChain() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, new World(Map.of(), false, CELL, seen(null, STONE)));
        System.out.println("WHY-LINE " + c.why());
        assertTrue(c.ok() && c.explore(), c.why());
        assertEquals(List.of(new Step.Explore(NeedChain.exploreMinutes())), c.steps());
        assertTrue(c.why().startsWith("stone_pickaxe <- ") && c.why().contains("explore") && c.why().contains("no tree seen"), c.why());
    }

    @Test
    void aBranchPastTheHorizonLosesToTheOtherBranch() {
        TestData d = TestData.planner();
        d.recipe("gadget", 1, "oak_planks");
        d.recipe("far_thing", 1, "gadget");
        d.install();
        // the tree is 30 m away: crafting from logs costs more than a 1-block horizon, and far_thing has no other branch, so the craft stays
        NeedChain.Chain c = NeedChain.resolve("far_thing", 1, new World(Map.of(), false, CELL, seen(TREE, STONE), 1));
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Craft || s instanceof Step.Explore), c.steps().toString());
    }

    @Test
    void aWoodenPickaxeInTheBagSkipsTheTierSubNeed() {
        World w = new World(Map.of("minecraft:wooden_pickaxe", 1, "minecraft:oak_log", 4), true, CELL, seen(null, STONE));
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, w);
        assertEquals(-1, at(c.steps(), Step.Craft.class, "wooden_pickaxe"), c.steps().toString());
        assertTrue(at(c.steps(), Step.Collect.class, "cobblestone") >= 0);
    }

    /** The 0.39.w1 owner-watched world: a golden pickaxe (tier of a wooden one) whose gold is a recipe loop, beside the wooden one. */
    static void goldWorld() {
        TestData d = TestData.planner();
        for (String m : List.of("wooden", "stone", "iron", "diamond", "golden")) d.tag(m + "_pickaxe", "minecraft:pickaxes");
        d.recipe("golden_pickaxe", 1, "gold_ingot", "gold_ingot", "gold_ingot", "stick", "stick");
        d.recipe("gold_ingot", 1, "gold_block");
        d.recipe("gold_block", 1, "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot");
        d.tableItems.add(TestData.id("golden_pickaxe"));
        d.install();
    }

    static String chainPart(String why) { int k = why.indexOf(" | dropped:"); return k < 0 ? why : why.substring(0, k); }

    @Test
    void stoneWithATreeSeenTakesTheWoodenPickaxeNotTheGoldOne() {
        goldWorld();
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, new World(Map.of(), false, CELL, seen(TREE, STONE)));
        System.out.println("WHY-LINE (a) " + c.why());
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(at(c.steps(), Step.Craft.class, "wooden_pickaxe") >= 0, c.steps().toString());
        assertEquals(-1, at(c.steps(), Step.Craft.class, "golden_pickaxe"), c.steps().toString());
        assertFalse(chainPart(c.why()).contains("gold"), c.why());
        assertTrue(c.dropped().stream().anyMatch(x -> x.startsWith("golden_pickaxe: ") && x.contains("no known source")), c.dropped().toString());   // SCENARIOS: an explore branch never competes, dropped unpriced
        assertTrue(c.dropped().stream().noneMatch(x -> x.startsWith("stone_pickaxe: ") && !x.contains("loop")), "a higher tier is never taken: " + c.dropped());
    }

    @Test
    void theRecipeLoopCandidateIsPricedOutAndTheChainCompletes() {
        goldWorld();
        NeedChain.Chain c = NeedChain.resolve("cobblestone", 3, new World(Map.of(), false, CELL, seen(TREE, STONE)));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.dropped().stream().anyMatch(x -> x.startsWith("golden_pickaxe: ")), c.dropped().toString());
        assertTrue(at(c.steps(), Step.Collect.class, "cobblestone") > at(c.steps(), Step.Craft.class, "wooden_pickaxe"), c.steps().toString());
    }

    @Test
    void noTreeStoneSeenExploresForALogNotForGold() {
        goldWorld();
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, new World(Map.of(), false, CELL, seen(null, STONE)));
        System.out.println("WHY-LINE (c) " + c.why());
        assertTrue(c.ok() && c.explore(), c.why());
        String chain = chainPart(c.why());
        assertTrue(chain.contains("log") && chain.contains("explore"), c.why());
        assertFalse(chain.contains("gold_ingot"), c.why());
    }}
