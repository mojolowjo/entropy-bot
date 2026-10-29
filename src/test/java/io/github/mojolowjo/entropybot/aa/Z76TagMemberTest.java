package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z76 J3-mod item 1 (the tag-member family, made once in NeedChain's tag resolution): a tag need becomes a member where
 * something names it - the inputs a recipe group took (run AM 21:39:44: "craft #planks 8 - no member of #planks can be made
 * from what I have (11 members)" with a birch and an oak log in the bag, one second before "made 4 birch_planks"), or a
 * block's drop (z75 metric fix A's #coals: coal_ore -> coal; run AH's #stone_tool_materials: stone -> cobblestone). Hand
 * numbers: a craft batch 0.3 s; the fake walk 12 m x 1.3 / 4.317 = 3.61 s.
 */
class Z76TagMemberTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static Set<String> texts(Chain c) { return c.steps().stream().map(Step::text).collect(Collectors.toSet()); }

    /**
     * Run AM 21:39:44: 8 #planks with 1 birch log and 1 oak log in the bag. No one member makes 8 (4 a log), so the chain
     * names the two members its two logs make: craft birch_planks 4 and craft oak_planks 4, 2 batches = 0.6 s (was one
     * "craft #planks 8" the craft step refused: no member can be made 8 of).
     */
    @Test
    void am_mixedLogsMakeOneCraftPerMember() {
        ScenariosTest.tagWorld();
        Chain c = NeedChain.resolve("#minecraft:planks", 8, ScenariosTest.w(Map.of("minecraft:birch_log", 1, "minecraft:oak_log", 1), false, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(Set.of("craft birch_planks 4", "craft oak_planks 4"), texts(c), c.steps().toString());
        assertEquals(2, c.steps().size(), c.steps().toString());
        assertEquals(0.6, c.seconds(), 0.01, c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s.text().contains("#planks")), "no tag word left in a craft step: " + c.steps());
    }

    /** #planks with birch logs in the bag: birch_planks (the member the bag's inputs make), 8 from 2 logs, 0.6 s. */
    @Test
    void am_birchLogsMakeBirchPlanks() {
        ScenariosTest.tagWorld();
        Chain c = NeedChain.resolve("#minecraft:planks", 8, ScenariosTest.w(Map.of("minecraft:birch_log", 2), false, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(List.of(new Step.Craft("birch_planks", 8)), c.steps(), c.why());
        assertEquals(0.6, c.seconds(), 0.01, c.why());
    }

    /** #planks with no log and no tree known: the chain says why - the explore for any log (no silent stop, no member named). */
    @Test
    void am_noLogsTheChainSaysWhy() {
        ScenariosTest.tagWorld();
        Chain c = NeedChain.resolve("#minecraft:planks", 8, ScenariosTest.w(Map.of(), false, false, Map.of()));
        assertTrue(c.ok() && c.explore(), c.why());
        assertTrue(c.why().contains("no #logs source known"), c.why());
        assertEquals(List.of(new Step.Explore(NeedChain.exploreMinutes(), "#logs")), c.steps());
    }

    /** The members a recipe group makes from its inputs (pure): one craft per member, the way's token to the tag word, no mixed batch. */
    @Test
    void membersMadeByTheInputs() {
        ScenariosTest.tagWorld();
        Chain one = NeedChain.resolve("#minecraft:planks", 4, ScenariosTest.w(Map.of("minecraft:oak_log", 1), false, false, Map.of()));
        assertEquals(List.of(new Step.Craft("oak_planks", 4)), one.steps(), "one member: named as before: " + one.why());
        // a log from the bag and one the tree gives (the tree's own logs are taken first: one member, oak)
        Chain tree = NeedChain.resolve("#minecraft:planks", 8, ScenariosTest.w(Map.of("minecraft:birch_log", 1), false, false,
                Map.of("oak_log", List.of(ScenariosTest.treeOf("minecraft:oak_log", 10, 6)))));
        assertTrue(tree.ok() && !tree.explore(), tree.why());
        assertTrue(tree.steps().stream().noneMatch(s -> s instanceof Step.Craft k && k.item().startsWith("#")), tree.steps().toString());
    }

    /** A spot of a coal ore the lookup names no member for: the loot data's drop names it (coal), the collect says "coal". */
    @Test
    void coals_aBlocksDropNamesTheMember() {
        ScenariosTest.world("wooden", "stone");
        LootBook book = new LootBook(List.of(new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:coal_ore", "minecraft:coal", 1, 1, false, null)), "test");
        Function<String, List<Spot>> seen = word -> word.contains("coals") ? List.of(new Spot(12, 60, 3, 12, "minecraft:coal_ore", 0)) : List.of();
        World w = FakePaths.paths(new World(Map.of("minecraft:stone_pickaxe", 1), false, false, seen, null, null, null, 0)).withMobs(x -> List.of(), book);
        Chain c = NeedChain.resolve("#minecraft:coals", 2, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(List.of(new Step.Walk(12, 60, 3, Step.Collect.MINE_SIGHT), new Step.Collect("coal", 2)), c.steps(), "the member the ore drops (the spot 12 m: its walk first, FY fix): " + c.why());
    }

    /** Run AH: a stone spot for #stone_tool_materials (stone is no member; its drop, cobblestone, is): collect cobblestone. */
    @Test
    void stoneToolMaterials_stoneGivesCobblestone() {
        ScenariosTest.world("wooden", "stone");
        LootBook book = new LootBook(List.of(new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:stone", "minecraft:cobblestone", 1, 1, false, null)), "test");
        Function<String, List<Spot>> seen = word -> word.contains("stone_tool_materials") ? List.of(ScenariosTest.STONE_12) : List.of();
        World w = FakePaths.paths(new World(Map.of("minecraft:wooden_pickaxe", 1), false, false, seen, null, null, null, 0)).withMobs(x -> List.of(), book);
        Chain c = NeedChain.resolve("#minecraft:stone_tool_materials", 3, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(List.of(new Step.Walk(12, 60, 3, Step.Collect.MINE_SIGHT), new Step.Collect("cobblestone", 3)), c.steps(), "the spot 12 m: its walk first (FY fix): " + c.why());
    }
}
