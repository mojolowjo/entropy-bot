package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The strip order's deposit leg: the chest choice (known / bag / craft) and the kept set. */
class DepositRuleTest {

    @Test
    void theNearestKnownChestByPathCostWins() {
        int[] near = {0, 64, 10}, far = {0, 64, 3};
        // the line says "far" is nearer, Baritone's path cost says "near" is: the path cost decides
        var c = DepositRule.choose(List.of(far, near), p -> p == far ? 90.0 : 12.0, true, true);
        assertEquals(DepositRule.How.KNOWN, c.how());
        assertArrayEquals(near, c.at());
    }

    @Test
    void aKnownChestWithNoPathIsPassedOver() {
        int[] a = {1, 2, 3};
        var c = DepositRule.choose(List.of(a), p -> null, true, false);
        assertEquals(DepositRule.How.BAG, c.how());
        assertTrue(c.why().contains("no path"));
        var f = DepositRule.choose(List.of(a), p -> null, false, false);
        assertEquals(DepositRule.How.FAIL, f.how());
        assertTrue(f.why().startsWith("no path to any known chest"));
    }

    @Test
    void noneKnownTakesTheBagsChestThenCraftsOne() {
        assertEquals(DepositRule.How.BAG, DepositRule.choose(List.of(), p -> 1.0, true, true).how());
        assertEquals(DepositRule.How.CRAFT, DepositRule.choose(List.of(), p -> 1.0, false, true).how());
        var none = DepositRule.choose(List.of(), p -> 1.0, false, false);
        assertEquals(DepositRule.How.FAIL, none.how());
        assertEquals("no chest known, and no chest or 8 planks in the bag", none.why());
    }

    @Test
    void notLoadedWaitsNeverNone() {
        assertEquals(DepositRule.How.WAIT, DepositRule.choose(null, p -> 1.0, true, true).how());
    }

    @Test
    void theKeptSetComesFromItemData() {
        var bag = List.of(
                new DepositRule.Item("minecraft:iron_pickaxe", 1, true, false, false, false),
                new DepositRule.Item("minecraft:bread", 5, false, true, false, false),
                new DepositRule.Item("minecraft:crafting_table", 1, false, false, true, false),
                new DepositRule.Item("minecraft:furnace", 1, false, false, true, false),
                new DepositRule.Item("minecraft:torch", 20, false, false, false, true),
                new DepositRule.Item("minecraft:coal", 30, false, false, false, false),
                new DepositRule.Item("minecraft:cobbled_deepslate", 64, false, false, false, false),
                new DepositRule.Item("minecraft:coal", 6, false, false, false, false),
                new DepositRule.Item("minecraft:raw_iron", 3, false, false, false, false));
        assertEquals(Map.of("minecraft:coal", 36, "minecraft:cobbled_deepslate", 64, "minecraft:raw_iron", 3), DepositRule.toPut(bag));
    }

    @Test
    void failureTexts() {
        assertEquals("chest at 1 2 3 full: 7 items left", DepositRule.full(new int[]{1, 2, 3}, 7));
        assertEquals("chest at 1 2 3 full: 1 item left", DepositRule.full(new int[]{1, 2, 3}, 1));
        assertEquals("", DepositRule.Deposits.NONE.line());
        assertEquals("2 deposits (50 items at 4 5 6)", DepositRule.Deposits.NONE.add(20, new int[]{1, 1, 1}).add(30, new int[]{4, 5, 6}).line());
    }
}
