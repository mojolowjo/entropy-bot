package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Camera v2: the air the bot saw, never a pocket behind a wall or round a corner. */
class SightTest {
    static Set<Long> seen(GridWorld w, double x, double y, double z, int r) {
        return new HashSet<>(Sight.seen(x, y, z, r, 10_000, w, (a, b, c) -> true));
    }

    @Test
    void aStraightTunnelIsSeen() {
        GridWorld w = new GridWorld().openBox(0, 10, 0, 8, 11, 0);
        Set<Long> s = seen(w, 0.5, 11.6, 0.5, 6);
        assertTrue(s.contains(CellKey.of(5, 10, 0)));
        assertTrue(s.contains(CellKey.of(5, 11, 0)));
        assertFalse(s.contains(CellKey.of(8, 11, 0)), "beyond the radius");
        for (long k : s) assertEquals(Shell.OPEN, w.kind(CellKey.x(k), CellKey.y(k), CellKey.z(k)));
    }

    @Test
    void aSealedPocketIsNeverSeen() {
        GridWorld w = new GridWorld().openBox(0, 10, 0, 4, 11, 0).open(2, 10, 2);   // pocket behind the wall at z 1
        assertFalse(seen(w, 0.5, 11.6, 0.5, 6).contains(CellKey.of(2, 10, 2)));
    }

    @Test
    void roundTheCornerIsNotSeen() {
        // a corridor east then a turn north at x 4: from x 0 the far part of the north leg is hidden
        GridWorld w = new GridWorld().openBox(0, 10, 0, 4, 10, 0).openBox(4, 10, -5, 4, 10, 0);
        Set<Long> s = seen(w, 0.5, 10.5, 0.5, 8);
        assertTrue(s.contains(CellKey.of(4, 10, 0)));
        assertFalse(s.contains(CellKey.of(4, 10, -4)), "round the corner");
    }

    @Test
    void aDiagonalCrackDoesNotLetItSeeThrough() {
        // two cells touching only on an edge: (0,10,0) and (1,11,0)... the line between their centres runs along the edge
        GridWorld w = new GridWorld().open(0, 10, 0).open(1, 11, 0);
        assertFalse(Sight.clear(0.5, 10.5, 0.5, 1.5, 11.5, 0.5, w), "both side cells are rock");
        w.open(1, 10, 0);
        assertFalse(Sight.clear(0.5, 10.5, 0.5, 1.5, 11.5, 0.5, w), "one side cell is still rock");
        w.open(0, 11, 0);
        assertTrue(Sight.clear(0.5, 10.5, 0.5, 1.5, 11.5, 0.5, w));
    }

    @Test
    void theFilterKeepsTheSurfaceOut() {
        GridWorld w = new GridWorld().openBox(0, 10, 0, 0, 20, 0);
        w.skyY = 15;
        List<Long> s = Sight.seen(0.5, 11.5, 0.5, 8, 1000, w, (x, y, z) -> !w.sky(x, y, z));
        for (long k : s) assertTrue(CellKey.y(k) < 15, "nothing sky-lit: " + CellKey.text(k));
        assertTrue(s.contains(CellKey.of(0, 14, 0)));
    }

    @Test
    void startingInRockSeesNothingAndTheCapHolds() {
        GridWorld w = new GridWorld().openBox(0, 0, 0, 9, 9, 9);
        assertTrue(Sight.seen(20.5, 20.5, 20.5, 5, 100, w, (x, y, z) -> true).isEmpty());
        assertEquals(50, Sight.seen(5.5, 5.5, 5.5, 5, 50, w, (x, y, z) -> true).size());
    }
}
