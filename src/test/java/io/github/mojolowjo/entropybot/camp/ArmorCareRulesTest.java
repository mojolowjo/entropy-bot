package io.github.mojolowjo.entropybot.camp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.6 armour care: which slots need a piece, and the replacement by stock and tier. */
class ArmorCareRulesTest {
    static ArmorCareRules.Piece on(String slot, String id, int left, int max) { return new ArmorCareRules.Piece(slot, id, left, max); }

    static List<ArmorCareRules.Piece> worn(ArmorCareRules.Piece chest) {
        return List.of(on("helmet", "minecraft:iron_helmet", 150, 165), chest,
                on("leggings", "minecraft:iron_leggings", 200, 225), on("boots", "minecraft:iron_boots", 180, 195));
    }

    @Test
    void wornOrEmptySlotsNeedAPiece() {
        Map<String, String> n = ArmorCareRules.needs(worn(on("chestplate", "minecraft:iron_chestplate", 20, 240)), List.of());
        assertEquals(Map.of("chestplate", "minecraft:iron_chestplate"), n);
        n = ArmorCareRules.needs(worn(on("chestplate", null, 0, 0)), List.of());
        assertTrue(n.containsKey("chestplate"));
        assertNull(n.get("chestplate"));
        assertTrue(ArmorCareRules.needs(worn(on("chestplate", "minecraft:iron_chestplate", 200, 240)), List.of()).isEmpty());
    }

    @Test
    void aGoodSpareInTheBagIsWornInsteadOfMade() {
        var bag = List.of(on("chestplate", "minecraft:leather_chestplate", 80, 80));
        assertTrue(ArmorCareRules.needs(worn(on("chestplate", "minecraft:iron_chestplate", 5, 240)), bag).isEmpty());
        assertEquals("minecraft:leather_chestplate", ArmorCareRules.spare("chestplate", bag));
        // a worn spare does not count
        assertNull(ArmorCareRules.spare("chestplate", List.of(on("chestplate", "minecraft:iron_chestplate", 3, 240))));
    }

    @Test
    void theSameItemFromStorageFirst() {
        assertEquals("get iron_chestplate 1 then wear", ArmorCareRules.replacement("chestplate", "minecraft:iron_chestplate",
                Set.of("minecraft:iron_chestplate"), Map.of("minecraft:diamond", 64)));
    }

    @Test
    void bestAffordableTierAtLeastTheWornOne() {
        // diamonds for a chestplate (8): diamond
        assertEquals("craft diamond_chestplate 1 then wear", ArmorCareRules.replacement("chestplate", "minecraft:iron_chestplate", Set.of(),
                Map.of("minecraft:diamond", 8, "minecraft:iron_ingot", 30)));
        // 7 diamonds are not enough: iron
        assertEquals("craft iron_chestplate 1 then wear", ArmorCareRules.replacement("chestplate", "minecraft:iron_chestplate", Set.of(),
                Map.of("minecraft:diamond", 7, "minecraft:iron_ingot", 8)));
        // boots need 4
        assertEquals("craft leather_boots 1 then wear", ArmorCareRules.replacement("boots", null, Set.of(), Map.of("minecraft:leather", 4)));
        // only leather for a worn iron piece: still better than nothing
        assertEquals("craft leather_helmet 1 then wear", ArmorCareRules.replacement("helmet", "minecraft:iron_helmet", Set.of(), Map.of("minecraft:leather", 9)));
    }

    @Test
    void neverTakesBlocksApartAndSaysWhenNothingCan() {
        assertNull(ArmorCareRules.replacement("chestplate", "minecraft:iron_chestplate", Set.of(),
                Map.of("minecraft:iron_block", 5, "minecraft:diamond_block", 2, "minecraft:iron_ingot", 7)));
        // a different piece for the slot in storage
        assertEquals("get diamond_leggings 1 then wear", ArmorCareRules.replacement("leggings", "minecraft:iron_leggings",
                Set.of("minecraft:leather_leggings", "minecraft:diamond_leggings"), Map.of()));
    }

    @Test
    void textAndLowest() {
        var w = worn(on("chestplate", "minecraft:iron_chestplate", 12, 240));
        String t = ArmorCareRules.text(w);
        assertTrue(t.contains("iron_chestplate 12/240 (worn)"), t);
        assertEquals("my iron_chestplate is at 5 %", ArmorCareRules.lowest(w));
        assertNull(ArmorCareRules.lowest(worn(on("chestplate", null, 0, 0))));
        assertEquals("chestplate", ArmorCareRules.slot("minecraft:netherite_chestplate"));
        assertNull(ArmorCareRules.slot("minecraft:iron_pickaxe"));
    }
}
