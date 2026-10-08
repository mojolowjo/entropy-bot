package io.github.mojolowjo.entropybot.camp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 0.24.3: one whisper after a join or respawn outside every area. */
class RespawnRuleTest {
    @Test void outsideAfterRespawnWhispersOnce() {
        RespawnRule r = new RespawnRule();
        int[] p = {63, 77, 16};
        assertNull(r.tick(false, 0, p, true));          // joined inside: settling
        assertNull(r.tick(false, 200, p, true));         // checked: inside, quiet
        assertNull(r.tick(true, 220, p, false));         // died
        assertNull(r.tick(false, 240, p, false));        // respawned, settling
        String w = r.tick(false, 340, p, false);
        assertEquals("I'm at 63 77 16, outside my areas - say: area here 48 camp, then bootstrap, or come get me", w);
        assertNull(r.tick(false, 360, p, false));        // once
        assertEquals(w, r.checkLine());
        assertEquals(1, r.homes().size());
        assertNull(r.tick(false, 380, p, true));
        assertNull(r.checkLine());                       // inside again: the check line goes
    }

    @Test void joinOutsideWhispersButIsNoHome() {
        RespawnRule r = new RespawnRule();
        r.joined();
        assertNull(r.tick(false, 0, new int[]{1, 2, 3}, false));
        assertNotNull(r.tick(false, 100, new int[]{1, 2, 3}, false));
        assertTrue(r.homes().isEmpty());
    }
}
