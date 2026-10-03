package io.github.mojolowjo.entropybot.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Package B: deposit never puts away the best-tier pickaxe, anything below its supplies count, nor what the hotbar layout names. */
class DepositKeepsTest {
    static StorageRules.Held h(String id, int n, int slot) { return new StorageRules.Held(id, n, false, slot); }

    static final List<StorageRules.Held> INV = List.of(
            h("minecraft:stone_pickaxe", 1, 0), h("minecraft:iron_pickaxe", 1, 12), h("minecraft:stone_pickaxe", 1, 13),
            h("minecraft:iron_sword", 1, 1), new StorageRules.Held("minecraft:bread", 20, true, 2), h("minecraft:torch", 40, 3),
            h("minecraft:cobblestone", 64, 4), h("minecraft:cobblestone", 64, 20), h("minecraft:raw_iron", 30, 21), h("minecraft:dirt", 50, 22));

    @Test
    void theBestPickaxeStaysEvenWhenNamed() {
        StorageRules.Keeps k = StorageRules.Keeps.NONE;
        Map<String, Integer> tools = StorageRules.depositables(INV, "tools", false, null, k);
        assertFalse(tools.containsKey("minecraft:iron_pickaxe"), "the iron pickaxe is the best tier: kept");
        assertEquals(0, tools.get("minecraft:stone_pickaxe"), "stone ones go when named");
        assertTrue(StorageRules.depositables(INV, "iron_pickaxe", false, null, k).isEmpty());
        assertEquals(Map.of("minecraft:iron_pickaxe", 0), StorageRules.depositables(INV, "iron_pickaxe", false, null), "the old rules (no keeps) would put it away");
        // with only stone pickaxes, those are the best tier
        List<StorageRules.Held> stoneOnly = List.of(h("minecraft:stone_pickaxe", 1, 0), h("minecraft:stone_pickaxe", 1, 9));
        assertTrue(StorageRules.depositables(stoneOnly, "stone_pickaxe", false, null, k).isEmpty());
        assertTrue(StorageRules.allKeptReply("iron_pickaxe").startsWith("error: nothing to deposit matching iron_pickaxe - I keep my best pickaxe"));
    }

    @Test
    void suppliesAreKeptUpToTheirCount() {
        StorageRules.Keeps k = new StorageRules.Keeps(Map.of("minecraft:torch", 32, "minecraft:cobblestone", 100, "minecraft:raw_iron", 10), Map.of());
        Map<String, Integer> all = StorageRules.depositables(INV, "", false, null, k);
        assertEquals(100, all.get("minecraft:cobblestone"), "the supply count beats the 64 kept for pickaxes");
        assertEquals(10, all.get("minecraft:raw_iron"));
        assertEquals(0, all.get("minecraft:dirt"));
        assertEquals(32, StorageRules.depositables(INV, "torch", false, null, k).get("minecraft:torch"), "named: all but the supply count");
        Map<String, Integer> valuables = StorageRules.depositables(INV, "", false, StorageRules.VALUABLE, k);
        assertEquals(Map.of("minecraft:raw_iron", 10), valuables, "the strip mine's trip to base keeps them too");
    }

    @Test
    void theHotbarLayoutsItemsStay() {
        StorageRules.Keeps k = new StorageRules.Keeps(Map.of(), Map.of(1, "pickaxe", 2, "sword", 5, "minecraft:cobblestone", 6, "minecraft:dirt"));
        Map<String, Integer> tools = StorageRules.depositables(INV, "tools", false, null, k);
        assertFalse(tools.containsKey("minecraft:iron_sword"), "the sword in its laid-out slot stays");
        assertEquals(1, tools.get("minecraft:stone_pickaxe"), "the stone pickaxe in slot 1 stays, the other goes");
        Map<String, Integer> all = StorageRules.depositables(INV, "", false, null, k);
        assertEquals(64, all.get("minecraft:cobblestone"), "slot 5's stack (64) and the 64 kept anyway: the larger");
        assertEquals(50, all.getOrDefault("minecraft:dirt", 50), "dirt laid out in slot 6: the best stack of it is kept even before it is moved there");
        assertFalse(all.containsKey("minecraft:dirt"));
    }
}
