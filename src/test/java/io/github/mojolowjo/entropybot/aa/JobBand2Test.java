package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.BandEnd;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.BandPrice;
import io.github.mojolowjo.entropybot.cave.CaveWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-band2 (rule 7): the band leg's ends (each cause from a hand-built column), the side-step off a held column, and the
 * lip walk in the band way's price. The chain as JobBandChainTest's (iron, the bot at 0 64 0, a stone pickaxe).
 */
class JobBand2Test {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static BandEnd.Cell stone(int y) { return new BandEnd.Cell(y, "minecraft:stone", null, null); }

    @Test
    void reachedSaysLevelsSecondsBreaks() {
        assertEquals("reached y 16 (48 levels, 92 s, 41 blocks broken)", BandEnd.reached(64, 16, 92.2, 41));
    }

    @Test
    void water() {
        String c = BandEnd.cause(3, -7, List.of(stone(40), new BandEnd.Cell(39, "minecraft:water", "water", null), stone(38)));
        assertEquals("water in the column ahead at 3 39 -7", c);
        String w = BandEnd.stopped(3, -7, 64, 41, 16, c, "Baritone's goal ended at y 41 (no path into the band); no path (the graph exhausted)");
        assertTrue(w.startsWith("stopped at y 41 of 16 (23 of 48 levels, column 3 -7): water in the column ahead at 3 39 -7; the fork: "), w);
        assertArrayEquals(new int[]{3, -7}, BandEnd.heldColumn(w));
    }

    @Test
    void lava() {
        assertEquals("lava in the column ahead at 0 11 0", BandEnd.cause(0, 0, List.of(stone(12), new BandEnd.Cell(11, "minecraft:lava", "lava", null))));
    }

    @Test
    void bedrock() {
        assertEquals("bedrock at 0 -60 0", BandEnd.cause(0, 0, List.of(new BandEnd.Cell(-60, "minecraft:bedrock", null, null), new BandEnd.Cell(-61, "minecraft:lava", "lava", null))));
    }

    @Test
    void keepOut() {
        String c = BandEnd.cause(5, 5, List.of(stone(30), new BandEnd.Cell(29, "minecraft:stone", null, "keep-out: inside area home")));
        assertEquals("the cost advisor forbids breaking stone at 5 29 5 (keep-out: inside area home)", c);
    }

    @Test
    void noCauseIsSaid() {
        assertNull(BandEnd.cause(0, 0, List.of(stone(10))));
        assertTrue(BandEnd.stopped(0, 0, 64, 40, 16, null, null).contains("no water, lava, bedrock or forbidden cell in the column ahead; the fork: no path end read"));
    }

    @Test
    void sideStepAwayFromTheHeldColumn() {
        assertArrayEquals(new int[]{6, 0}, BandEnd.sideStep(new int[]{0, 64, 0}, new int[]{0, 0}, 6));
        assertArrayEquals(new int[]{-1, -8}, BandEnd.sideStep(new int[]{-1, 64, -2}, new int[]{-1, 0}, 6));
        assertNull(BandEnd.sideStep(new int[]{20, 64, 0}, new int[]{0, 0}, 6));
    }

    @Test
    void heldColumn_theBandWaySideSteps() {
        int peak = JobBandChainTest.where("raw_iron").peaks(false).get(0).y();
        String held = FactChange.bandKey(peak, peak) + ": " + BandEnd.stopped(0, 0, 64, 40, peak, "lava in the column ahead at 0 39 0", "x");
        Chain c = NeedChain.resolve("raw_iron", 3, JobBandChainTest.world().withHeld(List.of(held)));
        assertTrue(c.ok(), c.why());
        assertEquals(new Step.Walk(6, 64, 0, 0), c.steps().get(0), c.steps().toString());
        assertEquals(new Step.ToBand(peak, peak), c.steps().get(1), c.steps().toString());
        assertTrue(c.why().contains("side-step to column 6 0"), c.why());
    }

    @Test
    void lipWalkInThePrice() {
        int peak = JobBandChainTest.where("raw_iron").peaks(false).get(0).y();
        var cave = new NeedChain.CaveWay(10, peak - 4, 10, 50.0, Z77ExploreTest.IRON_ORE, "iron", 0.9, 0.0,
                null, 1.92, List.of(), List.of(new int[]{10, peak - 4, 10}))
                .withPassages(List.of(new io.github.mojolowjo.entropybot.cave.Passages.Passage(0, -1, new int[]{0, peak + 4, 0}, new int[]{0, peak - 4, 0}, 100, 1, 4, List.of(), 200, 0,
                        io.github.mojolowjo.entropybot.cave.Passages.EndKind.DEAD_END)))
                .withDensity(CaveWalk.Density.flat(0.00001, "test: a thin cave, so its own way is dear"));
        Chain c = NeedChain.resolve("raw_iron", 3, JobBandChainTest.world().withCave(w -> w.contains("raw_iron") ? cave : null));
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Cave), c.steps().toString());
        assertTrue(c.why().contains("the walk to the lip") && c.why().contains("4 levels down"), c.why());
    }

    @Test
    void lipAndSpanPure() {
        assertArrayEquals(new int[]{2, 18}, BandPrice.span(10, List.of(), 8));
        assertArrayEquals(new int[]{4, 30}, BandPrice.span(10, List.of(new int[]{0, 4, 0}, new int[]{0, 30, 0}), 8));
        var l = BandPrice.lip(Double.NaN, 12, 5, 2, new int[]{0, 9});
        assertEquals(17, l.seconds(), 1e-9);
        assertTrue(l.line().contains("no estimate: the straight walk 12.0 s") && l.line().contains("2 levels down"), l.line());
        assertEquals(20, BandPrice.lip(20, 12, 5, 0, new int[]{0, 9}).seconds(), 1e-9);
    }
}
