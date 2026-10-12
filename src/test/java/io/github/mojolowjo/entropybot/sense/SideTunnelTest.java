package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.28.0 live (LAN flat world, the owner's shells): coal at 57 -60 -26 in a stone shell one block over the floor. */
class SideTunnelTest {
    private static String k(int x, int y, int z) { return x + " " + y + " " + z; }

    @Test
    void theShellOneOverTheFloor() {
        Set<String> solid = new HashSet<>();
        solid.add(k(57, -60, -26));       // the coal
        solid.add(k(57, -59, -26));       // stone over it
        solid.add(k(56, -60, -26));       // stone W, N, S at its level
        solid.add(k(57, -60, -27));
        solid.add(k(57, -60, -25));
        solid.add(k(58, -59, -26));       // stone E at -59 (E at -60 is air)
        KnownOre.Free w = (x, y, z) -> y > -61 && !solid.contains(k(x, y, z));      // grass floor at -61
        int[] t = KnownOre.sideTunnel(60, -26, 57, -60, -26, w, 16);
        assertNotNull(t);
        assertArrayEquals(new int[]{58, -60, -26, 58, -59, -26, 59, -60, -26}, t, "dig the 2-high cell E of the coal, stand one further E");
        // bot to the west: W at -60 and -59 open after the stone (56 -60 is stone): dig 56, stand at 55
        int[] west = KnownOre.sideTunnel(50, -26, 57, -60, -26, w, 16);
        assertArrayEquals(new int[]{56, -60, -26, 56, -59, -26, 55, -60, -26}, west);
        // already open beside it: nothing to dig
        solid.remove(k(58, -59, -26));
        int[] open = KnownOre.sideTunnel(60, -26, 57, -60, -26, w, 16);
        assertTrue(open[0] > open[3], "nothing to dig");
        assertArrayEquals(new int[]{58, -60, -26}, new int[]{open[6], open[7], open[8]});
        // buried deep in a solid world: no stand within 16
        assertNull(KnownOre.sideTunnel(60, -26, 57, -60, -26, (x, y, z) -> false, 16));
    }
}
