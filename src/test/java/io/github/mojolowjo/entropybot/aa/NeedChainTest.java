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
        assertTrue(c.why().startsWith("stone_pickaxe <- 3 cobblestone <- dig cobblestone seen 12 m"), c.why());
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
    void noCellForTheTableNamesTheMissingThing() {
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, new World(Map.of("minecraft:oak_log", 8), false, null, seen(null, STONE)));
        assertFalse(c.ok());
        assertTrue(c.missing().contains("no free cell"), c.missing());
    }

    @Test
    void aWoodenPickaxeInTheBagSkipsTheTierSubNeed() {
        World w = new World(Map.of("minecraft:wooden_pickaxe", 1, "minecraft:oak_log", 4), true, CELL, seen(null, STONE));
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, w);
        assertEquals(-1, at(c.steps(), Step.Craft.class, "wooden_pickaxe"), c.steps().toString());
        assertTrue(at(c.steps(), Step.Collect.class, "cobblestone") >= 0);
    }
}
