package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.camp.ArmorCareRules;
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

    @Test void armourCraftGetsTableWoodFirst() {
        String cmd = "craft leather_helmet 1 then wear";
        assertEquals("cut 4 then " + cmd, ArmorCareRules.withTablePrereq(cmd, false, Map.of("leather", 5)));
        assertEquals(cmd, ArmorCareRules.withTablePrereq(cmd, true, Map.of("leather", 5)));
        assertEquals(cmd, ArmorCareRules.withTablePrereq(cmd, false, Map.of("leather", 5, "oak_log", 1)));
        assertEquals(cmd, ArmorCareRules.withTablePrereq(cmd, false, Map.of("crafting_table", 1)));
        assertEquals("get iron_helmet 1 then wear", ArmorCareRules.withTablePrereq("get iron_helmet 1 then wear", false, Map.of()));
    }
}
