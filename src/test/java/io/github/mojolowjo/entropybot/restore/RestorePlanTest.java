package io.github.mojolowjo.entropybot.restore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P1: the item mapping, the reverse order, the distance filter and the end line. */
class RestorePlanTest {
    static final String OW = "minecraft:overworld";

    @Test
    void itemsForTerrain() {
        assertEquals(List.of("minecraft:cobblestone"), RestoreRules.itemsFor("minecraft:stone"), "answer 2: cobblestone fills stone holes");
        assertEquals(List.of("minecraft:cobbled_deepslate", "minecraft:cobblestone"), RestoreRules.itemsFor("minecraft:deepslate"));
        assertEquals(List.of("minecraft:dirt"), RestoreRules.itemsFor("minecraft:grass_block"));
        assertEquals(List.of("minecraft:andesite", "minecraft:cobblestone"), RestoreRules.itemsFor("minecraft:andesite"));
        assertEquals(List.of("minecraft:gravel"), RestoreRules.itemsFor("minecraft:gravel"));
        assertEquals(List.of("minecraft:red_terracotta", "minecraft:cobblestone"), RestoreRules.itemsFor("minecraft:red_terracotta"));
        assertTrue(RestoreRules.itemsFor("minecraft:white_glazed_terracotta").isEmpty(), "glazed terracotta is built");
        assertTrue(RestoreRules.itemsFor("minecraft:oak_planks").isEmpty());
        assertTrue(RestoreRules.itemsFor("create:limestone").isEmpty(), "modded blocks are never guessed");
        assertEquals("an ore", RestoreRules.whyNot("minecraft:deepslate_iron_ore", false));
        assertEquals("an ore", RestoreRules.whyNot("mekanism:osmium_ore_x", true));
        assertEquals("not terrain", RestoreRules.whyNot("minecraft:oak_planks", false));
        assertNull(RestoreRules.whyNot("minecraft:tuff", false));
    }

    @Test
    void undergroundRule() {
        assertTrue(RestoreRules.underground(false, 70, 40));
        assertFalse(RestoreRules.underground(true, 70, 40), "sky above: walk");
        assertFalse(RestoreRules.underground(false, 70, 68), "just under an overhang: walk");
    }

    static Ledger ledger(String... blocks) {
        Ledger l = new Ledger();
        for (int i = 0; i < blocks.length; i++) {
            String why = RestoreRules.whyNot(blocks[i], false);
            l.add(i, 60, 0, OW, blocks[i], null, why == null ? RestoreRules.itemsFor(blocks[i]) : List.of(), why, 1, "mine", Ledger.PATH, i, i);
        }
        l.settle((d, x, y, z) -> Ledger.Cell.EMPTY, 1000);
        return l;
    }

    @Test
    void newestFirstAndItemsCountedDown() {
        Ledger l = ledger("minecraft:stone", "minecraft:stone", "minecraft:deepslate", "minecraft:dirt");
        RestorePlan.Plan p = RestorePlan.plan(l.confirmed(), new int[]{0, 60, 0}, OW, 24,
                Map.of("minecraft:cobblestone", 2, "minecraft:dirt", 5));
        assertEquals(List.of(3, 2, 1), p.actions().stream().map(a -> a.entry().x).toList(), "a tunnel closes from its far end back");
        assertEquals("minecraft:dirt", p.actions().get(0).item());
        assertEquals("minecraft:cobblestone", p.actions().get(1).item(), "no cobbled deepslate: cobblestone");
        assertEquals(1, p.left().size());
        assertEquals(0, p.left().get(0).entry().x);
        assertEquals("no cobblestone", p.left().get(0).why());
        assertEquals("put back 3 blocks, 1 left: no cobblestone (restore now when you have it)", RestorePlan.summary(3, p.left()));
    }

    @Test
    void farEntriesWaitAndOresAreNeverPlaced() {
        Ledger l = new Ledger();
        l.add(100, 60, 0, OW, "minecraft:stone", null, RestoreRules.itemsFor("minecraft:stone"), null, 1, "mine", Ledger.PATH, 0, 0);
        l.add(5, 60, 0, OW, "minecraft:iron_ore", null, List.of(), "an ore", 1, "mine", Ledger.PATH, 1, 1);
        l.add(6, 60, 0, "minecraft:the_nether", "minecraft:netherrack", null, RestoreRules.itemsFor("minecraft:netherrack"), null, 1, "mine", Ledger.PATH, 2, 2);
        l.settle((d, x, y, z) -> Ledger.Cell.EMPTY, 1000);
        RestorePlan.Plan p = RestorePlan.plan(l.confirmed(), new int[]{0, 60, 0}, OW, 24, Map.of("minecraft:cobblestone", 64, "minecraft:netherrack", 9));
        assertTrue(p.actions().isEmpty());
        assertEquals(List.of("too far", "an ore", "too far"), p.left().stream().map(RestorePlan.Left::why).toList());
        assertEquals("3 left: too far, an ore (restore now)", RestorePlan.summary(0, p.left()));
    }

    @Test
    void summaryWords() {
        assertNull(RestorePlan.summary(0, List.of()));
        assertEquals("put back 1 block", RestorePlan.summary(1, List.of()));
        assertEquals("dug out 2 blocks at 1 2 3, put back 2", EscapePlan.report(2, new int[]{1, 2, 3}, 2));
        assertEquals("dug out 1 block at 1 2 3", EscapePlan.report(1, new int[]{1, 2, 3}, -1));
    }
}
