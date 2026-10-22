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
 * docs/SCENARIOS.md S1-S9: the chains the owner worked by hand (docs/TIME_MODEL.md), each built from the scenario's facts. The
 * hand number is the truth: a price is asserted within 0.1 s of it (the walk is the straight line x 1.3 / 4.317, so 30 m is
 * 9.03 s where the hand says 9.0). No game; the break times are the game's formula from the block data the test installs.
 */
class ScenariosTest {
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
        return new World(bag, table, word -> null, 0, word -> seen.getOrDefault(word, List.of()), furnace);
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

    @Test
    void s9_fromNothingTheTableIsAnItemWoodTableWoodenPickaxeCobblestoneStonePickaxeAndNoCellIsAsked() {
        world("wooden", "stone");
        Spot stone16 = new Spot(0, 60, 16, 16, "minecraft:stone", 40);
        Chain c = NeedChain.resolve("stone_pickaxe", 1, w(Map.of(), false, false, Map.of("log", List.of(tree(10, 6)), "cobblestone", List.of(stone16))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(28.35, c.seconds(), TOL, c.why());
        assertFalse(c.why().contains("free cell") || c.why().contains("no free"), c.why());
        assertTrue(c.dropped().stream().noneMatch(d -> d.contains("cell")), c.dropped().toString());
        int chop = -1, table = -1, wooden = -1, dig = -1, stonePick = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            Step s = c.steps().get(i);
            if (s instanceof Step.ChopTree) chop = i;
            else if (s instanceof Step.PlaceNear p && p.item().equals("crafting_table")) table = i;
            else if (s instanceof Step.Craft k && k.item().equals("wooden_pickaxe")) wooden = i;
            else if (s instanceof Step.Collect k && k.item().equals("cobblestone")) dig = i;
            else if (s instanceof Step.Craft k && k.item().equals("stone_pickaxe")) stonePick = i;
        }
        assertTrue(chop >= 0 && chop < table && table < wooden && wooden < dig && dig < stonePick, c.steps().toString());
        assertEquals(3, ((Step.ChopTree) c.steps().get(chop)).logs(), "9 planks = 3 logs: " + c.steps());
    }

    /** The tool tags the scenarios' tool group reads (ToolGroup), for the materials the planner data has recipes for. */
    static TestData toolWorld() {
        TestData d = TestData.planner();
        for (String m : List.of("wooden", "stone", "iron", "golden", "diamond")) {
            d.tag(m + "_pickaxe", "minecraft:pickaxes").tag(m + "_axe", "minecraft:axes").tag(m + "_hoe", "minecraft:hoes")
                    .tag(m + "_sword", "minecraft:swords").tag(m + "_shovel", "minecraft:shovels");
        }
        d.recipe("golden_pickaxe", 1, "gold_ingot", "gold_ingot", "gold_ingot", "stick", "stick");
        d.tableItems.add(TestData.id("golden_pickaxe"));
        d.block("gold_ore", 3, 0);
        d.install();
        return d;
    }

    /**
     * S10 (0.39.y1, ruling a): `aa only torches`, from nothing. Bag empty; a tree 10 m (6 logs), stone 16 m, coal ore 20 m, no
     * table, no furnace. 16 torches (Tuning torch.target = 16) = 4 crafts of 4 = 4 coal + 4 sticks. The owner's sketch gave the coal
     * ore 2 blocks; 4 coal need 4 ore blocks (one coal each), so the spot here has 4 (a 2-block vein would end in an explore).
     * Coal ore asks for tier 1 (wood), so the dig pulls in the lowest pickaxe: the wooden one. No furnace (coal is mined, not
     * smelted) and no stone pickaxe.
     * <pre>
     * wood:   walk 10 m 3.01 (10 x 1.3 / 4.317), 3 logs x 3.0 = 9.0                          = 12.01
     *         planks: 3 batches of 4 (pickaxe 3, its sticks 2, the table 4 = 9; 3 left) x 0.3 = 0.9;
     *         sticks 0.3 (4 made, 2 for the pickaxe); table 0.3, place 0.15; wooden pickaxe 0.3   = 1.95
     * coal:   walk 20 m 6.02, 4 ore x (3 x 30 / 2 = 45 ticks = 2.25 s, wooden pickaxe) 9.0    = 15.02
     * torches: 4 sticks wanted, 2 left: one more stick craft 0.3 (2 of the 3 spare planks); 4 torch crafts x 0.3 = 1.2   = 1.5
     * total 12.01 + 1.95 + 15.02 + 1.5 = 30.48 s
     * (the planner calls coal "fuel": the dig is "dig fuel seen 20 m with wooden_pickaxe")
     * </pre>
     */
    @Test
    void s10_torchesFromNothingDigCoalWithAWoodenPickaxeAndNothingMore() {
        toolWorld();
        Spot coal = new Spot(0, 40, 20, 20, "minecraft:coal_ore", 4);
        Spot stone16 = new Spot(0, 60, 16, 16, "minecraft:stone", 40);
        assertEquals(16, (int) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("torch.target"));
        Chain c = NeedChain.resolve("torch", 16, w(Map.of(), false, false, Map.of("log", List.of(tree(10, 6)), "fuel", List.of(coal), "cobblestone", List.of(stone16))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(30.48, c.seconds(), TOL, c.why());
        String chain = chainPart(c.why());
        assertFalse(chain.contains("stone_pickaxe") || chain.contains("furnace"), "wood tier digs coal ore: " + c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Smelt || s instanceof Step.Explore), c.steps().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft k && (k.item().contains("stone_pickaxe") || k.item().contains("furnace"))), c.steps().toString());
        assertTrue(chain.contains("dig fuel seen 20 m with wooden_pickaxe"), c.why());
        assertTrue(c.steps().contains(new Step.Collect("fuel", 4)), "4 coal ore in one collect (the planner calls coal fuel): " + c.steps());
        assertEquals(16, ((Step.Craft) c.steps().get(c.steps().size() - 1)).n(), "the torches are the last craft: " + c.steps());
        assertEquals("torch", ((Step.Craft) c.steps().get(c.steps().size() - 1)).item(), c.steps().toString());
        int wooden = -1, dig = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            Step s = c.steps().get(i);
            if (s instanceof Step.Craft k && k.item().equals("wooden_pickaxe")) wooden = i;
            else if (s instanceof Step.Collect k && k.item().equals("fuel")) dig = i;
        }
        assertTrue(wooden >= 0 && wooden < dig, "the pickaxe is made before the dig: " + c.steps());
    }

    /**
     * S11 (0.39.y1, ruling b): `aa want stone tools` with a table placed and 12 cobblestone + 8 sticks in the bag. The group is every
     * item of the tool tags whose material is stone, in the tag order pickaxes, axes, hoes, swords, shovels (the test data's recipes:
     * pickaxe 3 cobblestone + 2 sticks, axe 3 + 2, hoe 2 + 2, sword 2 + 1, shovel 1 + 2). One simulated bag: after the pickaxe, axe, hoe
     * and sword 11 of the 12... 10 cobblestone and 7 of the 8 sticks are used, so the shovel (2 sticks) has 1 and would have to look
     * for a tree that is not known: it is dropped, named once. 4 crafts x 0.3 s = 1.2 s, no walk.
     */
    @Test
    void s11_stoneToolsWithATableAndTheBagMakeFourCraftsAndNoWalk() {
        toolWorld();
        List<String> group = io.github.mojolowjo.entropybot.aa.brain.ToolGroup.items("stone_tools");
        assertEquals(List.of("minecraft:stone_pickaxe", "minecraft:stone_axe", "minecraft:stone_hoe", "minecraft:stone_sword", "minecraft:stone_shovel"), group);
        Chain c = NeedChain.resolveAll("stone_tools", group, 1, w(Map.of("minecraft:cobblestone", 12, "minecraft:stick", 8), true, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(1.2, c.seconds(), TOL, c.why());
        assertEquals(List.of(new Step.Craft("stone_pickaxe", 1), new Step.Craft("stone_axe", 1), new Step.Craft("stone_hoe", 1), new Step.Craft("stone_sword", 1)), c.steps());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Walk || s instanceof Step.Explore), c.steps().toString());
        assertEquals(1, c.dropped().stream().filter(d -> d.startsWith("minecraft:stone_shovel: no known source")).count(), c.dropped().toString());
        assertEquals(1, c.dropped().size(), c.dropped().toString());
    }

    @Test
    void s11b_stoneToolsFromNothingShareOneTableAndOneTrip() {
        toolWorld();
        Spot stone16 = new Spot(0, 60, 16, 16, "minecraft:stone", 40);
        List<String> group = io.github.mojolowjo.entropybot.aa.brain.ToolGroup.items("stone_tools");
        Chain c = NeedChain.resolveAll("stone_tools", group, 1, w(Map.of(), false, false, Map.of("log", List.of(tree(10, 12)), "cobblestone", List.of(stone16))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(1, c.steps().stream().filter(s -> s instanceof Step.PlaceNear p && p.item().equals("crafting_table")).count(), "one table: " + c.steps());
        assertEquals(1, c.steps().stream().filter(s -> s instanceof Step.Collect k && k.item().equals("cobblestone")).count(), "one trip to the stone: " + c.steps());
        assertEquals(1, c.steps().stream().filter(s -> s instanceof Step.Walk w && w.z() == 16).count(), "one walk to the stone: " + c.steps());
        assertTrue(c.steps().contains(new Step.Collect("cobblestone", 11)), "3 + 3 + 2 + 2 + 1 cobblestone: " + c.steps());
        assertEquals(1, crafts(c, "wooden_pickaxe"), "the digging pickaxe is made once: " + c.steps());
        for (String t : List.of("stone_pickaxe", "stone_axe", "stone_hoe", "stone_sword", "stone_shovel")) assertEquals(1, crafts(c, t), t + ": " + c.steps());
        assertFalse(chainPart(c.why()).contains("golden") || chainPart(c.why()).contains("iron") || chainPart(c.why()).contains("diamond"), "only the stone group: " + c.why());
    }

    @Test
    void theDroppedListNamesEachLosingCandidateOnce() {
        TestData d = toolWorld();
        d.recipe("oak_planks", 4, "bamboo_block");
        d.install();
        Chain c = NeedChain.resolve("stone_pickaxe", 1, w(Map.of(), false, false, s4Seen()));
        assertTrue(c.ok() && !c.explore(), c.why());
        long bamboo = c.dropped().stream().filter(x -> x.contains("bamboo_block")).count();
        assertEquals(1, bamboo, "bamboo listed once, not once per batch: " + c.dropped());
        assertEquals(c.dropped().size(), c.dropped().stream().map(x -> x.substring(0, x.indexOf(':'))).distinct().count(), c.dropped().toString());
    }

    @Test
    void aGroupWordIsAnyMaterialFromTheItemDataAndToolsIsAll() {
        toolWorld();
        var tools = io.github.mojolowjo.entropybot.aa.brain.ToolGroup.items("tools");
        assertEquals(25, tools.size(), tools.toString());
        assertEquals(java.util.Set.of("minecraft:wooden_pickaxe", "minecraft:golden_pickaxe"), java.util.Set.copyOf(tools.subList(0, 2)), "by tag, then by tier (gold digs as wood)");
        assertEquals(5, io.github.mojolowjo.entropybot.aa.brain.ToolGroup.items("wood_tools").size(), "wood -> wooden");
        assertEquals(5, io.github.mojolowjo.entropybot.aa.brain.ToolGroup.items("iron_tools").size());
        assertTrue(io.github.mojolowjo.entropybot.aa.brain.ToolGroup.items("stone").isEmpty(), "not a group word");
        assertTrue(io.github.mojolowjo.entropybot.aa.brain.ToolGroup.items("unobtainium_tools").isEmpty());
        // a modded stone-tier tool with another name is in "stone tools": the tier is read from its data, not its name
        toolWorld().tool("moddium:rock_pick", io.github.mojolowjo.entropybot.aa.brain.ItemWorth.Role.PICKAXE, 4, 1, 131).tag("moddium:rock_pick", "minecraft:pickaxes").install();
        assertTrue(io.github.mojolowjo.entropybot.aa.brain.ToolGroup.items("stone_tools").contains("moddium:rock_pick"));
        assertFalse(io.github.mojolowjo.entropybot.aa.brain.ToolGroup.items("wood_tools").contains("moddium:rock_pick"));
    }
}