package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrereqTest {
    @Test void woodPrereqFromThePlannersNumber() {
        assertEquals(4, Ways.logsToCut(1));
        assertEquals(64, Ways.logsToCut(64));
        assertEquals(64, Ways.logsToCut(200));
        assertEquals(0, Ways.logsToCut(0), "no logs missing: no wood row");
    }
    @Test void zeroGainIsFailure() {
        assertTrue(AaLadder.zeroGain("done - mining coal: got 0 of 32 - no coal ore known yet (walked 0 blocks)"));
        assertFalse(AaLadder.zeroGain("done - mining coal: got 10 of 32"));
        assertFalse(AaLadder.zeroGain("done - got 0 of 0"));
        assertFalse(AaLadder.zeroGain(null));
    }

}
