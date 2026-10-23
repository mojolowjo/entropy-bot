package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z16, owner-watched 2026-10-22 (LAN flat world, three 3-log trunks 15 m, stone, no coal): `aa want torch` explored for
 * coal ore through "coal <- coal_block <- coal" and never named charcoal. The book: #coals = coal | charcoal; charcoal smelts
 * from #logs_that_burn, coal from #coal_ores; coal crafts from a coal_block, a coal_block from 9 coal.
 */
class CoalsWaysTest {

    @BeforeEach
    void book() {
        TestData d = TestData.planner();
        for (String m : List.of("wooden", "stone")) d.tag(m + "_pickaxe", "minecraft:pickaxes");
        d.recipe("coal_block", 1, java.util.Collections.nCopies(9, "coal").toArray(new String[0]));
        d.recipe("coal", 9, "coal_block");
        d.tableItems.add(TestData.id("coal_block"));
        d.tag("coal_ore", "minecraft:coal_ores").tag("deepslate_coal_ore", "minecraft:coal_ores");
        d.smelt("coal", "#minecraft:coal_ores");
        d.install();
    }

    @AfterEach
    void clear() { TestData.clear(); }

    static final List<Spot> TREES = List.of(new Spot(15, 64, 0, 15, "minecraft:oak_log", 3), new Spot(0, 64, 15, 15, "minecraft:oak_log", 3),
            new Spot(-15, 64, 0, 15, "minecraft:oak_log", 3));
    static final Spot STONE = new Spot(8, 60, 0, 8, "minecraft:stone", 8);
    static final Spot COAL_ORE = new Spot(10, 40, 0, 10, "minecraft:coal_ore", 4);

    static String chainPart(String why) { int k = why.indexOf(" | dropped:"); return k < 0 ? why : why.substring(0, k); }

    static Chain torch(Map<String, List<Spot>> seen) { return NeedChain.resolve("torch", 1, ScenariosTest.w(Map.of(), false, false, seen)); }

    /** (a) trees and stone known, no coal: the chain is charcoal, named in the line; the coal ways are each in the dropped list. */
    @Test
    void theOwnersWorldTakesCharcoalAndNamesEveryOtherWay() {
        Chain c = torch(Map.of("oak_log", TREES, "cobblestone", List.of(STONE)));
        System.out.println("WHY-LINE (a) " + c.why());
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(chainPart(c.why()).startsWith("torch <- craft: 1 charcoal <- smelt "), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Smelt m && m.item().equals("charcoal")), c.steps().toString());
        assertFalse(chainPart(c.why()).contains("coal_block"), "the circle is never a chosen way: " + c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("coal from coal_block: loop: coal needs itself")), c.dropped().toString());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("smelting coal from #coal_ores: no known source for #coal_ores")), c.dropped().toString());
        assertTrue(c.dropped().stream().noneMatch(d -> d.contains("|")), "no joined need word: " + c.dropped());
    }

    /** (a) with a coal ore known 10 m too: both ways are priced, one is chosen, the other is in the dropped list with its seconds. */
    @Test
    void withACoalOreKnownBothWaysArePricedAndTheLoserHasItsSeconds() {
        Map<String, List<Spot>> seen = new HashMap<>(Map.of("oak_log", TREES, "cobblestone", List.of(STONE)));
        seen.put("coal", List.of(COAL_ORE));
        Chain c = torch(seen);
        System.out.println("WHY-LINE (a2) " + c.why());
        assertTrue(c.ok() && !c.explore(), c.why());
        String chain = chainPart(c.why());
        boolean coalWon = chain.startsWith("torch <- craft: 1 coal <- dig coal seen 10 m");
        boolean charcoalWon = chain.startsWith("torch <- craft: 1 charcoal <- smelt ");
        assertTrue(coalWon ^ charcoalWon, c.why());
        String loser = coalWon ? "charcoal: " : "coal: ";
        String other = c.dropped().stream().filter(d -> d.startsWith(loser)).findFirst().orElseThrow(() -> new AssertionError(c.dropped().toString()));
        assertTrue(other.matches(".*: \\d+\\.\\d s \\(dearer than (coal|charcoal)\\).*"), other);
        assertFalse(chain.contains("coal_block"), c.why());
    }

    /** (b) the circle coal <- coal_block <- coal is dropped on both sides: no chosen line goes through coal_block, whatever is known. */
    @Test
    void theCoalBlockCircleNeverAppearsInAChosenLine() {
        for (Map<String, List<Spot>> seen : List.<Map<String, List<Spot>>>of(Map.of(), Map.of("cobblestone", List.of(STONE)),
                Map.of("oak_log", TREES, "cobblestone", List.of(STONE)), Map.of("coal", List.of(COAL_ORE), "oak_log", TREES))) {
            Chain c = torch(seen);
            assertFalse(chainPart(c.why()).contains("coal_block"), c.why());
            assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft k && k.item().contains("coal_block")), c.steps().toString());
        }
        Chain block = NeedChain.resolve("coal_block", 1, ScenariosTest.w(Map.of(), false, false, Map.of()));
        assertTrue(block.dropped().stream().noneMatch(d -> d.isEmpty()), block.dropped().toString());
        assertFalse(chainPart(block.why()).contains("coal_block <- craft: 9 coal <- craft: 1 coal_block"), "coal_block <- coal <- coal_block is a loop: " + block.why());
    }

    /** (c) nothing known: the explore is for the whole source set of #coals and ends when a tree turns up; the chain then re-prices to charcoal. */
    @Test
    void theExploreForCoalsEndsWhenATreeBecomesKnown() {
        Chain c = torch(Map.of("cobblestone", List.of(STONE)));
        System.out.println("WHY-LINE (c) " + c.why() + " STEPS " + c.steps());
        assertTrue(c.ok() && c.explore(), c.why());
        Step.Explore ex = (Step.Explore) c.steps().stream().filter(s -> s instanceof Step.Explore).findFirst().orElseThrow();
        List<String> words = List.of(ex.until().split("\\|"));
        assertTrue(words.contains("#logs_that_burn") && words.contains("#coal_ores"), "the explore is for any source of #coals: " + ex);
        boolean[] treeKnown = {false};
        java.util.function.Function<String, Spot> live = w -> treeKnown[0] && NeedChain.word(w).equals("#minecraft:logs_that_burn") ? TREES.get(0) : null;
        assertNull(io.github.mojolowjo.entropybot.cave.ExploreRules.untilFound(ex.until(), live), "nothing known yet: the walk goes on");
        treeKnown[0] = true;
        String end = io.github.mojolowjo.entropybot.cave.ExploreRules.untilFound(ex.until(), live);
        assertNotNull(end, "a tree came into the scan: the explore ends");
        assertTrue(end.contains("#logs_that_burn"), end);
        Chain again = torch(Map.of("oak_log", TREES, "cobblestone", List.of(STONE)));
        assertTrue(again.ok() && !again.explore() && chainPart(again.why()).contains("charcoal"), "priced again at the step boundary: " + again.why());
    }

    /** grouping-rule: the planks' log words together are one game tag and stay one way; unrelated tags never join. */
    @Test
    void wordsAreOneGroupOnlyWhenTheyAreExactlyOneTag() {
        java.util.Set<String> logs = new java.util.TreeSet<>();
        for (String w : List.of("oak", "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak")) logs.add("minecraft:" + w + "_log");
        assertEquals("#minecraft:logs_that_burn", NeedChain.cover(logs));
        assertNull(NeedChain.cover(java.util.Set.of("#minecraft:coal_ores", "#minecraft:logs_that_burn")), "unrelated tags are no one need");
        Map<String, String> split = NeedChain.covers(java.util.Set.of("#minecraft:coal_ores", "#minecraft:logs_that_burn"));
        assertEquals("#minecraft:coal_ores", split.get("#minecraft:coal_ores"));
        assertEquals("#minecraft:logs_that_burn", split.get("#minecraft:logs_that_burn"));
        java.util.Set<String> mixed = new java.util.TreeSet<>(logs);
        mixed.add("#minecraft:coal_ores");
        Map<String, String> part = NeedChain.covers(mixed);
        for (String l : logs) assertEquals("#minecraft:logs_that_burn", part.get(l), part.toString());
        assertEquals("#minecraft:coal_ores", part.get("#minecraft:coal_ores"), part.toString());
    }
}
