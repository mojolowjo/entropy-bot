package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import static io.github.mojolowjo.entropybot.commands.FenceGrace.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class FenceGraceTest {
    @Test
    void insideOrOneOutIsFineAsBefore() {
        assertEquals(OK, FenceGrace.verdict(0, true, -1, 1000));
        assertEquals(OK, FenceGrace.verdict(1, false, -1, 1000));
        assertEquals(OK, FenceGrace.verdict(1, true, 500, 1000), "back within 1: the caller resets the clock");
    }

    @Test
    void aDigTwoToFourOutGetsFifteenSeconds() {
        // the night: 260 -48 852 is 2 out of the tunnel area
        assertEquals(GRACE, FenceGrace.verdict(2, true, -1, 1000), "first seen: the clock starts");
        assertEquals(GRACE, FenceGrace.verdict(2, true, 1000, 1280));
        assertEquals(GRACE, FenceGrace.verdict(4, true, 1000, 1299));
        assertEquals(STOP, FenceGrace.verdict(3, true, 1000, 1300), "300 ticks later still out: stop");
    }

    @Test
    void farOutOrNotDiggingStopsAtOnce() {
        assertEquals(STOP, FenceGrace.verdict(5, true, -1, 1000), "more than 4 out");
        assertEquals(STOP, FenceGrace.verdict(2, false, -1, 1000), "a walk or a trip without dig leases");
    }

    @Test
    void theStopMessageSaysWhy() {
        assertEquals("stopped: I am outside my areas at 260 -48 852 - area <name> <r>",
                FenceGrace.stopMessage("260 -48 852", false));
        assertEquals("stopped: I am outside my areas at 260 -48 852 (and didn't get back in within 15 s) - area <name> <r>",
                FenceGrace.stopMessage("260 -48 852", true));
    }
}
