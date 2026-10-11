package io.github.mojolowjo.entropybot.camp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 0.25.1 gear.ironFirst: iron to chestplate then leggings; leather fills helmet and boots meanwhile. */
class GearOrderTest {
    static final List<ArmorCareRules.Piece> NAKED = List.of(new ArmorCareRules.Piece("helmet", null, 0, 0), new ArmorCareRules.Piece("chestplate", null, 0, 0),
            new ArmorCareRules.Piece("leggings", null, 0, 0), new ArmorCareRules.Piece("boots", null, 0, 0));

    @Test
    void orderPutsTheBodyFirstAndKeepsWornPiecesAhead() {
        Map<String, String> needs = ArmorCareRules.needs(NAKED, List.of());
        assertEquals(List.of("helmet", "chestplate", "leggings", "boots"), List.copyOf(needs.keySet()), "the old order");
        assertEquals(List.of("chestplate", "leggings", "helmet", "boots"), List.copyOf(ArmorCareRules.ordered(needs, true).keySet()));
        assertSame(needs, ArmorCareRules.ordered(needs, false));
        Map<String, String> mixed = new LinkedHashMap<>();
        mixed.put("boots", "minecraft:iron_boots");      // a worn piece
        mixed.put("helmet", null);
        mixed.put("leggings", null);
        assertEquals(List.of("boots", "leggings", "helmet"), List.copyOf(ArmorCareRules.ordered(mixed, true).keySet()));
    }

    @Test
    void ironIsHeldForTheBodySoLeatherFillsHelmetAndBoots() {
        Map<String, Integer> have = Map.of("minecraft:iron_ingot", 16, "minecraft:leather", 9);
        Set<String> stored = Set.of();
        assertEquals("craft iron_chestplate 1 then wear", ArmorCareRules.replacement("chestplate", null, stored, ArmorCareRules.spendable("chestplate", NAKED, have, true)));
        assertEquals("craft leather_helmet 1 then wear", ArmorCareRules.replacement("helmet", null, stored, ArmorCareRules.spendable("helmet", NAKED, have, true)));
        assertEquals("craft leather_boots 1 then wear", ArmorCareRules.replacement("boots", null, stored, ArmorCareRules.spendable("boots", NAKED, have, true)));
        assertEquals("craft iron_helmet 1 then wear", ArmorCareRules.replacement("helmet", null, stored, ArmorCareRules.spendable("helmet", NAKED, have, false)), "off: as before");
        // the body in iron already: the helmet may take the iron
        List<ArmorCareRules.Piece> body = List.of(new ArmorCareRules.Piece("helmet", null, 0, 0),
                new ArmorCareRules.Piece("chestplate", "minecraft:iron_chestplate", 240, 240),
                new ArmorCareRules.Piece("leggings", "minecraft:iron_leggings", 225, 225), new ArmorCareRules.Piece("boots", null, 0, 0));
        assertEquals("craft iron_helmet 1 then wear", ArmorCareRules.replacement("helmet", null, stored, ArmorCareRules.spendable("helmet", body, have, true)));
        assertTrue(ArmorCareRules.orderText(true).contains("chestplate then leggings"));
    }
}
