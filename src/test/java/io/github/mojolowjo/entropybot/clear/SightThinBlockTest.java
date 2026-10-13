package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SightThinBlockTest {

    @Test
    void thinBlockBesideTheBotIsSeen() {
        FakeWorld w = new FakeWorld();
        // eye at (0.5, 60 + 1.62, 0.5) = (0.5, 61.62, 0.5)
        double ex = 0.5, ey = 61.62, ez = 0.5;
        // stone at (0,59,0) and (1,59,0) for the bot's footing
        w.set(0, 59, 0, "stone");
        w.set(1, 59, 0, "stone");
        // snow at (1,60,0) - thin block beside the bot
        w.set(1, 60, 0, "snow");
        // sightOf should return non-null for the snow block
        ClearEngine.Sight s = ClearEngine.sightOf(w, ex, ey, ez, 1, 60, 0);
        assertNotNull(s, "should see the thin block beside the bot");
    }

    @Test
    void fullBlockBesideTheBotIsSeen() {
        FakeWorld w = new FakeWorld();
        double ex = 0.5, ey = 61.62, ez = 0.5;
        w.set(0, 59, 0, "stone");
        w.set(1, 59, 0, "stone");
        // stone at (1,60,0) - full block beside the bot
        w.set(1, 60, 0, "stone");
        ClearEngine.Sight s = ClearEngine.sightOf(w, ex, ey, ez, 1, 60, 0);
        assertNotNull(s, "should see the full block beside the bot");
    }

    @Test
    void thinBlockBehindAWallIsNotSeen() {
        FakeWorld w = new FakeWorld();
        double ex = 0.5, ey = 61.62, ez = 0.5;
        w.set(0, 59, 0, "stone");
        w.set(1, 59, 0, "stone");
        // stone wall at (1,60,0) and (1,61,0) blocking the view
        w.set(1, 60, 0, "stone");
        w.set(1, 61, 0, "stone");
        // snow at (2,60,0) behind the wall
        w.set(2, 60, 0, "snow");
        // sightOf should return null for the snow block behind the wall
        ClearEngine.Sight s = ClearEngine.sightOf(w, ex, ey, ez, 2, 60, 0);
        assertNull(s, "should not see the thin block behind a wall");
    }

    @Test
    void nothingToSeeInAir() {
        FakeWorld w = new FakeWorld();
        double ex = 0.5, ey = 61.62, ez = 0.5;
        w.set(0, 59, 0, "stone");
        w.set(1, 59, 0, "stone");
        // cell (1,60,0) left as air (default)
        ClearEngine.Sight s = ClearEngine.sightOf(w, ex, ey, ez, 1, 60, 0);
        assertNull(s, "should see nothing in air");
    }
}