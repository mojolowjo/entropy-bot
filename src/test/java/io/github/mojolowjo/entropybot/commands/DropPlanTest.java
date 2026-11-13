package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** aa drop (owner 2026-11-13): what is thrown, and that a -d order's amount stays. */
class DropPlanTest {
    private static final Map<String, Integer> BAG = Map.of("minecraft:cobblestone", 40, "minecraft:dirt", 12, "minecraft:torch", 4);

    @Test
    void anItem_allOfIt_orANumber() {
        assertEquals(Map.of("minecraft:dirt", 12), DropPlan.of("dirt", BAG, Map.of()).drops());
        assertEquals(Map.of("minecraft:dirt", 5), DropPlan.of("dirt 5", BAG, Map.of()).drops());
        assertEquals(Map.of("minecraft:dirt", 12), DropPlan.of("dirt 99", BAG, Map.of()).drops(), "never more than the bag holds");
        assertEquals(Map.of("minecraft:dirt", 12), DropPlan.of("dirt all", BAG, Map.of()).drops());
        assertTrue(DropPlan.of("gravel", BAG, Map.of()).drops().isEmpty(), "not in the bag: nothing");
    }

    @Test
    void all_isTheWholeBag_noRefusal() {
        DropPlan p = DropPlan.of("all", BAG, Map.of());
        assertNull(p.err());
        assertEquals(BAG, p.drops());
        assertEquals("", p.heldText());
    }

    @Test
    void aDepositOrdersAmountStays() {
        Map<String, Integer> kept = Map.of("cobblestone", 32);
        DropPlan one = DropPlan.of("cobblestone", BAG, kept);
        assertEquals(Map.of("minecraft:cobblestone", 8), one.drops(), "the 8 spare go, the 32 for the deposit stay");
        assertEquals(" (kept for a deposit, not dropped: cobblestone 32)", one.heldText());
        assertEquals(Map.of("minecraft:cobblestone", 5), DropPlan.of("cobblestone 5", BAG, kept).drops());
        assertEquals("", DropPlan.of("cobblestone 5", BAG, kept).heldText(), "5 were asked and 5 went");
        DropPlan all = DropPlan.of("all", BAG, Map.of("cobblestone", 32, "torch", 4));
        assertEquals(Map.of("minecraft:cobblestone", 8, "minecraft:dirt", 12), all.drops());
        assertEquals(" (kept for a deposit, not dropped: cobblestone 32, torch 4)", all.heldText());
    }

    @Test
    void badWords_sayTheUsage() {
        assertEquals(DropPlan.USAGE, DropPlan.of("", BAG, Map.of()).err());
        assertEquals(DropPlan.USAGE, DropPlan.of("dirt many", BAG, Map.of()).err());
        assertEquals(DropPlan.USAGE, DropPlan.of("dirt 0", BAG, Map.of()).err());
    }
}
