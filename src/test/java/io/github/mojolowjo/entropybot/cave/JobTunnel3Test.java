package io.github.mojolowjo.entropybot.cave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Job-tunnel3 (run JAW: four 51-block goals ended TIME / CHUNKS at their start): the tunnel runs in legs. */
class JobTunnel3Test {

    @Test
    void a51BlockTunnelRunsAsLegsOf6() {
        int left = 51, legs = 0, walked = 0;
        while (left > 0) {
            int steps = Tunnel.legSteps(left, 6, i -> true);
            assertTrue(steps >= 1 && steps <= 6);
            walked += steps;
            left -= steps;
            legs++;
        }
        assertEquals(51, walked);
        assertEquals(9, legs, "8 legs of 6 and a last of 3");
        assertEquals(3, Tunnel.legSteps(3, 6, i -> true), "the last leg stops at the end");
    }

    @Test
    void aShortLegIsRetriedOnceThenHeld() {
        assertEquals(Tunnel.ShortLeg.RETRY, Tunnel.shortLeg(false));
        assertEquals(Tunnel.ShortLeg.HOLD, Tunnel.shortLeg(true));
        assertEquals(2, Tunnel.legSteps(51, 2, i -> true), "the retry's goal is tunnel.shortLegBlocks ahead");
    }

    @Test
    void theLegGoalIsNeverInAnUnloadedChunk() {
        // run JAW north: the cell 51 north was unloaded; the goal and the cell past it must be loaded
        assertEquals(3, Tunnel.legSteps(51, 6, i -> i <= 4), "loaded to 4: the goal stops at 3, one cell short of the edge");
        assertEquals(0, Tunnel.legSteps(51, 6, i -> i <= 1), "only the first step loaded: no goal");
        assertEquals(0, Tunnel.legSteps(51, 6, i -> false));
        assertEquals(6, Tunnel.legSteps(51, 6, i -> i <= 7));
        assertEquals(2, Tunnel.legSteps(51, 6, i -> i != 4), "a gap stops the cap");
    }

    @Test
    void theEndLineCountsLegs() {
        String l = Tunnel.endLine("at the tunnel's end", 102, 300, 2, 1, "iron", 9);
        assertTrue(l.contains("1 mined, 9 leg(s)"), l);
    }
}
