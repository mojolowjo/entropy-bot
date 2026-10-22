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

    /** A world of the scenario's facts; the seen map is keyed by item id (a short id gets "minecraft:"). */
    static World w(Map<String, Integer> bag, boolean table, boolean furnace, Map<String, List<Spot>> seen) {
        Map<String, List<Spot>> ids = new java.util.HashMap<>();
        for (var e : seen.entrySet()) ids.put(e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey(), e.getValue());
        return new World(bag, table, furnace, item -> ids.getOrDefault(item, List.of()), null, null, null, 0);
    }

    static String chainPart(String why) { int k = why.indexOf(" | dropped:"); return k < 0 ? why : why.substring(0, k); }

    static long crafts(Chain c, String item) { return c.steps().stream().filter(s -> s instanceof Step.Craft k && k.item().contains(item)).count(); }

    @Test
    void s1_aLogInTheBagMakesThePlanksInOneCraft() {
        world("wooden");
        Chain c = NeedChain.resolve("planks", 4, w(Map.of("minecraft:oak_log", 1), false, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(0.3, c.seconds(), TOL, c.why());
        assertTrue(c.why().startsWith("planks <- 4 oak_planks <- craft: 1 oak_log: 0.3 s"), c.why());
        assertEquals(1, c.steps().size(), c.steps().toString());
        assertTrue(c.dropped().isEmpty(), c.dropped().toString());
    }

    @Test
    void s2_aTreeSeenTenMetresIsWalkPlusBreakAndNoExplore() {
        world("wooden");
        Chain c = NeedChain.resolve("oak_log", 1, w(Map.of(), false, false, Map.of("oak_log", List.of(tree(10, 4)))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(6.0, c.seconds(), TOL, c.why());
        assertTrue(c.why().startsWith("oak_log <- cut tree seen 10 m: 6.0 s"), c.why());
        assertTrue(c.dropped().isEmpty(), c.dropped().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore), c.steps().toString());
    }

    @Test
    void s3_nothingSeenIsTheExploreStepWithNoPriceAndNeverNoWay() {
        world("wooden");
        Chain c = NeedChain.resolve("oak_log", 1, w(Map.of(), false, false, Map.of()));
        assertTrue(c.ok(), "never a no way: " + c.why());
        assertTrue(c.explore(), c.why());
        assertEquals(List.of(new Step.Explore(NeedChain.exploreMinutes(), "oak_log")), c.steps());
        assertTrue(c.why().startsWith("oak_log <- explore ("), c.why());
        assertFalse(c.why().matches("(?s).*\\d+(\\.\\d+)? s\\b.*"), "no price in the why line: " + c.why());
        assertFalse(c.why().contains("no way"), c.why());
    }

    static Map<String, List<Spot>> s4Seen() { return Map.of("oak_log", List.of(tree(30, 6)), "cobblestone", List.of(STONE_12)); }

    @Test
    void s4_threeCobblestoneTakeTheWoodenPickaxeAndDropTheLoopAndTheUnknownSources() {
        world("wooden", "stone", "iron", "golden");
        Chain c = NeedChain.resolve("cobblestone", 3, w(Map.of(), false, false, s4Seen()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(28.05, c.seconds(), TOL, c.why());
        String chain = chainPart(c.why());
        assertTrue(chain.startsWith("cobblestone <- dig cobblestone seen 12 m with wooden_pickaxe"), c.why());
        assertTrue(chain.contains("craft: 3 oak_planks") && chain.contains("2 stick") && chain.contains("table") && chain.contains("tree seen 30 m"), c.why());
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
        Chain c = NeedChain.resolve("oak_log", 1, w(Map.of(), false, false, Map.of("oak_log", List.of(tree(40, 5), tree(10, 5)))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(6.0, c.seconds(), TOL, c.why());
        assertTrue(c.why().startsWith("oak_log <- cut tree seen 10 m: 6.0 s"), c.why());
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
        Chain c = NeedChain.resolve("stone_pickaxe", 1, w(Map.of(), false, false, Map.of("oak_log", List.of(tree(10, 6)), "cobblestone", List.of(stone16))));
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
     * (one-planner: coal is coal, the torch's ingredient is the game's coals tag; charcoal, the other member, would need 7 logs
     * of a 6-log tree: it ends in an explore and stands no chance)
     * </pre>
     */
    @Test
    void s10_torchesFromNothingDigCoalWithAWoodenPickaxeAndNothingMore() {
        toolWorld();
        Spot coal = new Spot(0, 40, 20, 20, "minecraft:coal_ore", 4);
        Spot stone16 = new Spot(0, 60, 16, 16, "minecraft:stone", 40);
        assertEquals(16, (int) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("torch.target"));
        Chain c = NeedChain.resolve("torch", 16, w(Map.of(), false, false, Map.of("oak_log", List.of(tree(10, 6)), "coal", List.of(coal), "cobblestone", List.of(stone16))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(30.48, c.seconds(), TOL, c.why());
        String chain = chainPart(c.why());
        assertFalse(chain.contains("stone_pickaxe") || chain.contains("furnace"), "wood tier digs coal ore: " + c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Smelt || s instanceof Step.Explore), c.steps().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft k && (k.item().contains("stone_pickaxe") || k.item().contains("furnace"))), c.steps().toString());
        assertTrue(chain.contains("dig coal seen 20 m with wooden_pickaxe"), c.why());
        assertFalse(c.why().contains("fuel"), "no family word: " + c.why());
        assertTrue(c.steps().contains(new Step.Collect("coal", 4)), "4 coal ore in one collect: " + c.steps());
        assertEquals(16, ((Step.Craft) c.steps().get(c.steps().size() - 1)).n(), "the torches are the last craft: " + c.steps());
        assertEquals("torch", ((Step.Craft) c.steps().get(c.steps().size() - 1)).item(), c.steps().toString());
        int wooden = -1, dig = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            Step s = c.steps().get(i);
            if (s instanceof Step.Craft k && k.item().equals("wooden_pickaxe")) wooden = i;
            else if (s instanceof Step.Collect k && k.item().equals("coal")) dig = i;
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
        Chain c = NeedChain.resolveAll("stone_tools", group, 1, w(Map.of(), false, false, Map.of("oak_log", List.of(tree(10, 12)), "cobblestone", List.of(stone16))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(1, c.steps().stream().filter(s -> s instanceof Step.PlaceNear p && p.item().equals("crafting_table")).count(), "one table: " + c.steps());
        assertEquals(1, c.steps().stream().filter(s -> s instanceof Step.Collect k && k.item().equals("cobblestone")).count(), "one trip to the stone: " + c.steps());
        assertEquals(0, c.steps().stream().filter(s -> s instanceof Step.Walk).count(), "chop B2 (2026-10-22): no walk step before a block source, the collect paths itself: " + c.steps());
        assertTrue(c.steps().contains(new Step.Collect("cobblestone", 11)), "3 + 3 + 2 + 2 + 1 cobblestone: " + c.steps());
        assertEquals(1, crafts(c, "wooden_pickaxe"), "the digging pickaxe is made once: " + c.steps());
        for (String t : List.of("stone_pickaxe", "stone_axe", "stone_hoe", "stone_sword", "stone_shovel")) assertEquals(1, crafts(c, t), t + ": " + c.steps());
        assertFalse(chainPart(c.why()).contains("golden") || chainPart(c.why()).contains("iron") || chainPart(c.why()).contains("diamond"), "only the stone group: " + c.why());
    }

    /** S12/S13: a tree 10 m (12 logs) and stone 16 m known. */
    static Map<String, List<Spot>> s12Seen() {
        return Map.of("oak_log", List.of(tree(10, 12)), "cobblestone", List.of(new Spot(0, 60, 16, 16, "minecraft:stone", 40)));
    }

    /**
     * S12 (owner ruling 2026-10-21 a): 16 torches, a tree 10 m (12 logs) and stone 16 m known, NO coal known: the charcoal path. The
     * torch's ingredient is the game's coals tag (coal | charcoal); coal has no known source, so charcoal: 4 logs smelted in a furnace
     * (8 cobblestone, a wooden pickaxe, a table), the burn from the fuel table (3 planks of 300 ticks for 4 smelts of 200, left in the bag).
     * <pre>
     * walk to the tree 10 m 3.01; 8 logs x 3.0 = 24.0 (4 charcoal, 4 for planks: pickaxe, its sticks, the table, the torches' sticks)
     * crafts: planks x4 1.2, sticks x2 0.6, table 0.3, wooden pickaxe 0.3, furnace 0.3, torches x4 1.2 = 3.9; places: table, furnace 0.3
     * walk to the stone 16 m 4.82; 8 cobblestone x 1.5 (wooden pickaxe: 2 x 30 / 2 = 30 ticks) = 12.0; walk back to the table 4.82
     * smelt 4 x 10 = 40.0 (the bot waits); the burn: 3 planks already in the bag, 0
     * total 3.01 + 24.0 + 3.9 + 0.3 + 4.82 + 12.0 + 4.82 + 40.0 = 92.85 s
     * </pre>
     */
    @Test
    void s12_torchesWithNoCoalKnownTakeTheCharcoalPathAndTheFuelTable() {
        world("wooden", "stone");
        Chain c = NeedChain.resolve("torch", 16, w(Map.of(), false, false, s12Seen()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(92.85, c.seconds(), TOL, c.why());
        String chain = chainPart(c.why());
        assertTrue(chain.startsWith("torch <- craft: 4 charcoal <- smelt 4 oak_log <- cut tree seen 10 m + furnace <- craft: 8 cobblestone"), c.why());
        assertTrue(chain.contains("burn 3 oak_planks (in the bag)"), "planks burn, from the game's fuel table: " + c.why());
        assertFalse(c.why().contains("fuel"), "no family word anywhere: " + c.why());
        List<Class<?>> order = List.of(Step.ChopTree.class, Step.PlaceNear.class, Step.Collect.class, Step.Smelt.class);
        int last = -1;
        for (Class<?> k : order) {
            int at = -1;
            for (int i = 0; i < c.steps().size(); i++) if (k.isInstance(c.steps().get(i))) { at = i; break; }
            assertTrue(at > last, k.getSimpleName() + " in order: " + c.steps());
            last = at;
        }
        assertTrue(c.steps().contains(new Step.PlaceNear("furnace")), c.steps().toString());
        assertEquals(new Step.Smelt("charcoal", 4, true), c.steps().stream().filter(s -> s instanceof Step.Smelt).findFirst().orElseThrow());
        assertEquals(new Step.Craft("torch", 16), c.steps().get(c.steps().size() - 1));
    }

    /**
     * S13: S12 plus coal ore 20 m (4 blocks): the coal path (S10's chain, 30.48 s) beats charcoal, which is listed with its seconds:
     * the charcoal trial is S12 without the torches' own sticks and crafts (1 log 3.0 + planks 0.3 + sticks 0.3 + torches 1.2 = 4.8):
     * 92.85 - 4.8 = 88.05 s. No furnace in the chain.
     */
    @Test
    void s13_coalKnownBeatsCharcoalAndCharcoalIsDroppedWithItsSeconds() {
        world("wooden", "stone");
        Map<String, List<Spot>> seen = new java.util.HashMap<>(s12Seen());
        seen.put("coal", List.of(new Spot(0, 40, 20, 20, "minecraft:coal_ore", 4)));
        Chain c = NeedChain.resolve("torch", 16, w(Map.of(), false, false, seen));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(30.48, c.seconds(), TOL, c.why());
        assertTrue(chainPart(c.why()).contains("4 coal <- dig coal seen 20 m with wooden_pickaxe"), c.why());
        assertFalse(chainPart(c.why()).contains("furnace"), c.why());
        String charcoal = c.dropped().stream().filter(d -> d.startsWith("charcoal: ")).findFirst().orElseThrow(() -> new AssertionError(c.dropped().toString()));
        assertTrue(charcoal.startsWith("charcoal: 88.0 s (dearer than coal)"), charcoal);
    }

    /** S14: the food need's world: the game's loot data says a cow drops 2 beef a kill, a wheat crop 1 wheat. */
    static io.github.mojolowjo.entropybot.aa.brain.LootBook foodBook() {
        return new io.github.mojolowjo.entropybot.aa.brain.LootBook(List.of(
                new io.github.mojolowjo.entropybot.aa.brain.LootBook.Source(io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.MOB, "minecraft:cow", "minecraft:beef", 2, 1, false, null),
                new io.github.mojolowjo.entropybot.aa.brain.LootBook.Source(io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.BLOCK, "minecraft:wheat", "minecraft:wheat", 1, 1, false, null)), "test");
    }

    static Chain food(World w) {
        return NeedChain.resolveAny("food", io.github.mojolowjo.entropybot.aa.brain.ItemWorth.plainFoods(), 8, w, io.github.mojolowjo.entropybot.aa.brain.ItemWorth::energy);
    }

    /**
     * S14: food 8 with a cow known 15 m (the mob source: an animal of that kind known) and no crops. The members compare by seconds a
     * point of food (nutrition + saturation, the FOOD data) with the eating counted (32 ticks = 1.6 s an item, game fact).
     * <pre>
     * hunt: walk 15 m 4.52 + 4 kills (8 beef, 2 a kill) x ceil(10 hp / 1) x 0.25 s = 10.0                    = 14.52 s (raw)
     * cook (a furnace known, a coal in the bag): + walk back 15 m 4.52 + smelt 8 x 10 = 80                   = 99.03 s
     * a point: raw (14.52 + 8 x 1.6) / (8 x 4.8) = 0.71; cooked (99.03 + 12.8) / (8 x 20.8) = 0.67: cooked
     * no furnace known (and no stone to make one): cooked ends in an explore, never competes: raw 14.52 s
     * only a wheat crop 10 m known: 8 bread = 24 wheat: walk 3.01 + 24 x 3.0 (no hardness in the data: needChain.unknownBreakS)
     *   + 8 crafts 2.4 = 77.41 s
     * </pre>
     */
    @Test
    void s14_foodIsTheHuntAndTheCookWhenAFurnaceIsKnownElseRawAndACropElse() {
        world("wooden", "stone").mob("cow", 10, Double.NaN, io.github.mojolowjo.entropybot.aa.brain.ItemWorth.MobMove.WALK, false).install();
        var cow = new NeedChain.Mob("minecraft:cow", 15, 64, 0, 15);
        java.util.function.Function<String, List<NeedChain.Mob>> cows = t -> t.equals("minecraft:cow") ? List.of(cow) : List.of();
        Chain cooked = food(w(Map.of("minecraft:coal", 1), false, true, Map.of()).withMobs(cows, foodBook()));
        assertTrue(cooked.ok() && !cooked.explore(), cooked.why());
        assertEquals(99.03, cooked.seconds(), TOL, cooked.why());
        assertTrue(cooked.why().startsWith("food <- 8 cooked_beef <- smelt 8 beef <- hunt 4 cow seen 15 m"), cooked.why());
        assertEquals(new Step.HuntAt("minecraft:cow", 4, new int[]{15, 0}, "beef", 8).text(), cooked.steps().get(0).text());
        assertTrue(cooked.dropped().stream().anyMatch(d -> d.startsWith("beef: 14.5 s, 0.71 s a point")), cooked.dropped().toString());
        Chain raw = food(w(Map.of(), false, false, Map.of()).withMobs(cows, foodBook()));
        assertTrue(raw.ok() && !raw.explore(), raw.why());
        assertEquals(14.52, raw.seconds(), TOL, raw.why());
        assertTrue(raw.why().startsWith("food <- 8 beef <- hunt 4 cow seen 15 m: 14.5 s"), raw.why());
        Chain crop = food(w(Map.of(), false, false, Map.of("wheat", List.of(new Spot(10, 64, 0, 10, "minecraft:wheat", 30)))).withMobs(t -> List.of(), foodBook()));
        assertTrue(crop.ok() && !crop.explore(), crop.why());
        assertEquals(77.41, crop.seconds(), TOL, crop.why());
        assertTrue(crop.why().startsWith("food <- 8 bread <- craft: 24 wheat <- dig wheat seen 10 m"), crop.why());
    }

    /**
     * S15: `aa want diamond_chestplate` (a single item through the chain, never the old planner or chest loot). With 8 diamond blocks in
     * the bag and a table known: 9 diamonds from a block (one craft, 0.3 s), the chestplate (8 diamonds, 0.3 s) = 0.6 s, nothing else.
     * With no diamonds: the diamond's only source is a block (diamond ore, the loot data) nothing knows: one Baritone goal to the band it
     * generates at (worldgen, -64..16), then the explore, no price, and no chest-loot talk.
     */
    @Test
    void s15_aSingleItemOrderIsTheChainAndNoDiamondsIsTheExploreAtItsBand() {
        TestData d = world("wooden", "stone");
        d.recipe("diamond", 9, "diamond_block").recipe("diamond_block", 1, java.util.Collections.nCopies(9, "diamond").toArray(new String[0]));
        d.tableItems.add(TestData.id("diamond_chestplate"));
        d.install();
        Chain c = NeedChain.resolve("diamond_chestplate", 1, w(Map.of("minecraft:diamond_block", 8), true, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(0.6, c.seconds(), TOL, c.why());
        assertEquals(List.of(new Step.Craft("diamond", 9), new Step.Craft("diamond_chestplate", 1)), c.steps(), "nothing else");
        Chain none = NeedChain.resolve("diamond_chestplate", 1, w(Map.of(), true, false, Map.of()).withBand(i -> i.equals("minecraft:diamond") ? new int[]{-64, 16} : null));
        assertTrue(none.ok() && none.explore(), none.why());
        assertEquals(List.of(new Step.ToBand(-64, 16), new Step.Explore(NeedChain.exploreMinutes(), "diamond")), none.steps());
        assertTrue(none.why().contains("explore (no diamond source known; it generates at Y -64..16, going there first)"), none.why());
        assertFalse(none.why().contains("chests") || none.why().contains("treasure") || none.why().contains("still searching"), "no chest-loot talk, no planner: " + none.why());
    }

    /**
     * S16: place-near. The fork's placeNear answers FAILED("no cell within 3 blocks"): the hands' step fails with that reason at once
     * (no 40-s stuck), the run ends FAILED naming it, and the need's chain row is held so the next pick prices again.
     */
    @Test
    void s16_aPlaceNearTheForkCannotDoFailsTheStepWithItsReasonAndTheChainIsPricedAgain() {
        // the fork's answer through the job hands
        final String no = "place crafting_table near me: no cell within 3 blocks";
        var hand = new io.github.mojolowjo.entropybot.aa.moves.JobHands.Hand() {
            String placed;
            @Override public void start(List<int[]> dig, List<io.github.mojolowjo.entropybot.aa.moves.JobHands.Click> clicks, int t) {}
            @Override public void startBuild(List<int[]> cells, List<String> items, int t, boolean replace) {}
            @Override public void startPlaceNear(String item, int radius, int t) { placed = item + " r" + radius; }
            @Override public boolean reflexActive() { return false; }
            @Override public io.github.mojolowjo.entropybot.aa.moves.Car.Status status() { return io.github.mojolowjo.entropybot.aa.moves.Car.Status.failed("stage build: " + no); }
            @Override public void cancel(String why) {}
            @Override public String verdict(String action, int[] cell) { return ""; }
            @Override public void look(double x, double y, double z) {}
        };
        var hands = new io.github.mojolowjo.entropybot.aa.moves.JobHands(hand, () -> 0L);
        var r = hands.placeNear("crafting_table", 3, 200, "steps");
        assertTrue(r.running(), r.why());
        assertEquals("crafting_table r3", hand.placed, "the mod names the item and the radius, never a cell");
        var end = hands.placeNear("crafting_table", 3, 200, "steps");
        assertTrue(end.failed() && end.why().contains("no cell within 3 blocks"), end.why());
        // the step fails at once with that reason (Steps over hands that answer as StepHands does)
        var steps = new io.github.mojolowjo.entropybot.aa.moves.Steps(new io.github.mojolowjo.entropybot.aa.moves.Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public io.github.mojolowjo.entropybot.aa.moves.Car.Status status(Step s) {
                return s instanceof Step.PlaceNear ? io.github.mojolowjo.entropybot.aa.moves.Car.Status.failed(end.why()) : io.github.mojolowjo.entropybot.aa.moves.Car.Status.done("ok");
            }
            @Override public void cancel(String why) {}
        });
        steps.start("chain obtain 1 stone_pickaxe", List.of(new Step.Craft("crafting_table", 1), new Step.PlaceNear("crafting_table"), new Step.Craft("wooden_pickaxe", 1)), 0);
        steps.tick(1);
        steps.tick(2);
        assertEquals(io.github.mojolowjo.entropybot.aa.moves.Steps.Phase.FAILED, steps.phase(), steps.why());
        assertTrue(steps.why().startsWith("stopped at step 2 (place crafting_table near me)") && steps.why().contains("no cell within 3 blocks"), steps.why());
        assertEquals("chain obtain 1 stone_pickaxe", steps.failedKey());
        // the need: the chain row fails with that reason and is held; the next pick prices again (no stuck wait)
        world("wooden", "stone");
        var clock = new io.github.mojolowjo.entropybot.aa.core.Clock.Fake(10_000_000);
        var port = new io.github.mojolowjo.entropybot.aa.core.FakePort();
        var bb = new io.github.mojolowjo.entropybot.aa.core.Blackboard(clock, port, new io.github.mojolowjo.entropybot.aa.core.Tracer());
        bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<World>) () -> w(Map.of("minecraft:oak_log", 8), false, false, s4Seen()));
        Need n = new Need("needs.table", io.github.mojolowjo.entropybot.aa.core.TaskKind.TABLE_FURNACE, b -> true, b -> null, b -> List.of(new Step.Obtain("stone_pickaxe", 1)));
        assertEquals(io.github.mojolowjo.entropybot.aa.core.Status.RUNNING, n.tick(bb));
        port.fail(1, steps.why());
        assertEquals(io.github.mojolowjo.entropybot.aa.core.Status.FAILURE, n.tick(bb), "a failed step ends the task: the need fails with its reason, no wait");
        assertTrue(n.reason().contains("no cell within 3 blocks"), n.reason());
        clock.advance(250);
        n.tick(bb);
        assertTrue(n.options().contains("dropped: chain: ") && n.options().contains("no cell within 3 blocks"), "priced again: the failed chain held with its reason: " + n.options());
        assertEquals(1, port.started.size(), "held: not run again at once");
    }

    /**
     * S17 (chop, owner 2026-10-22): cut 3 logs, the nearest tree 10 m with 8 logs in a canopy. The chain has no walk step (the
     * chop paths itself; the spot only names the blocks and gives the price) and one cut of 3 at the tree. The fork's chop takes
     * the whole tree (its ChopTreeTest: quantity 3 keeps all 8 as targets, ends "have 8 valid items"), so the bag ends with 8.
     * <pre>
     * the chain (what AA prices, unchanged rule):  walk 10 m 3.01 (10 x 1.3 / 4.317) + 3 logs x 3.0 = 9.0     = 12.01 s
     * the run (what the chop does):               walk 10 m 3.01 + 8 logs x 3.0 = 24.0                       = 27.01 s
     * </pre>
     * The chain still prices the logs wanted (S2, S4, S9, S10, S12, S13 hold their hand numbers on that rule); pricing the
     * whole tree is the owner's call (it would change those numbers).
     */
    @Test
    void s17_cutThreeLogsIsOneCutWithNoWalkAndTheChopTakesTheWholeTree() {
        world("wooden");
        Chain c = NeedChain.resolve("oak_log", 3, w(Map.of(), false, false, Map.of("oak_log", List.of(tree(10, 8)))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Walk), "no walk step: " + c.steps());
        assertEquals(1, c.steps().size(), c.steps().toString());
        Step.ChopTree cut = (Step.ChopTree) c.steps().get(0);
        assertEquals(3, cut.logs());
        assertArrayEquals(new int[]{10, 64, 0}, cut.at());
        assertEquals("cut at 10 64 0", NeedChain.sourceKey(cut), "its source key is the tree");
        assertEquals(12.01, c.seconds(), TOL, c.why());
        double walk = NeedChain.walkS(10), perLog = (c.seconds() - walk) / cut.logs();
        assertEquals(3.0, perLog, TOL);
        assertEquals(27.01, walk + 8 * perLog, TOL, "the run: the chop takes all 8 logs of the tree");
    }

    /**
     * S18 (chop C): `aa only torches`, the torches chain held (its need fails "held"), a wood stock-up (60 x 0.25 = 15 before
     * the only weight) and want.explore (20) silenced to 0. A 0 never starts: the brain is idle, one wait, nothing else.
     * Before: the 0s started and were ended "its reason is gone" 5 s later, every 5 s.
     */
    @Test
    void s18_underOnlyTorchesWithTheTorchesChainHeldNothingElseStartsTheBrainWaits() {
        var clock = new io.github.mojolowjo.entropybot.aa.core.Clock.Fake(10_000_000);
        var port = new io.github.mojolowjo.entropybot.aa.core.FakePort();
        var bb = new io.github.mojolowjo.entropybot.aa.core.Blackboard(clock, port, new io.github.mojolowjo.entropybot.aa.core.Tracer());
        io.github.mojolowjo.entropybot.aa.brain.Only.set("torches");
        try {
            double[] sc = new double[io.github.mojolowjo.entropybot.aa.core.TaskKind.values().length];
            sc[io.github.mojolowjo.entropybot.aa.core.TaskKind.STOCK_WOOD.ordinal()] = 60;
            sc[io.github.mojolowjo.entropybot.aa.core.TaskKind.WAIT.ordinal()] = AaScorer.waitScore();
            io.github.mojolowjo.entropybot.aa.core.Scorer scorer = in -> sc.clone();
            List<Map.Entry<io.github.mojolowjo.entropybot.aa.core.TaskKind, io.github.mojolowjo.entropybot.aa.core.Node>> idle = List.of(
                    Map.entry(io.github.mojolowjo.entropybot.aa.core.TaskKind.STOCK_WOOD, io.github.mojolowjo.entropybot.aa.core.RunJob.of("idle.wood",
                            io.github.mojolowjo.entropybot.aa.core.TaskKind.STOCK_WOOD, List.of(new Step.Obtain("oak_log", 4)), "idle")),
                    Map.entry(io.github.mojolowjo.entropybot.aa.core.TaskKind.WAIT, io.github.mojolowjo.entropybot.aa.core.RunJob.of("idle.wait",
                            io.github.mojolowjo.entropybot.aa.core.TaskKind.WAIT, List.of(new Step.Wait(10)), "idle")));
            var heldTorches = new io.github.mojolowjo.entropybot.aa.core.Node("needs.torches.held", "the torches need, its chain held") {
                @Override protected io.github.mojolowjo.entropybot.aa.core.Status run(io.github.mojolowjo.entropybot.aa.core.Blackboard b) {
                    return fail("TORCHES: no way can run (chain: held: cut 3 logs: none in range (9 min))");
                }
            };
            var torches = new io.github.mojolowjo.entropybot.aa.brain.Desire("needs.torches", io.github.mojolowjo.entropybot.aa.brain.Desire.Kind.UPKEEP, heldTorches, b -> 40).cost(0);
            var explore = new io.github.mojolowjo.entropybot.aa.brain.Desire("want.explore", io.github.mojolowjo.entropybot.aa.brain.Desire.Kind.WANT,
                    io.github.mojolowjo.entropybot.aa.core.RunJob.of("want.explore.job", io.github.mojolowjo.entropybot.aa.core.TaskKind.WAIT, List.of(new Step.Explore(1, "anything")), "want"), b -> 20).cost(0);
            var br = new io.github.mojolowjo.entropybot.aa.brain.Brain("brain", "t", torches, explore, AaDesires.idleSet(idle, scorer));
            for (int i = 0; i < 12; i++) {           // a minute of picks, 5 s apart
                br.tick(bb);
                clock.advance(5000);
            }
            assertEquals(1, port.started.size(), "one start in a minute: " + port.started);
            assertEquals(List.of(new Step.Wait(10)), port.started.get(0).steps(), "the brain is idle: a plain wait");
            assertTrue(br.scoreAll(bb).stream().filter(s -> s.d().id.equals("want.explore")).allMatch(s -> s.skip() != null && s.skip().startsWith("not wanted")),
                    "a 0 is not wanted: " + br.scoreAll(bb));
        } finally {
            io.github.mojolowjo.entropybot.aa.brain.Only.set("off");
        }
    }

    /**
     * S19 (chop D): cut 3 logs, tree A 10 m and tree B 40 m (8 logs each). The cut at A fails "none in range": that source is
     * held by its key (cut at 10 64 0), the chain is priced again at once and runs B; the chain row and B are not held.
     * <pre>
     * first:  walk 10 m 3.01 + 3 x 3.0 = 12.01 s (A)
     * again:  walk 40 m 12.04 (40 x 1.3 / 4.317) + 3 x 3.0 = 21.04 s (B, A out)
     * </pre>
     */
    @Test
    void s19_aCutThatFailsNoneInRangeRepricesToTheNextTreeAtOnceAndHoldsOnlyThatTree() {
        world("wooden");
        Spot a = tree(10, 8), b = tree(40, 8);
        World both = w(Map.of(), false, false, Map.of("oak_log", List.of(a, b)));
        assertEquals(12.01, NeedChain.resolve("oak_log", 3, both).seconds(), TOL);
        assertEquals(21.04, NeedChain.resolve("oak_log", 3, both.without((item, sp) -> sp.x() == 10)).seconds(), TOL, "B with A out");
        var clock = new io.github.mojolowjo.entropybot.aa.core.Clock.Fake(10_000_000);
        var port = new io.github.mojolowjo.entropybot.aa.core.FakePort();
        var bb = new io.github.mojolowjo.entropybot.aa.core.Blackboard(clock, port, new io.github.mojolowjo.entropybot.aa.core.Tracer());
        bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<World>) () -> both);
        Step.Obtain goal = new Step.Obtain("oak_log", 3);
        Need n = new Need("needs.wood", io.github.mojolowjo.entropybot.aa.core.TaskKind.STOCK_WOOD, x -> true, x -> null, x -> List.of(goal));
        var f = new io.github.mojolowjo.entropybot.aa.brain.Failures();
        n.bindFailures(f);
        assertEquals(io.github.mojolowjo.entropybot.aa.core.Status.RUNNING, n.tick(bb));
        Step first = port.started.get(0).steps().get(0);
        assertEquals("cut 3 at 10 64 0", first.text());
        port.fail(1, "stopped at step 1 (cut 3 at 10 64 0): cut 3 logs: none in range (got 0 of 3)", first);
        assertEquals(io.github.mojolowjo.entropybot.aa.core.Status.RUNNING, n.tick(bb), "re-priced at once, no hold of the need");
        assertEquals(2, port.started.size(), "the next source started in the same tick: " + port.started);
        assertEquals("cut 3 at 40 64 0", port.started.get(1).steps().get(0).text());
        long now = clock.now();
        assertTrue(f.blocked("needs.wood", "cut at 10 64 0", now), f.describe(now).toString());
        assertTrue(f.reason("needs.wood", "cut at 10 64 0").contains("none in range"));
        assertFalse(f.blocked("needs.wood", "cut at 40 64 0", now), "B is not held");
        assertNull(f.held("needs.wood", Ways.chainKey(goal), now), "the chain row is not held for one tree");
        assertEquals(1, f.heldCount(now), "Failures holds A only: " + f.describe(now));
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