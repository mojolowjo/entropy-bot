package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.28.2 (trial fix package 2, D): walled in at surface level by my own blocks. */
class ClimbSurfaceTest {
    /** Ground at y 63 (solid below 64); walls: the cells in {@code walls}; beyond the walls open. */
    private static ClimbPlan.Cells world(Set<String> walls, Set<String> hard) {
        return (x, y, z) -> {
            String k = x + " " + y + " " + z;
            if (hard.contains(k)) return ClimbPlan.Kind.BLOCKED;
            if (walls.contains(k)) return ClimbPlan.Kind.DIG;
            return y < 64 ? ClimbPlan.Kind.DIG : ClimbPlan.Kind.OPEN;
        };
    }

    private static Set<String> ring() {
        Set<String> s = new HashSet<>();
        for (int y = 64; y <= 65; y++) {
            s.add("1 " + y + " 0");
            s.add("-1 " + y + " 0");
            s.add("0 " + y + " 1");
            s.add("0 " + y + " -1");
        }
        return s;
    }

    @Test
    void notWalledIn() {
        Set<String> w = ring();
        w.remove("1 64 0");
        w.remove("1 65 0");
        assertNull(ClimbPlan.walledIn(world(w, Set.of()), (x, y, z) -> true, 0, 64, 0, "south"));
    }

    @Test
    void walledInByMyOwnDigsThePreferredSide() {
        ClimbPlan.Out o = ClimbPlan.walledIn(world(ring(), Set.of()), (x, y, z) -> true, 0, 64, 0, "east");
        assertNull(o.err());
        assertEquals("east", o.dir());
        assertEquals("dig 1 64 0 1 65 0 force ores via engine", ClimbPlan.outDig(o));
    }

    @Test
    void onlyMyOwnBlocks() {
        // only the west wall is mine
        ClimbPlan.Out o = ClimbPlan.walledIn(world(ring(), Set.of()), (x, y, z) -> x == -1, 0, 64, 0, "east");
        assertEquals("west", o.dir());
        // none is mine
        ClimbPlan.Out n = ClimbPlan.walledIn(world(ring(), Set.of()), (x, y, z) -> false, 0, 64, 0, "south");
        assertNotNull(n.err());
        assertTrue(n.err().contains("no side is my own wall"), n.err());
    }

    @Test
    void fewestBlocksWins() {
        Set<String> w = ring();
        w.remove("0 65 1");          // south: only the feet cell is closed
        ClimbPlan.Out o = ClimbPlan.walledIn(world(w, Set.of()), (x, y, z) -> true, 0, 64, 0, "east");
        assertEquals("south", o.dir());
        assertEquals("dig 0 64 1 0 64 1 force ores via engine", ClimbPlan.outDig(o));
    }

    @Test
    void notIntoAWallBeyond() {
        ClimbPlan.Out o = ClimbPlan.walledIn(world(ring(), Set.of("2 64 0")), (x, y, z) -> true, 0, 64, 0, "east");
        assertNotEquals("east", o.dir());
    }
}
