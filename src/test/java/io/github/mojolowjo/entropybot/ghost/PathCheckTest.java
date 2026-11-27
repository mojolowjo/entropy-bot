package io.github.mojolowjo.entropybot.ghost;

import io.github.mojolowjo.entropybot.ghost.PathCheck.Facts;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Kind;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Point;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Reason;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathCheckTest {
    static final List<Point> LINE = List.of(new Point(1, -50, 0), new Point(2, -50, 0), new Point(3, -50, 0), new Point(4, -50, 0));
    static final List<MoveKind> WALK = List.of(MoveKind.OTHER, MoveKind.TRAVERSE, MoveKind.TRAVERSE, MoveKind.TRAVERSE);

    static Facts at(double x, double y, double z) {
        return new Facts(x, y, z, (int) Math.floor(x), (int) Math.floor(y + 0.1251), (int) Math.floor(z), true, false, false, false, 0, false, false);
    }

    static PathCheck line() { return new PathCheck(LINE, WALK, 40, 5, 3); }

    @Test
    void walkingThePointsPasses() {
        PathCheck c = line();
        assertNull(c.start());
        assertNull(c.tick(at(1.9, -50, 0.5)));
        assertNull(c.tick(at(2.2, -50, 0.5)));
        assertEquals(2, c.next());
        assertNull(c.tick(at(3.1, -50, 0.5)));
        Verdict v = c.tick(at(4.0, -50, 0.5));
        assertEquals(Kind.PASS, v.kind());
        assertEquals("pass (4 ghost ticks)", v.line());
    }

    @Test
    void aFastGhostMaySkipPoints() {
        PathCheck c = line();
        assertNull(c.tick(at(3.5, -50, 0.5)), "point 1 skipped, point 2 reached");
        assertEquals(3, c.next());
    }

    @Test
    void noProgressRefusesAfterThePointTicksAndHopsAreOffered() {
        PathCheck c = new PathCheck(LINE, WALK, 10, 3, 1);
        Verdict v = null;
        int t = 0;
        boolean offered = false;
        while (v == null) {
            v = c.tick(at(1.5, -50, 0.5));
            t++;
            if (c.mayHop()) { offered = true; c.hopped(); }
        }
        assertTrue(offered, "a stalled ghost may hop");
        assertFalse(c.mayHop(), "the one hop is used");
        assertEquals(Kind.REFUSE, v.kind());
        assertEquals(Reason.NO_PROGRESS, v.reason());
        assertEquals(11, t);
        assertTrue(v.line().contains("point 1 of 3, traverse"), v.line());
    }

    @Test
    void hazardsFallsAndAirRefuseAtOnce() {
        Facts lava = new Facts(1.5, -50, 0.5, 1, -50, 0, true, true, false, false, 0, false, false);
        assertEquals(Reason.HAZARD, line().tick(lava).reason());
        Facts fall = new Facts(1.5, -55, 0.5, 1, -55, 0, true, false, false, false, 2, false, false);
        Verdict v = line().tick(fall);
        assertEquals(Reason.HURT_FALL, v.reason());
        assertEquals("refuse: a fall that would hurt at 1 -55 0 after 1 ghost ticks (2 damage)", v.line());
        Facts air = new Facts(1.5, -50, 0.5, 1, -50, 0, false, false, false, false, 0, true, false);
        assertEquals(Reason.OUT_OF_AIR, line().tick(air).reason());
    }

    @Test
    void endingUnderTheGoalIsOffHeight() {
        List<Point> up = List.of(new Point(1, -50, 0), new Point(2, -48, 0));
        PathCheck c = new PathCheck(up, List.of(MoveKind.OTHER, MoveKind.ASCEND), 40, 5, 3);
        Verdict v = c.tick(new Facts(2.5, -50, 0.5, 2, -50, 0, true, false, false, false, 0, false, false));
        assertEquals(Reason.OFF_HEIGHT, v.reason());
    }

    @Test
    void aStuckGhostSaysStuck() {
        PathCheck c = new PathCheck(LINE, WALK, 2, 99, 0);
        Facts web = new Facts(1.5, -50, 0.5, 1, -50, 0, true, false, false, false, 0, false, true);
        assertNull(c.tick(web));
        assertNull(c.tick(web));
        assertEquals(Reason.STUCK, c.tick(web).reason());
    }

    @Test
    void aPathWithoutAMoveIsShortAndUnchecked() {
        PathCheck c = new PathCheck(List.of(new Point(0, 0, 0)), List.of(MoveKind.OTHER), 40, 5, 3);
        assertEquals(Kind.UNCHECKED, c.start().kind());
        assertEquals("unchecked: no move to check (1 points)", c.start().line());
    }
}
