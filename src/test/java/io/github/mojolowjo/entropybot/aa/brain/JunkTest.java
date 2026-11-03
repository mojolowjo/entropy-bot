package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** rm-names: one junk definition from the data; a modded pack is classified with no list. */
class JunkTest {
    @AfterEach void tearDown() { TestData.clear(); }

    @Test void junkIsStoneLikeOrSoftAndNeverKeptOrUnknown() {
        TestData.vanilla().install();
        assertTrue(Junk.is("cobblestone") && Junk.is("dirt") && Junk.is("gravel") && Junk.is("netherrack"));
        assertFalse(Junk.is("iron_ore"), "an ore is not junk");
        assertFalse(Junk.is("iron_pickaxe") || Junk.is("bread") || Junk.is("torch") || Junk.is("crafting_table"), "kept by role");
        assertFalse(Junk.is("rotten_flesh_unknown"), "an unknown worth is never junk");
        assertTrue(Junk.placeable("cobblestone") && Junk.placeable("oak_planks") && !Junk.placeable("iron_ore") && !Junk.placeable("torch"));
    }

    @Test void aModdedPackNeedsNoList() {
        TestData d = TestData.vanilla();
        d.block("moddium:slag", 1.8, 0).block("moddium:silt", 0.2, 0).block("moddium:crystal_ore", 3, 0);
        d.stone.add("moddium:slag");
        d.ore.add("moddium:crystal_ore");
        d.install();
        assertTrue(Junk.is("moddium:slag"), "stone-like by the data");
        assertTrue(Junk.is("moddium:silt"), "worth under junk.maxWorth: " + ItemWorth.of("moddium:silt").value());
        assertFalse(Junk.is("moddium:crystal_ore"));
        assertTrue(Junk.placeable("moddium:slag"));
    }

    @Test void aStoneLikeBlockIsPlaceableWhateverItsHardness() {
        // run HW (2026-11-02): 90 cobbled deepslate (hardness 3.5, worth 0.035 over junk.placeWorth 0.02) in the bag and no pillar
        TestData d = TestData.vanilla();
        d.block("minecraft:cobbled_deepslate", 3.5, 0).block("minecraft:deepslate_bricks_hard", 9, 0);
        d.stone.add("minecraft:cobbled_deepslate");
        d.install();
        assertTrue(Junk.placeable("cobbled_deepslate"), "stone-like: filler");
        assertFalse(Junk.placeable("deepslate_bricks_hard"), "not stone-like and worth over the band: not filler");
    }

    @Test void withoutDataNothingIsJunk() {
        TestData.clear();
        assertFalse(Junk.is("cobblestone"));
    }
}
