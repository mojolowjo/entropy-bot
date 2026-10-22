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

    /** Addendum (b), owner-watched: the collect hand refuses a dig its bag's pickaxe cannot do, with the block, its tier and mine. */
    @Test
    void theCollectHandRefusesADigItsPickaxeCannotDo() {
        TestData.planner().install();
        try {
            List<String> coal = List.of("minecraft:coal_ore");
            assertEquals("coal_ore needs tier 1, I have 0", MiningTiers.digRefusal(coal, 0));
            assertNull(MiningTiers.digRefusal(coal, 1), "a wooden pickaxe digs coal ore");
            assertEquals("iron_ore needs tier 2, I have 1", MiningTiers.digRefusal(List.of("minecraft:diamond_ore", "minecraft:iron_ore"), 1), "the least tier is named");
            assertEquals(List.of("minecraft:coal_ore"), MiningTiers.diggable(List.of("minecraft:coal_ore", "minecraft:iron_ore"), 1), "what it cannot dig leaves the filter");
            assertNull(MiningTiers.digRefusal(List.of("minecraft:dirt"), 0), "dirt needs no pickaxe");
        } finally {
            TestData.clear();
        }
    }
}