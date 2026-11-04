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

    /** Run JAC (2026-11-03): the main chest 360 m off in unloaded chunks had only an estimate; the deposit crafted a chest instead. */
    @Test
    void aKnownChestWithOnlyAnEstimateIsWalkedToward() {
        int[] far = {2, 72, 0}, farther = {400, 72, 0};
        var c = DepositRule.choose(List.of(farther, far), p -> null, p -> 0.0, p -> p == far ? 180.0 : 400.0, false, true);
        assertEquals(DepositRule.How.KNOWN, c.how());
        assertArrayEquals(far, c.at());
        assertTrue(c.estimated());
        assertTrue(c.why().contains("estimate 180 s"), c.why());
        // a priced chest still beats any estimate (rule 18: a rank is never a price)
        int[] near = {0, 64, 10};
        var pr = DepositRule.choose(List.of(far, near), p -> p == near ? 30.0 : null, p -> 0.0, p -> p == far ? 5.0 : null, false, true);
        assertArrayEquals(near, pr.at());
        assertFalse(pr.estimated());
        // one still computed is waited for before any estimate is taken
        var w = DepositRule.choose(List.of(far, near), p -> p == near ? Double.NaN : null, p -> 0.0, p -> 5.0, false, true);
        assertEquals(DepositRule.How.WAIT, w.how());
        // no estimate either: the old way (bag, craft, fail)
        assertEquals(DepositRule.How.CRAFT, DepositRule.choose(List.of(far), p -> null, p -> 0.0, p -> null, false, true).how());
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
