package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.brain.OreBand;
import io.github.mojolowjo.entropybot.aa.brain.StripAreas;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.ToolUses;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 spares (J2 one-fix cycle 9, manager's ruling 2026-10-26; run CA on the owner's PC, 22 stone pickaxes): ten
 * "spare pickaxes:" lines each made 3 more while the bag's other pickaxes rose 0 -> 393 -> ... -> 1965 uses - tools.maxSpares
 * capped the spares one chain makes, not the spares held. Now the cap is on the spares HELD (spares 2: the bag's other
 * pickaxes of the tier as new ones of the kind made - known uses / its fresh uses, rounded down; unknown uses 0), and a
 * strip's wear comes from the one dig estimate its seconds come from (stripDig).
 * Hand numbers (spares 2, the dig from geometry): Z62RunSTest's band (iron at y 16, ~110.4 slab cells an ore / 3 seen a
 * block dug = 36.8 blocks dug an ore; 3 ore x 36.8 / 2 a cell = 56 cells: wear 2 x 56 + 3 = 115); a walk's pickaxe blocks
 * (Baritone's path) add to it; a stone pickaxe 131 uses, a new one 131.
 */
class Z77SparesTest {

    @BeforeEach
    void areas() { StripAreas.clear(); }

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        StripAreas.clear();
    }

    /** A band at y 16 under feet at y 64 (the walk down is Baritone's path, {@code pathBreaks} pickaxe blocks on it). */
    static NeedChain.World deep(Map<String, Integer> bag, double perSlab, int pathBreaks, java.util.function.Function<String, Integer> uses) {
        StripBand b = new StripBand(Z62RunSTest.IRON, -64, 384, 16, perSlab, 0, 0, 64, "east", "minecraft:stone");
        return ScenariosTest.w(bag, true, true, Map.of()).withStrip(word -> word.contains("raw_iron") || word.contains("iron_ore") ? b : null)
                .withUses(uses::apply).withPath((x, y, z, d) -> PathPrice.Answer.priced(new PathPrice.Walk(30, 0, pathBreaks)));
    }

    static double iron16() { return Z62RunSTest.band("east").perSlab(); }

    /**
     * The ruling's scenario: the bag holds 786 uses (the diamond pickaxe in hand 655 left + one stone pickaxe 131), the way
     * wears 800 (the strip's 115 + 685 dug on the path down): 14 over -> ceil(14 / 131) = 1 spare stone pickaxe, not 3
     * (held: 131 / 131 = 1, room 2). The re-plan with 1179 uses (655 + 4 x 131) covers the 800: no spare.
     */
    @Test
    void theSparesCoverTheGap_oneNotThree_thenNone() {
        ScenariosTest.world("wooden", "stone", "iron", "diamond");
        Map<String, Integer> bag = Map.of("minecraft:diamond_pickaxe", 1, "minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, deep(bag, iron16(), 685, id -> id.contains("diamond") ? 655 : 131));
        assertTrue(c.ok(), c.why());
        assertEquals(1, Z62RunSTest.crafted(c, "stone_pickaxe"), c.spares());
        assertTrue(c.spares().contains("diamond_pickaxe: 655 uses left, 800 blocks to break, the bag's other pickaxes 131 uses (131 a new stone_pickaxe) -> 1 spare stone_pickaxe made first"), c.spares());
        Map<String, Integer> after = Map.of("minecraft:diamond_pickaxe", 1, "minecraft:stone_pickaxe", 4, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain again = NeedChain.resolve("raw_iron", 3, deep(after, iron16(), 685, id -> id.contains("diamond") ? 655 : 131));
        assertTrue(again.ok(), again.why());
        assertEquals(0, Z62RunSTest.crafted(again, "stone_pickaxe"), again.spares());
        assertTrue(again.spares().contains("655 uses left, 800 blocks to break; the bag's 4 other pickaxe(s) (524 uses) cover it: no spare made"), again.spares());
    }

    /**
     * Run CA 15:35:44 / 15:37:22 (manager's call 1: 786 uses = 6 stone pickaxes is past the cap, 0 spares is right): the
     * stone pickaxe in hand and 3 spares held (393 uses = 3 new ones), a way wearing 667 (115 + run CA's 552 path blocks):
     * 143 over, 2 would cover it - tools.maxSpares 3 held already: none made, the gap said. With 2 held: 1 made, 143 over.
     */
    @Test
    void runCA_theSparesHeldCountAgainstTheCap() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 4, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, deep(bag, iron16(), 552, id -> 131));
        assertTrue(c.ok(), c.why());
        assertEquals(0, Z62RunSTest.crafted(c, "stone_pickaxe"), c.spares());
        assertTrue(c.spares().contains("stone_pickaxe: 2 spare stone_pickaxe would cover 667 blocks; tools.maxSpares 3 holds (3 held already: the bag's 393 uses / 131 a new one): 0 made, 143 blocks over, re-planned when a pickaxe breaks"), c.spares());
        Map<String, Integer> three = Map.of("minecraft:stone_pickaxe", 3, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain one = NeedChain.resolve("raw_iron", 3, deep(three, iron16(), 552, id -> 131));
        assertEquals(1, Z62RunSTest.crafted(one, "stone_pickaxe"), one.spares());
        assertTrue(one.spares().contains("tools.maxSpares 3 holds (2 held already: the bag's 262 uses / 131 a new one): 1 made, 143 blocks over"), one.spares());
    }

    /**
     * Review 1 and 3 (the Sonnet review of a2ec20dd): worn and unknown-use pickaxes held up to the cap never fill it. The
     * diamond pickaxe in hand 131 left; 3 iron pickaxes with 5 uses each (15 uses: 15 / 131 = 0 new ones) and 2 stone
     * pickaxes of unknown uses (0, rule 15); a way wearing 667: 521 over -> 4 would cover it, 3 made at the cap 3 (128 blocks
     * over, said); tools.maxSpares 4: 4 made, covered. On a2ec20dd: 5 held at full value, none made.
     */
    @Test
    void wornAndUnknownPickaxesHeld_theGapIsCoveredOrSaid() {
        ScenariosTest.world("wooden", "stone", "iron", "diamond");
        Map<String, Integer> bag = Map.of("minecraft:diamond_pickaxe", 1, "minecraft:iron_pickaxe", 3, "minecraft:stone_pickaxe", 2, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        java.util.function.Function<String, Integer> uses = id -> id.contains("diamond") ? 131 : id.contains("iron") ? 5 : ToolUses.UNKNOWN;
        Chain c = NeedChain.resolve("raw_iron", 3, deep(bag, iron16(), 552, uses));
        assertTrue(c.ok(), c.why());
        assertEquals(3, Z62RunSTest.crafted(c, "stone_pickaxe"), c.spares());
        assertTrue(c.spares().contains("diamond_pickaxe: 4 spare stone_pickaxe would cover 667 blocks; tools.maxSpares 3 holds: 3 made, 128 blocks over, re-planned when a pickaxe breaks"), c.spares());
        Tuning.set("tools.maxSpares", 4);
        Chain all = NeedChain.resolve("raw_iron", 3, deep(bag, iron16(), 552, uses));
        assertEquals(4, Z62RunSTest.crafted(all, "stone_pickaxe"), all.spares());
        assertFalse(all.spares().contains("blocks over"), "covered: " + all.spares());
    }

    /**
     * Control: no other pickaxe in the bag (0 uses), the stone pickaxe in hand 131 left, a way wearing 400 (115 + 285 path
     * blocks): 269 over -> 3 spares (131 + 393 = 524 covers 400), at the cap 3. tools.maxSpares 2: 2 made, 7 over, said; one
     * other held and tools.maxSpares 2: 1 made.
     */
    @Test
    void control_noUsesInTheBag_enoughSparesUpToTheCapHeld() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, deep(bag, iron16(), 285, id -> 131));
        assertTrue(c.ok(), c.why());
        assertEquals(3, Z62RunSTest.crafted(c, "stone_pickaxe"), c.spares());
        assertTrue(c.spares().contains("stone_pickaxe: 131 uses left, 400 blocks to break, the bag's other pickaxes 0 uses (131 a new stone_pickaxe) -> 3 spare stone_pickaxe made first"), c.spares());
        Tuning.set("tools.maxSpares", 2);
        Chain capped = NeedChain.resolve("raw_iron", 3, deep(bag, iron16(), 285, id -> 131));
        assertEquals(2, Z62RunSTest.crafted(capped, "stone_pickaxe"), capped.spares());
        assertTrue(capped.spares().contains("3 spare stone_pickaxe would cover 400 blocks; tools.maxSpares 2 holds: 2 made, 7 blocks over"), capped.spares());
        Map<String, Integer> two = Map.of("minecraft:stone_pickaxe", 2, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain held = NeedChain.resolve("raw_iron", 3, deep(two, iron16(), 285, id -> 131));
        assertEquals(1, Z62RunSTest.crafted(held, "stone_pickaxe"), "138 over, 2 would cover it, 1 held of 2: " + held.spares());
    }

    /**
     * The one dig estimate (spares 2, geometry): a 1 x 2 tunnel sees 6 new cells a cell = 3 a block dug; the band's slab
     * cells an ore x the areas' factor, capped, / 3 = blocks dug an ore; x the ore / 2 a cell = the cells. Band 64 a slab:
     * 64 cells an ore / 3 = 21.33, x 3 ore / 2 = 32 cells (64 breaks); 200 bare faces seen along east: x 2 -> 64 cells. The
     * strip way's wear follows the areas (2 x 64 + 3 + 13 = 144). NeedChain reads the band's blocks per ore in one place only.
     */
    @Test
    void theWearIsTheStripsOwnDigEstimate_withTheAreas() throws java.io.IOException {
        ScenariosTest.world("wooden", "stone");
        assertEquals(3.0, NeedChain.SEEN_PER_DUG, 1e-9, "(2 x 2 sides + roof + floor) / 2 blocks a cell");
        double ps = 64.0;
        NeedChain.StripDig none = NeedChain.stripDig(ps, 3, null, 16, "east", Z62RunSTest.IRON);
        assertEquals(1.0, none.scale(), 1e-9);
        assertEquals(64.0 / 3, none.bpo(), 1e-9);
        assertEquals(32, none.cells());
        assertEquals(64, none.breaks(), "TUNNEL_HEIGHT 2 a cell");
        StripAreas.add(1, 16, 0, 200, Map.of());          // 200 faces, no iron: K 200 -> observed half the band's -> x 2
        NeedChain.StripDig seen = NeedChain.stripDig(ps, 3, new int[]{0, 0}, 16, "east", Z62RunSTest.IRON);
        assertEquals(StripAreas.factor(StripAreas.perFace(ps), StripAreas.along(0, 16, 0, "east", Z62RunSTest.IRON)), seen.scale(), 1e-9);
        assertEquals(2.0, seen.scale(), 1e-9);
        assertEquals(64, seen.cells());
        assertEquals(OreBand.blocksPerOre(ps), seen.band(), 1e-9);
        assertEquals(32, NeedChain.stripDig(ps, 3, null, 16, "east", Z62RunSTest.IRON).cells(), "no start known: the band's estimate");
        // the strip way: its wear from the same estimate; no strip from fresh ground offered beside it (strip.areaMinFaces
        // past the faces seen), so the way priced is this one
        Tuning.set("strip.areaMinFaces", 100_000);
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, deep(bag, ps, 13, id -> 131));
        assertTrue(c.spares().contains("144 blocks to break"), c.spares());
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/aa/NeedChain.java"));
        assertEquals(1, src.split("OreBand\\.blocksPerOre\\(", -1).length - 1, "the band's blocks per ore read in stripDig only");
    }
}
