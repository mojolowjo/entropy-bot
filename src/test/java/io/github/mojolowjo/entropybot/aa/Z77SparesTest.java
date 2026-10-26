package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.brain.OreBand;
import io.github.mojolowjo.entropybot.aa.brain.StripAreas;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
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
 * capped the spares one chain makes, not the spares held. Now the cap is on the spares HELD (the bag's other pickaxes of
 * the tier count against it), and a strip's wear comes from the one dig estimate its seconds come from (stripDig: the
 * band's blocks per ore x the areas' factor x the ore x the tunnel height).
 * Hand numbers: Z62RunSTest's band (iron at y 16, 3 ore x ~110.6 blocks = 332 cells: 2 x 332 + 3 = 667 wear); a walk's
 * pickaxe blocks (Baritone's path) add to it; a stone pickaxe 131 uses, a new one 131.
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
     * The ruling's scenario: the bag holds 786 uses (the diamond pickaxe in hand 655 left + one stone pickaxe 131), the strip
     * wears 800 (667 + 133 dug on the path down): 14 over -> ceil(14 / 131) = 1 spare stone pickaxe, not 3. The re-plan
     * with 1179 uses (655 + 4 x 131) covers the 800: no spare.
     */
    @Test
    void theSparesCoverTheGap_oneNotThree_thenNone() {
        ScenariosTest.world("wooden", "stone", "iron", "diamond");
        Map<String, Integer> bag = Map.of("minecraft:diamond_pickaxe", 1, "minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, deep(bag, iron16(), 133, id -> id.contains("diamond") ? 655 : 131));
        assertTrue(c.ok(), c.why());
        assertEquals(1, Z62RunSTest.crafted(c, "stone_pickaxe"), c.spares());
        assertTrue(c.spares().contains("diamond_pickaxe: 655 uses left, 800 blocks to break, the bag's other pickaxes 131 uses (131 a new stone_pickaxe) -> 1 spare stone_pickaxe made first"), c.spares());
        Map<String, Integer> after = Map.of("minecraft:diamond_pickaxe", 1, "minecraft:stone_pickaxe", 4, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain again = NeedChain.resolve("raw_iron", 3, deep(after, iron16(), 133, id -> id.contains("diamond") ? 655 : 131));
        assertTrue(again.ok(), again.why());
        assertEquals(0, Z62RunSTest.crafted(again, "stone_pickaxe"), again.spares());
        assertTrue(again.spares().contains("655 uses left, 800 blocks to break; the bag's 4 other pickaxe(s) (524 uses) cover it: no spare made"), again.spares());
    }

    /**
     * Run CA 15:35:44 / 15:37:22: the stone pickaxe in hand and 3 spares held (393 uses), a strip wearing more (667): 143
     * over, 2 would cover it - tools.maxSpares 3 already held: none made, said, the rest re-planned when one breaks (was: 2
     * more, then 3 more on every re-plan until the bag was full).
     */
    @Test
    void runCA_theSparesHeldCountAgainstTheCap() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 4, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(bag, Z62RunSTest.band("east")).withUses(id -> 131));
        assertTrue(c.ok(), c.why());
        assertEquals(0, Z62RunSTest.crafted(c, "stone_pickaxe"), c.spares());
        assertTrue(c.spares().contains("stone_pickaxe: 2 spare stone_pickaxe would cover 667 blocks; tools.maxSpares 3 holds (3 other pickaxe(s) held already): 0 made, the rest re-planned when a pickaxe breaks"), c.spares());
        // two held: room for one of the two
        Map<String, Integer> three = Map.of("minecraft:stone_pickaxe", 3, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain one = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(three, Z62RunSTest.band("east")).withUses(id -> 131));
        assertEquals(1, Z62RunSTest.crafted(one, "stone_pickaxe"), one.spares());
        assertTrue(one.spares().contains("tools.maxSpares 3 holds (2 other pickaxe(s) held already): 1 made"), one.spares());
    }

    /**
     * Control: no other pickaxe in the bag (0 uses), the stone pickaxe in hand 131 left, a strip wearing 400 (band 64 ore a
     * slab: 4096 / 64 = 64 blocks an ore, 3 x 64 = 192 cells, 2 x 192 + 3 = 387, + 13 dug on the path): 269 over -> 3 spares
     * (131 + 393 = 524 covers 400), at the cap 3. tools.maxSpares 2: 2 made; one other held and tools.maxSpares 2: 1 made.
     */
    @Test
    void control_noUsesInTheBag_enoughSparesUpToTheCapHeld() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, deep(bag, 64.0, 13, id -> 131));
        assertTrue(c.ok(), c.why());
        assertEquals(3, Z62RunSTest.crafted(c, "stone_pickaxe"), c.spares());
        assertTrue(c.spares().contains("stone_pickaxe: 131 uses left, 400 blocks to break, the bag's other pickaxes 0 uses (131 a new stone_pickaxe) -> 3 spare stone_pickaxe made first"), c.spares());
        Tuning.set("tools.maxSpares", 2);
        Chain capped = NeedChain.resolve("raw_iron", 3, deep(bag, 64.0, 13, id -> 131));
        assertEquals(2, Z62RunSTest.crafted(capped, "stone_pickaxe"), capped.spares());
        assertTrue(capped.spares().contains("3 spare stone_pickaxe would cover 400 blocks; tools.maxSpares 2 holds: 2 made"), capped.spares());
        Map<String, Integer> two = Map.of("minecraft:stone_pickaxe", 2, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain held = NeedChain.resolve("raw_iron", 3, deep(two, 64.0, 13, id -> 131));
        assertEquals(1, Z62RunSTest.crafted(held, "stone_pickaxe"), "138 over, 2 would cover it, 1 held of 2: " + held.spares());
    }

    /**
     * The one dig estimate: the band's blocks per ore x the areas' factor (run U's kind of facts: faces seen along east at
     * y 16 with no iron on them), capped, x the ore = the cells; no start known: the band's estimate (x 1). The strip way's
     * wear follows the areas: the same order, its areas seen bare, wears more and makes more spares. NeedChain reads the
     * band's blocks per ore in that one place only.
     */
    @Test
    void theWearIsTheStripsOwnDigEstimate_withTheAreas() throws java.io.IOException {
        ScenariosTest.world("wooden", "stone");
        double ps = 64.0;
        NeedChain.StripDig none = NeedChain.stripDig(ps, 3, null, 16, "east", Z62RunSTest.IRON);
        assertEquals(1.0, none.scale(), 1e-9);
        assertEquals(192, none.cells());
        assertEquals(384, none.breaks(), "TUNNEL_HEIGHT 2 a cell");
        StripAreas.add(1, 16, 0, 200, Map.of());          // 200 faces, no iron: K 200 -> observed half the band's -> x 2
        NeedChain.StripDig seen = NeedChain.stripDig(ps, 3, new int[]{0, 0}, 16, "east", Z62RunSTest.IRON);
        assertEquals(StripAreas.factor(StripAreas.perFace(ps), StripAreas.along(0, 16, 0, "east", Z62RunSTest.IRON)), seen.scale(), 1e-9);
        assertEquals(2.0, seen.scale(), 1e-9);
        assertEquals(384, seen.cells());
        assertEquals(OreBand.blocksPerOre(ps), seen.band(), 1e-9);
        assertEquals(192, NeedChain.stripDig(ps, 3, null, 16, "east", Z62RunSTest.IRON).cells(), "no start known: the band's estimate");
        // the strip way: its wear from the same estimate (2 x 384 + 3 + 13 = 784; 653 over -> 5 needed, 3 made); no strip
        // from fresh ground offered beside it (strip.areaMinFaces past the faces seen), so the way priced is this one
        Tuning.set("strip.areaMinFaces", 100_000);
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        StripBand b = new StripBand(Z62RunSTest.IRON, -64, 384, 16, ps, 0, 0, 64, "east", "minecraft:stone");
        Chain c = NeedChain.resolve("raw_iron", 3, ScenariosTest.w(bag, true, true, Map.of()).withStrip(word -> word.contains("raw_iron") || word.contains("iron_ore") ? b : null)
                .withUses(id -> 131).withPath((x, y, z, d) -> PathPrice.Answer.priced(new PathPrice.Walk(30, 0, 13))));
        assertTrue(c.spares().contains("784 blocks to break"), c.spares());
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/aa/NeedChain.java"));
        assertEquals(1, src.split("OreBand\\.blocksPerOre\\(", -1).length - 1, "the band's blocks per ore read in stripDig only");
    }
}
