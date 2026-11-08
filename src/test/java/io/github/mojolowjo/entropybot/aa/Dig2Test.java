package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.BandDirect;
import io.github.mojolowjo.entropybot.aa.moves.BandLeg;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** job-dig2 (run JAU 05:38:39-05:38:44): the dig leg's not-known ask goes to Baritone's own path; a landed column is reused. */
class Dig2Test {
    private static final int[] FEET = {-111, 63, -5};

    @Test
    void notKnownAskHandsTheGoalToBaritone() {
        PathPrice.Answer timedOut = PathPrice.Answer.none("not known: the search ran out of its 2000 ms (priceAskFailureTimeoutMS)");
        BandLeg.Next next = BandLeg.of(timedOut, FEET, true);
        assertEquals(BandLeg.Act.NOT_KNOWN, next.act());
        assertTrue(BandDirect.handOver(next.act(), 0, 5), "not known: handed over at once, never an end");
        String w = BandDirect.words(16, "the ask ran out of its 2000 ms");
        assertEquals("dig down to y 16: the price is not known yet (the ask ran out of its 2000 ms); digging on Baritone's own path, the band price stands", w);
    }

    @Test
    void computingPastAskWaitHandsOverAndPricedDoesNot() {
        assertFalse(BandDirect.handOver(BandLeg.Act.WAIT, 4_999, 5));
        assertTrue(BandDirect.handOver(BandLeg.Act.WAIT, 5_000, 5));
        assertFalse(BandDirect.handOver(BandLeg.Act.WALK, 60_000, 5), "a priced path walks its own way");
        assertFalse(BandDirect.handOver(BandLeg.Act.FACT, 60_000, 5), "an exhausted no-path stays the band's fact");
    }

    @Test
    void landedColumnNearTheFeetIsReused() {
        List<int[]> landed = List.of(new int[]{-103, 16, -6}, new int[]{-110, 16, -5}, new int[]{-111, 30, -5});
        assertArrayEquals(new int[]{-110, 16, -5}, BandDirect.reuse(landed, FEET, 16, 8), "the nearest at the band Y");
        assertNull(BandDirect.reuse(List.of(new int[]{-90, 16, -5}), FEET, 16, 8), "21 m away: asked anew");
        assertNull(BandDirect.reuse(landed, FEET, 16, 0), "reuse off");
    }

    @Test
    void notKnownFactIsMarked() {
        assertTrue(new FactChange.Fact(FactChange.NOT_KNOWN + FactChange.bandKey(16, 16), "x").notKnown());
        assertFalse(new FactChange.Fact("source strip iron_ore|16|west", "x").notKnown());
    }
}
