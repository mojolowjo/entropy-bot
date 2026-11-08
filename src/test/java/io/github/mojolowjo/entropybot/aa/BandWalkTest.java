package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-bandwalk (run JAX 06:57-07:04: the band way dropped on every re-price, "walk: path not known yet (computing)", the
 * cave way won at 3405 s): a dig column whose path is not known is priced by the band model, never dropped; a landed path
 * replaces it.
 */
class BandWalkTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    private static final Map<String, Integer> PICK = Map.of("minecraft:stone_pickaxe", 1);

    private static PathPrice deep(PathPrice.Answer under) {
        return (x, y, z, d) -> y < 40 ? under : PathPrice.Answer.priced(new PathPrice.Walk(FakePaths.walkS(d)));
    }

    @Test
    void aComputingDigColumn_theBandWayIsPricedByTheModelAndBeatsA3405sCave() {
        Chain c = NeedChain.resolve("raw_iron", 3, BandToolTest.world(PICK).withPath(deep(PathPrice.Answer.computing(""))));
        assertTrue(c.ok(), c.why());
        String band = BandToolTest.bandPart(c.why());
        assertTrue(band.contains(NeedChain.BAND_MODEL), band);
        assertTrue(band.contains("the dig priced by the band model"), band);
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.ToBand), "ToBand on the model price: " + c.steps());
        assertTrue(c.seconds() < 3405, "the band way competes under the JAX cave's 3405 s: " + c.seconds());
    }

    @Test
    void aTimedOutDigColumn_stillPricedByTheModel() {
        Chain c = NeedChain.resolve("raw_iron", 3, BandToolTest.world(PICK).withPath(deep(PathPrice.Answer.none("not known: the search ran out of its 2000 ms"))));
        assertTrue(BandToolTest.bandPart(c.why()).contains(NeedChain.BAND_MODEL), c.why());
    }

    @Test
    void aLandedPathReplacesTheModel() {
        Chain c = NeedChain.resolve("raw_iron", 3, BandToolTest.world(PICK).withPath(deep(PathPrice.Answer.priced(new PathPrice.Walk(200)))));
        String band = BandToolTest.bandPart(c.why());
        assertFalse(band.isEmpty(), c.why());
        assertFalse(band.contains(NeedChain.BAND_MODEL), band);
        assertTrue(band.contains("the dig priced by Baritone's path"), band);
    }

    @Test
    void anUnreachableColumnStillDrops() {
        Chain c = NeedChain.resolve("raw_iron", 3, BandToolTest.world(PICK).withPath(deep(PathPrice.Answer.unreachable("no path"))));
        assertFalse(BandToolTest.bandPart(c.why()).contains(NeedChain.BAND_MODEL), c.why());
    }

    @Test
    void theLineText() {
        assertEquals("dig down to y 16: 120 s (band model: the dig path not landed; walk: path not known yet (computing))",
                NeedChain.bandModelWords(true, 16, 120, NeedChain.COMPUTING).replace("120.0 s", "120 s"));
    }
}
