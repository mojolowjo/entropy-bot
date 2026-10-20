package io.github.mojolowjo.entropybot.threat;

import io.github.mojolowjo.entropybot.move.ServerCmds;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.24.1: the live bug (phantoms at full health sent the bot /home to an old home 10M blocks off). */
class FleeNeverHomeTest {

    @Test
    void homeTeleportOnlyUnderSixAndWithServerCommandsOn() {
        assertFalse(ServerCmds.homeTp(20, true), "full health: never /home");
        assertFalse(ServerCmds.homeTp(6, true));
        assertTrue(ServerCmds.homeTp(5, true));
        assertFalse(ServerCmds.homeTp(5, false), "server commands off: walk");
    }

    @Test
    void absurdWalksAreRefused() {
        assertNull(ServerCmds.tooFar(1999, 2000));
        assertNull(ServerCmds.tooFar(2000, 2000));
        assertEquals("error: that is 5000 blocks away - too far to walk (path maxWalk 2000)", ServerCmds.tooFar(5000, 2000));
        String live = ServerCmds.tooFar(new int[]{-20, 54, 183}, new int[]{10_000_000, 70, 183}, 2000);
        assertNotNull(live);
        assertTrue(live.contains("too far to walk"), live);
        assertNull(ServerCmds.tooFar(new int[]{0, 64, 0}, new int[]{300, 64, 400}, 2000));
    }

    @Test
    void serverCommandsDefaultOffAndNoted() {
        boolean was = ServerCmds.on();
        try {
            ServerCmds.setOn(false);
            assertFalse(ServerCmds.allowed("home"));
            assertTrue(ServerCmds.checkLines().stream().anyMatch(l -> l.contains("home walked instead")));
            ServerCmds.setOn(true);
            assertTrue(ServerCmds.allowed("home"));
        } finally {
            ServerCmds.setOn(was);
        }
    }
}
