package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Camera v2 since 0.15.4: the real world never shows while the tunnel view is on (the frame guard), and what is drawn. */
class WorldVeilTest {
    static final WorldVeil.Verdict NOTHING = WorldVeil.Verdict.NOTHING, CLEAR = WorldVeil.Verdict.CLEAR, STOP = WorldVeil.Verdict.CLEAR_AND_STOP;

    @Test
    void aVeiledFrameNeedsNothing() {
        WorldVeil.Guard g = new WorldVeil.Guard();
        g.pre(true);
        assertTrue(g.armed());
        g.placed();
        g.veiled();
        assertEquals(NOTHING, g.post(true, true));
        assertEquals(1, g.veiledFrames());
        assertEquals(0, g.misses());
        assertFalse(g.armed(), "disarmed after the frame");
    }

    @Test
    void aMissedFrameIsClearedAndThreeInARowStopTheView() {
        WorldVeil.Guard g = new WorldVeil.Guard();
        for (int i = 1; i <= 2; i++) {
            g.pre(true);
            g.placed();
            assertEquals(CLEAR, g.post(true, true), "frame " + i);
        }
        g.pre(true);
        assertEquals(STOP, g.post(true, true), "armed without a veil, even with no camera move");
        assertEquals(3, g.misses());
    }

    @Test
    void aVeiledFrameResetsTheRun() {
        WorldVeil.Guard g = new WorldVeil.Guard();
        for (int round = 0; round < 5; round++) {
            g.pre(true);
            assertEquals(CLEAR, g.post(true, true));
            g.pre(true);
            assertEquals(CLEAR, g.post(true, true));
            g.pre(true);
            g.veiled();
            assertEquals(NOTHING, g.post(true, true));
        }
        assertEquals(10, g.misses());
        g.resetRun();
    }

    @Test
    void offFramesAndFramesWithoutALevelAreLeftAlone() {
        WorldVeil.Guard g = new WorldVeil.Guard();
        g.pre(false);
        assertFalse(g.armed());
        assertEquals(NOTHING, g.post(true, false));
        g.pre(true);
        assertEquals(NOTHING, g.post(false, true), "no level: nothing of the world was drawn");
        assertEquals(0, g.misses());
        assertEquals(0, g.frames());
    }

    @Test
    void turnedOffDuringTheFrame() {
        WorldVeil.Guard g = new WorldVeil.Guard();
        g.pre(true);
        g.placed();                                  // the camera already sits in the rock
        assertEquals(CLEAR, g.post(true, false), "the moved camera's frame is still hidden");
        g.pre(true);
        assertEquals(NOTHING, g.post(true, false), "camera not moved yet: the normal view is fine");
        assertEquals(0, g.misses(), "neither is a miss: the veil rightly stopped");
    }

    @Test
    void turnedOnDuringTheFrameIsNotArmed() {
        WorldVeil.Guard g = new WorldVeil.Guard();
        g.pre(false);                                // off at the frame's start: no camera move in this frame
        assertEquals(NOTHING, g.post(true, true));
        assertEquals(0, g.misses());
    }

    @Test
    void whoIsDrawn() {
        assertTrue(WorldVeil.shows(true, false, true, 1e9), "the bot always");
        assertTrue(WorldVeil.shows(false, true, false, 47 * 47));
        assertFalse(WorldVeil.shows(false, true, false, 49 * 49), "too far");
        assertFalse(WorldVeil.shows(false, true, true, 4), "invisible");
        assertFalse(WorldVeil.shows(false, false, false, 4), "items, frames, minecarts, armour stands: part of the world");
    }
}
