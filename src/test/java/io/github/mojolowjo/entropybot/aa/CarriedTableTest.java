package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner 2026-10-29 ("the crafting table keeps on itself": in the bag unless the next step crafts at it; two crafts back to
 * back leave it down): the chain picks the table it placed up after the last craft of a run at it ("take my crafting_table
 * here", priced at its break), and the next craft at a table places it near me again instead of walking back.
 */
class CarriedTableTest {
    static final String TAKE = "take my crafting_table here", PLACE = "place crafting_table near me";

    static boolean tableCraft(Step s) { return s instanceof Step.Craft k && k.table(); }

    /** The stone pickaxe from nothing (S4's world): a table craft run, the dig for cobblestone, the pickaxe's craft - the take between, the place before, the take last. */
    @Test
    void theTableIsTakenAfterARunOfCraftsAndPlacedAgainForTheNext() {
        ScenariosTest.world("wooden", "stone", "iron", "golden");
        Chain c = NeedChain.resolve("stone_pickaxe", 1, ScenariosTest.w(Map.of(), false, false, ScenariosTest.s4Seen()));
        assertTrue(c.ok() && !c.explore(), c.why());
        List<Step> st = c.steps();
        List<String> texts = st.stream().map(Step::text).toList();
        assertTrue(texts.contains(TAKE), texts.toString());
        assertEquals(TAKE, texts.get(texts.size() - 1), "the chain ends on the pickaxe's craft at the table: the table comes along: " + texts);
        boolean inBag = false;                                   // after a take, until the next place
        for (int i = 0; i < st.size(); i++) {
            Step s = st.get(i);
            if (s instanceof Step.TakeNear) {
                assertTrue(i > 0 && tableCraft(st.get(i - 1)), "a take follows a craft at the table: " + texts);
                assertTrue(i == st.size() - 1 || !tableCraft(st.get(i + 1)), "no take between two crafts at the table: " + texts);
                assertFalse(inBag, "no second take before a place: " + texts);
                inBag = true;
            } else if (s instanceof Step.PlaceNear p && p.item().equals("crafting_table")) {
                inBag = false;
            } else if (tableCraft(s)) {
                assertFalse(inBag, "a craft at the table after a take has its place before it: " + texts);
            }
        }
        assertTrue(c.why().contains("placed"), c.why());
    }

    /** The table and the pickaxe's parts in the bag: place, craft, take - nothing else; the take is priced (the chain's seconds grow past place + craft). */
    @Test
    void aChainEndingOnATableCraft_takesTheTableLast() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("stone_pickaxe", 1, ScenariosTest.w(Map.of("minecraft:crafting_table", 1, "minecraft:cobblestone", 3, "minecraft:stick", 2), false, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(List.of(PLACE, "craft stone_pickaxe 1", TAKE), c.steps().stream().map(Step::text).toList());
        assertTrue(((Step.Craft) c.steps().get(1)).table(), "the pickaxe's craft says it crafts at the table");
        assertTrue(c.seconds() > 0.45 + 0.5, "the take's break is priced (place 0.15 + craft 0.3 + the break): " + c.seconds() + " s");
        assertEquals(3, c.stepSeconds().size());
    }

    /** A craft needing no table (sticks from planks, the 2x2 grid) places and takes nothing. */
    @Test
    void aCraftWithoutTheTable_carriesNothing() {
        ScenariosTest.world("wooden");
        Chain c = NeedChain.resolve("stick", 4, ScenariosTest.w(Map.of("minecraft:oak_planks", 4), false, false, Map.of()));
        assertTrue(c.ok(), c.why());
        assertEquals(List.of("craft stick 4"), c.steps().stream().map(Step::text).toList());
        assertFalse(((Step.Craft) c.steps().get(0)).table());
    }
}
