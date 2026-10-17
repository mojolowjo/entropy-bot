package io.github.mojolowjo.entropybot.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.33.0: the shaper takes failure from a job's own end status where there is one, the text only as a fallback. */
class EndStatusTest {
    @Test
    void endStatusWord() {
        assertFalse(ReplyShaper.endFailed("ok: mined 12 iron, couldn't reach 2"));
        assertFalse(ReplyShaper.endFailed("done (5 steps)"));
        assertTrue(ReplyShaper.endFailed("stopped: attacked by zombie"));
        assertTrue(ReplyShaper.endFailed("blocked by water at 1 2 3"));
        assertTrue(ReplyShaper.endFailed("walked 40 blocks, ran out of time"));
        assertFalse(ReplyShaper.endFailed(null));
    }

    @Test
    void statusWinsOverText() {
        ReplyShaper s = new ReplyShaper();
        // the text reads like a failure ("couldn't"), the job said ok: no "why" option, why stays empty
        String out = s.shapeLazy(null, "couldn't reach 2 of them, the rest is in the chest", false, null, List::of);
        assertFalse(out.contains("why"), out);
        assertTrue(s.why(System.currentTimeMillis(), "").startsWith("no failure yet"));
        // the text reads fine, the job's status says it failed: kept for why
        String bad = s.shapeLazy(null, "mine: walked home after 3 ores", true, null, List::of);
        assertTrue(bad.contains("why"), bad);
        assertTrue(s.why(System.currentTimeMillis(), "").contains("walked home after 3 ores"));
        // no status: the text decides as before
        assertTrue(s.shapeLazy(null, "error: no path", null, null, List::of).contains("why"));
    }
}
