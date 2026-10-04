package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** S1: a clear stuck outside its box says why (live: dig 0 38 112 15 54 127 behind a 2-high wall at x -1). */
class S1StuckTest {
    static final ClearBox BOX = new ClearBox(0, 38, 112, 15, 54, 127);

    static ClearJob job() {
        return ClearJob.start(new ClearJob.Options().box(BOX), null, new OreBook.Simple());
    }

    @Test
    void aWallTwoHighBetweenTheBotAndTheBoxIsNamed() {
        FakeWorld w = new FakeWorld();
        w.fill(-1, -1, 53, 54, 100, 140, "stone");           // the wall, longer than the grid reaches
        String why = ClearGrid.entryProblem(w, Bot.at(-1.5, 53, 120.5), job());
        assertEquals(" - I can't get into the box: no spot I can walk to sees into it (a wall 2 high at -1 53 120?) - break one block "
                + "for a step, or dig a box one wider", why);
        // the end message keeps its first sentence ("stopped: stuck") for whatever matches it
        String end = ClearEngine.finishMessage(w, job(), ClearEngine.STUCK + why);
        assertTrue(end.startsWith(ClearEngine.STUCK + " - I can't get into the box"), end);
    }

    @Test
    void noClaimWhenAWalkableSpotSeesIn() {
        FakeWorld w = new FakeWorld();
        w.fill(-1, -1, 53, 53, 100, 140, "stone");           // a 1-high step: it can climb it and see the box
        assertEquals("", ClearGrid.entryProblem(w, Bot.at(-1.5, 53, 120.5), job()));
        // nothing left to clear: no claim either
        FakeWorld empty = new FakeWorld();
        empty.fill(0, 15, 38, 54, 112, 127, "air");
        assertEquals("", ClearGrid.entryProblem(empty, Bot.at(-1.5, 53, 120.5), job()));
    }
}
