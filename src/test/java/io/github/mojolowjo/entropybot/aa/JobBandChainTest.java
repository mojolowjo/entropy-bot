package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.OreBand;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.CaveWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Job-band (coordinator 2026-11-08, rule 7: the steps do what the price says): the band way's steps - the dig to the
 * band's Y, then a known cave near that Y, else the explore from there. Iron as vanilla's ore_iron_middle (trapezoid
 * -24..56, 10 veins of 9: a test input), the bot at 0 64 0 on the surface, a stone pickaxe.
 */
class JobBandChainTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static GenWhere.Fact where(String word) {
        if (!word.contains("raw_iron")) return GenWhere.unknown("test");
        var f = new OreBand.Feature(-24, 56, 0, 10, 9);
        return new GenWhere.Fact(GenWhere.Kind.BAND, -24, 56, Z77ExploreTest.IRON_ORE, "minecraft:ore_iron_middle", null,
                List.of(new GenWhere.Band(-24, 56, 90, "minecraft:ore_iron_middle", f)));
    }

    static NeedChain.World world() {
        ScenariosTest.world("wooden", "stone");
        return ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), false, false, Map.of())
                .withMobs(x -> List.of(), Z77ExploreTest.book())
                .withWhere(new NeedChain.WhereFacts(JobBandChainTest::where, () -> new NeedChain.Surface(0, 0, 64, 0, 0), () -> new int[]{0, 64, 0}));
    }

    @Test
    void noCaveKnown_digDownThenExploreFromThere() {
        Chain c = NeedChain.resolve("raw_iron", 3, world());
        assertTrue(c.ok(), c.why());
        int peak = where("raw_iron").peaks(false).get(0).y();
        assertEquals(new Step.ToBand(peak, peak), c.steps().get(0), c.steps().toString());
        assertEquals("dig down to y " + peak, c.steps().get(0).text());
        assertTrue(c.steps().get(c.steps().size() - 1) instanceof Step.Explore e && e.priced(), c.steps().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Cave), c.steps().toString());
        assertTrue(c.why().contains("the band way: dig down to y " + peak) && c.why().contains("band price:"), c.why());
    }

    @Test
    void aCaveKnownNearTheBand_digDownThenTheCave() {
        int peak = where("raw_iron").peaks(false).get(0).y();
        var cave = new NeedChain.CaveWay(10, peak + 4, 10, 50.0, Z77ExploreTest.IRON_ORE, "iron", 0.9, 0.0,
                null, 1.92, List.of(), List.of(new int[]{10, peak + 4, 10}))
                .withPassages(List.of(new io.github.mojolowjo.entropybot.cave.Passages.Passage(0, -1, new int[]{0, peak + 4, 0}, new int[]{0, peak + 4, 0}, 100, 1, 4, List.of(), 200, 0,
                        io.github.mojolowjo.entropybot.cave.Passages.EndKind.DEAD_END)))
                .withDensity(CaveWalk.Density.flat(0.00001, "test: a thin cave, so its own way is dear"));
        Chain c = NeedChain.resolve("raw_iron", 3, world().withCave(w -> w.contains("raw_iron") ? cave : null));
        assertTrue(c.ok(), c.why());
        assertEquals(new Step.ToBand(peak, peak), c.steps().get(0), c.steps().toString());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Cave), c.steps().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore), c.steps().toString());
        assertTrue(c.why().contains("then the cave at 10 " + (peak + 4) + " 10"), c.why());
    }
}
