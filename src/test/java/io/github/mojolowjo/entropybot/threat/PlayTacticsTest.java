package io.github.mojolowjo.entropybot.threat;

import static io.github.mojolowjo.entropybot.threat.ReachGridTest.box;
import static io.github.mojolowjo.entropybot.threat.ReachGridTest.flat;
import static io.github.mojolowjo.entropybot.threat.ReachGridTest.set;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.25.1: the cover rule (ranged attackers) and the high-ground rule (groups), on grids. */
class PlayTacticsTest {

    @Test
    void rangedByKindOrByArrowHits() {
        long now = 100_000;
        assertTrue(Cover.ranged("minecraft:skeleton", List.of(), now));
        assertTrue(Cover.ranged("pillager", null, now));
        assertFalse(Cover.ranged("zombie", List.of(), now));
        HitLog.Hit arrow = new HitLog.Hit(now - 2000, "minecraft:arrow", "minecraft:zombie", null, 3, 15, 0, 0, 0, null);
        assertTrue(Cover.ranged("zombie", List.of(arrow), now), "a zombie that shot arrows (a modded one) counts as ranged");
        HitLog.Hit old = new HitLog.Hit(now - 20_000, "minecraft:arrow", "minecraft:zombie", null, 3, 15, 0, 0, 0, null);
        assertFalse(Cover.ranged("zombie", List.of(old), now), "only the last 10 s count");
    }

    @Test
    void coverIsWantedBeyondTheRangeAndEndsWhenItClosesOrTheWaitRunsOut() {
        assertTrue(Cover.wanted(true, 12, 8));
        assertFalse(Cover.wanted(true, 6, 8));
        assertFalse(Cover.wanted(false, 12, 8));
        assertFalse(Cover.wanted(true, 12, 0), "threat.coverRange 0 = off");
        assertTrue(Cover.over(3.5, 20, 4, 15), "it closed in");
        assertTrue(Cover.over(10, 15 * 20, 4, 15), "the wait ran out");
        assertFalse(Cover.over(10, 100, 4, 15));
    }

    @Test
    void coverFindsACellBehindAWallAndNoneOnOpenGround() {
        ReachGrid open = flat();
        assertNull(Cover.find(open, 8, 1, 8, 8.5, 2.62, 0.5), "flat open ground: nowhere to hide");
        ReachGrid g = flat();
        box(g, 10, 1, 6, 12, 2, 6, ReachGrid.SOLID);         // a wall 3 long, 2 high
        Cover.Spot s = Cover.find(g, 8, 1, 8, 8.5, 2.62, 0.5);
        assertNotNull(s);
        assertTrue(s.moves() <= Cover.MAX_MOVES);
        assertNotNull(Cover.blocker(g, 8.5, 2.62, 0.5, s.x() + 0.5, s.y() + 1.6, s.z() + 0.5), "the skeleton's eye ray is blocked");
        assertNotNull(Cover.blocker(g, 8.5, 2.62, 0.5, s.x() + 0.5, s.y() + 0.9, s.z() + 0.5), "...and the chest ray too");
        assertTrue(s.bz() == 6 && s.bx() >= 10 && s.bx() <= 12, "behind the wall: " + s);
        String line = Cover.line("minecraft:skeleton", 8, 1, 0, 12.3, s, 4, 15);
        assertTrue(line.startsWith("take cover from skeleton at 8 1 0 (12 away): behind "), line);
        assertTrue(line.contains("until it closes"), line);
    }

    /** A 3x3 platform 2 high (top cells y 3) with one stair block in front: one way up. */
    static ReachGrid ledge() {
        ReachGrid g = flat();
        box(g, 10, 1, 7, 12, 2, 9, ReachGrid.SOLID);
        set(g, 9, 1, 8, ReachGrid.SOLID);
        return g;
    }


}
