package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The tiers of tools and blocks come from the game's data (the TOOL component, the tags); no name list, no switch. */
class MiningTiersTest {
    @Test void aModdedTierAboveDiamondIsReadFromItsOwnTags() {
        assertEquals(List.of(1, 1, 2, 3, 4, 5), MiningTiers.rankTiers(List.of(10, 10, 6, 3, 1, 0)));
    }

    /** rm-names: a fake ore with a modded tier and a fake tool: tiers come from the data, no switch, no name. */
    @Test
    void aModdedTierIsReadFromTheDataAlone() {
        TestData d = TestData.vanilla();
        d.tool("moddium:drill", ItemWorth.Role.PICKAXE, 9, 4, 900).tool("moddium:drill_axe", ItemWorth.Role.AXE, 9, 1, 900);
        d.tiers.put("moddium:rune_ore", 6);
        d.install();
        assertEquals(5, MiningTiers.toolTier("moddium:drill", "pickaxe"), "level 4 + 1 on the bot scale");
        assertEquals(0, MiningTiers.toolTier("moddium:drill", "axe"), "a pickaxe is no axe");
        assertEquals(2, MiningTiers.toolTier("moddium:drill_axe", "axe"));
        assertEquals(4, MiningTiers.toolTier("minecraft:diamond_pickaxe", "pickaxe"));
        assertEquals(1, MiningTiers.toolTier("minecraft:wooden_sword", "sword"));
        assertEquals(6, MiningTiers.blockTierOf("moddium:rune_ore"));
        assertEquals(MiningTiers.UNKNOWN, MiningTiers.blockTierOf("moddium:nothing"));
        TestData.clear();
        assertEquals(0, MiningTiers.toolTier("minecraft:diamond_pickaxe", "pickaxe"), "no data: unknown, said once");
        assertEquals(MiningTiers.UNKNOWN, MiningTiers.blockTierOf("minecraft:iron_ore"));
    }
}