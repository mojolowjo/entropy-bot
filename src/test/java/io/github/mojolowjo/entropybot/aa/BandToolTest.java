package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-bandtool (run JAW 06:28-06:35: "dig down to y 16 by hand (...; the pickaxe first 587.6 s)", 44 levels of stone by
 * hand, no pickaxe): the band way requires the tool the ore drops with (iron ore: a stone pickaxe, game data), made first
 * and priced into the way; never "by hand".
 */
class BandToolTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static NeedChain.World world(Map<String, Integer> bag) {
        ScenariosTest.world("wooden", "stone");
        return ScenariosTest.w(bag, false, false, Map.of("oak_log", List.of(ScenariosTest.tree(10, 6)), "cobblestone", List.of(ScenariosTest.STONE_12)))
                .withMobs(x -> List.of(), new io.github.mojolowjo.entropybot.aa.brain.LootBook(List.of(
                        new io.github.mojolowjo.entropybot.aa.brain.LootBook.Source(io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.BLOCK, Z77ExploreTest.IRON_ORE, "minecraft:raw_iron", 1, 1, false, null),
                        new io.github.mojolowjo.entropybot.aa.brain.LootBook.Source(io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.BLOCK, "minecraft:stone", "minecraft:cobblestone", 1, 1, false, null)), "test"))
                .withWhere(new NeedChain.WhereFacts(JobBandChainTest::where, () -> new NeedChain.Surface(0, 0, 64, 0, 0), () -> new int[]{0, 64, 0}));
    }

    static String bandPart(String why) {
        int k = why.indexOf("the band way:");
        return k < 0 ? "" : why.substring(k);
    }

    @Test
    void noPickaxe_theBandWayMakesTheStonePickaxeFirstNeverByHand() {
        Chain c = NeedChain.resolve("raw_iron", 3, world(Map.of()));
        assertTrue(c.ok(), c.why());
        String band = bandPart(c.why());
        assertFalse(band.isEmpty(), c.why());
        assertFalse(band.contains("by hand"), band);
        assertTrue(band.startsWith("the band way: with stone_pickaxe made first ("), band);
        // the tool chain is in the price: the pickaxe is crafted before the dig down
        int craft = -1, dig = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            Step s = c.steps().get(i);
            if (craft < 0 && s instanceof Step.Craft k && k.item().contains("stone_pickaxe")) craft = i;
            if (dig < 0 && s instanceof Step.ToBand) dig = i;
        }
        assertTrue(craft >= 0 && dig > craft, c.steps().toString());
    }

    @Test
    void thePickaxePartIsTheStonePickaxeChainsOwnPrice() {
        // the stone pickaxe alone in the same world: the band way's pickaxe part is that chain's price, not a far larger one
        ScenariosTest.world("wooden", "stone");
        Chain pick = NeedChain.resolve("stone_pickaxe", 1, ScenariosTest.w(Map.of(), false, false,
                Map.of("oak_log", List.of(ScenariosTest.tree(10, 6)), "cobblestone", List.of(ScenariosTest.STONE_12))));
        assertTrue(pick.ok(), pick.why());
        Chain c = NeedChain.resolve("raw_iron", 3, world(Map.of()));
        String band = bandPart(c.why());
        var m = java.util.regex.Pattern.compile("made first \\(([0-9.]+) s;").matcher(band);
        assertTrue(m.find(), band);
        double part = Double.parseDouble(m.group(1));
        assertEquals(pick.seconds(), part, Math.max(1, pick.seconds() * 0.1), band + " vs " + pick.why());
    }

    @Test
    void aStonePickaxeInTheBag_noToolMadeFirst() {
        Chain c = NeedChain.resolve("raw_iron", 3, world(Map.of("minecraft:stone_pickaxe", 1)));
        String band = bandPart(c.why());
        assertFalse(band.contains("made first"), band);
        assertFalse(band.contains("by hand"), band);
    }
}
