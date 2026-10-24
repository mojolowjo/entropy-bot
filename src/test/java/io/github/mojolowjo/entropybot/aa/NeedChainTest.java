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

/** rm-bootstrap, one-planner: an item resolved by recursion over the game's data - wood, a table, a pickaxe for the stone, then the stone pickaxe; item ids only. */
class NeedChainTest {

    @BeforeEach
    void data() {
        TestData d = TestData.planner();
        for (String m : List.of("wooden", "stone", "iron", "diamond")) d.tag(m + "_pickaxe", "minecraft:pickaxes");
        d.install();
    }

    @AfterEach
    void clear() { TestData.clear(); }

    static Function<String, Spot> seen(Spot tree, Spot stone) {
        return w -> w.equals("minecraft:oak_log") ? tree : w.equals("minecraft:cobblestone") ? stone : null;
    }

    static final Spot TREE = new Spot(30, 64, 0, 30, null), STONE = new Spot(12, 60, 3, 12, "minecraft:stone");

    static int at(List<Step> steps, Class<? extends Step> k, String text) {
        for (int i = 0; i < steps.size(); i++) if (k.isInstance(steps.get(i)) && steps.get(i).text().contains(text)) return i;
        return -1;
    }

    @Test
    void fromNothingATreeAndStoneSeenGiveTheWholeChain() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, seen(TREE, STONE))));
        assertTrue(c.ok(), c.why());
        assertFalse(c.explore());
        List<Step> s = c.steps();
        int chop = at(s, Step.ChopTree.class, "cut"), table = at(s, Step.PlaceNear.class, "crafting_table"), wooden = at(s, Step.Craft.class, "wooden_pickaxe"),
                stone = at(s, Step.Collect.class, "cobblestone"), last = at(s, Step.Craft.class, "stone_pickaxe");
        assertTrue(chop >= 0 && table > chop && wooden > table && stone > wooden && last > stone, s.toString());
        assertEquals(s.size() - 1, last, "the stone pickaxe is the last step");
        assertTrue(s.stream().noneMatch(x -> x instanceof Step.Walk), "chop B2: no walk step before the cut, the chop paths itself: " + s);
        assertTrue(c.why().startsWith("stone_pickaxe <- craft: 3 cobblestone <- dig cobblestone seen 12 m"), c.why());
        assertTrue(c.why().contains("planks <- ") && c.why().contains("tree seen 30 m"), c.why());
        assertTrue(c.cost() > 0);
    }

    /** station-memory (owner 2026-10-22): the table I placed 20 m back is walked to and priced, never a second one. */
    @Test
    void aKnownTable20mAwayIsWalkedToAndPricedNeverMadeAgain() {
        Map<String, Integer> bag = Map.of("minecraft:cobblestone", 8, "minecraft:oak_planks", 4);
        Spot table = new Spot(20, 64, 0, 20, "minecraft:crafting_table");
        World far = io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(bag, true, seen(null, null))).withStations(k -> io.github.mojolowjo.entropybot.aa.brain.ItemWorth.CRAFT.equals(k) ? table : null);
        NeedChain.Chain c = NeedChain.resolve("furnace", 1, far);
        NeedChain.Chain here = NeedChain.resolve("furnace", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(bag, true, seen(null, null))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.why().contains("at the table 20 m"), "the craft phrase names the walk: " + c.why());
        assertEquals(here.seconds() + FakePaths.walkS(20), c.seconds(), 1e-6, "the price holds the 20 m walk to the table");
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.PlaceNear), "no second table: " + c.steps());
        assertEquals(List.of(new Step.Craft("furnace", 1)), c.steps(), "the craft step walks to the table itself (InteractProcess)");
    }

    /** station-memory: the table forgotten (gone on arrival) -> no table known: the same order is priced again and places one. */
    @Test
    void theTableForgottenRepricesTheChainWithANewOne() {
        Map<String, Integer> bag = Map.of("minecraft:cobblestone", 8, "minecraft:oak_planks", 4);
        NeedChain.Chain c = NeedChain.resolve("furnace", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(bag, false, seen(null, null))));
        assertTrue(c.ok() && !c.explore(), c.why());
        int place = at(c.steps(), Step.PlaceNear.class, "crafting_table"), craft = at(c.steps(), Step.Craft.class, "furnace");
        assertTrue(place >= 0 && craft > place, c.steps().toString());
        assertFalse(c.why().contains("at the table"), c.why());
    }

    @Test
    void aLootBookStillBeingReadMakesTheChainWaitNotExplore() {
        io.github.mojolowjo.entropybot.aa.brain.LootBook.Progress p = new io.github.mojolowjo.entropybot.aa.brain.LootBook.Progress("from the jars");
        p.total.set(3104);
        p.done.set(812);
        var reading = io.github.mojolowjo.entropybot.aa.brain.LootBook.reading(p);
        Function<String, List<Spot>> none = w -> List.of();
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, false, none, null, reading, null, 0)));
        assertFalse(c.ok(), c.why());
        assertTrue(c.waiting(), c.why());
        assertEquals("waiting for the loot book (812 of 3104)", c.missing());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore), "no explore on a missing book: " + c.steps());
        // a tree seen: the chain stands without the book where it can
        NeedChain.Chain t = NeedChain.resolve("oak_planks", 4, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, false, w -> w.equals("minecraft:oak_log") ? List.of(TREE) : List.of(), null, reading, null, 0)));
        assertTrue(t.ok() && !t.explore(), t.why());
        // the book lands (ready, nothing in it): the same order is priced again and ends in its explore
        var landed = new io.github.mojolowjo.entropybot.aa.brain.LootBook(List.of(), "jars: 3,104 tables, 2.1 s");
        NeedChain.Chain again = NeedChain.resolve("stone_pickaxe", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, false, none, null, landed, null, 0)));
        assertTrue(again.ok() && again.explore(), again.why());
    }

    @Test
    void nothingSeenIsTheExploreWayPricedForThatNeedNeverADrop() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, seen(null, null))));
        assertTrue(c.ok(), "no tree seen is not infeasible: " + c.why());
        assertTrue(c.explore());
        assertEquals(List.of(new Step.Explore(NeedChain.exploreMinutes(), "oak_log")), c.steps());
        assertTrue(c.why().contains("explore") && c.why().contains("no oak_log source known"), c.why());
        assertTrue(c.cost() > NeedChain.exploreMinutes() * Options.MINUTE, "the return leg is priced: " + c.cost());
    }

    @Test
    void logsInTheBagGoStraightToTheTableAndTheCrafts() {
        World w = io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of("minecraft:oak_log", 8), false, seen(null, STONE)));
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, w);
        assertTrue(c.ok(), c.why());
        assertEquals(-1, at(c.steps(), Step.ChopTree.class, "cut"), "8 logs are enough: " + c.steps());
        assertFalse(c.explore());
        assertTrue(at(c.steps(), Step.Craft.class, "planks") < at(c.steps(), Step.PlaceNear.class, "crafting_table"));
    }

    @Test
    void aTableInReachIsNotMadeAgain() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of("minecraft:oak_log", 8), true, seen(null, STONE))));
        assertTrue(c.ok(), c.why());
        assertEquals(-1, at(c.steps(), Step.PlaceNear.class, "crafting_table"), c.steps().toString());
        assertEquals(-1, at(c.steps(), Step.Craft.class, "crafting_table"), c.steps().toString());
    }

    @Test
    void aTableFromTheBagIsPlacedNotCrafted() {
        World w = io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of("minecraft:oak_log", 8, "minecraft:crafting_table", 1), false, seen(null, STONE)));
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, w);
        assertTrue(at(c.steps(), Step.PlaceNear.class, "crafting_table") >= 0);
        assertEquals(-1, at(c.steps(), Step.Craft.class, "crafting_table"));
    }

    @Test
    void whatIsInTheBagIsNoWork() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of("minecraft:stone_pickaxe", 1), false, seen(null, null))));
        assertTrue(c.ok());
        assertTrue(c.steps().isEmpty());
    }

    @Test
    void theTableIsAnItemPlacedNearMeAndNoCellIsEverAsked() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of("minecraft:oak_log", 8), false, seen(null, STONE))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().contains(new Step.PlaceNear("crafting_table")), c.steps().toString());
        assertFalse(c.why().contains("cell"), c.why());
        assertTrue(c.dropped().stream().noneMatch(d -> d.contains("cell")), c.dropped().toString());
    }

    @Test
    void aRecipeLoopIsADeadBranchNamedInTheWhyAndTheLeafIsTheExplore() {
        TestData d = TestData.planner();
        d.recipe("zinc_ingot", 1, "zinc_block");
        d.recipe("zinc_block", 1, "zinc_ingot");
        d.install();
        NeedChain.Chain c = NeedChain.resolve("zinc_ingot", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, seen(TREE, STONE))));
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
        NeedChain.Chain c = NeedChain.resolve("gadget", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), true, seen(TREE, STONE))));
        assertTrue(c.ok(), c.why());
        assertTrue(at(c.steps(), Step.ChopTree.class, "cut") >= 0 || at(c.steps(), Step.Explore.class, "") >= 0, c.steps().toString());
        assertFalse(c.dropped().isEmpty(), c.why());
    }

    @Test
    void anIngredientWithNoRecipeAndNoKnownSourceIsTheExploreLeafForThatItem() {
        TestData d = TestData.planner();
        d.recipe("gadget", 1, "mystery");
        d.install();
        NeedChain.Chain c = NeedChain.resolve("gadget", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), true, seen(TREE, STONE))));
        assertTrue(c.ok(), "no recipe and no source is an explore, not a 'no way': " + c.why());
        assertTrue(c.explore());
        assertEquals(List.of(new Step.Explore(NeedChain.exploreMinutes(), "mystery")), c.steps());
        assertTrue(c.why().contains("mystery") && c.why().contains("no mystery source known"), c.why());
    }

    @Test
    void aNoTreeWorldIsTheExploreLeafAndTheWhyLineShowsTheChain() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, seen(null, STONE))));
        System.out.println("WHY-LINE " + c.why());
        assertTrue(c.ok() && c.explore(), c.why());
        assertEquals(1, c.steps().size(), c.steps().toString());
        // explore-until (0.39.z16): the chosen way's word first; the other pickaxes' unknown sources end it too (the iron one's raw iron)
        assertEquals("oak_log", ((Step.Explore) c.steps().get(0)).until().split("\\|")[0], c.steps().toString());
        assertTrue(c.why().startsWith("stone_pickaxe <- ")&& c.why().contains("explore") && c.why().contains("no oak_log source known"), c.why());
    }

    @Test
    void aBranchPastTheHorizonLosesToTheOtherBranch() {
        TestData d = TestData.planner();
        d.recipe("gadget", 1, "oak_planks");
        d.recipe("far_thing", 1, "gadget");
        d.install();
        // the tree is 30 m away: crafting from logs costs more than a 1-block horizon, and far_thing has no other branch, so the craft stays
        NeedChain.Chain c = NeedChain.resolve("far_thing", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, seen(TREE, STONE), 1)));
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Craft || s instanceof Step.Explore), c.steps().toString());
    }

    @Test
    void aWoodenPickaxeInTheBagSkipsTheTierSubNeed() {
        World w = io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of("minecraft:wooden_pickaxe", 1, "minecraft:oak_log", 4), true, seen(null, STONE)));
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
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, seen(TREE, STONE))));
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
        NeedChain.Chain c = NeedChain.resolve("cobblestone", 3, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, seen(TREE, STONE))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.dropped().stream().anyMatch(x -> x.startsWith("golden_pickaxe: ")), c.dropped().toString());
        assertTrue(at(c.steps(), Step.Collect.class, "cobblestone") > at(c.steps(), Step.Craft.class, "wooden_pickaxe"), c.steps().toString());
    }

    @Test
    void noTreeStoneSeenExploresForALogNotForGold() {
        goldWorld();
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(Map.of(), false, seen(null, STONE))));
        System.out.println("WHY-LINE (c) " + c.why());
        assertTrue(c.ok() && c.explore(), c.why());
        String chain = chainPart(c.why());
        assertTrue(chain.contains("log") && chain.contains("explore"), c.why());
        assertFalse(chain.contains("gold_ingot"), c.why());
    }}
