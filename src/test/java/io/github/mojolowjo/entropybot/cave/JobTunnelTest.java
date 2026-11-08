package io.github.mojolowjo.entropybot.cave;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Job-tunnel (run JAS): the band way's tunnel rules (cave.Tunnel). Test inputs, not game facts. */
class JobTunnelTest {
    static final int[] FEET = {8, 16, 8};

    @Test
    void theHeadingPrefersTheNeverSeenChunk() {
        // every chunk seen except the one east of the feet's chunk (cx 1)
        var pk = Tunnel.pick(FEET, 32, 64, (cx, cz) -> !(cx == 1 && cz == 0), Set.of(), null, null);
        assertEquals("east", pk.heading().name(), pk.why());
        assertTrue(pk.why().contains("never-seen chunk"), pk.why());
    }

    @Test
    void aTunnelledHeadingIsPassedOver() {
        var pk = Tunnel.pick(FEET, 32, 64, (cx, cz) -> !(cx == 1 && cz == 0), Set.of("east"), null, null);
        assertNotEquals("east", pk.heading().name(), pk.why());
        assertNull(Tunnel.pick(FEET, 32, 64, (cx, cz) -> true, Set.of("north", "east", "south", "west"), null, null).heading());
    }

    @Test
    void allSeenThenTheDensityThenTheEstimate() {
        var pk = Tunnel.pick(FEET, 32, 64, (cx, cz) -> true, Set.of(), h -> h.name().equals("west") ? 0.01 : 0.001, null);
        assertEquals("west", pk.heading().name(), pk.why());
        var e = Tunnel.pick(FEET, 32, 64, (cx, cz) -> true, Set.of(), null, h -> h.name().equals("south") ? 5 : 9);
        assertEquals("south", e.heading().name(), e.why());
    }

    @Test
    void aSeenOrePauses() {
        var h = Tunnel.heading("east");
        var n = Tunnel.next(new int[]{10, 16, 9}, null, new int[]{12, 17, 8}, false, false, h, new int[]{40, 16, 8}, FEET);
        assertEquals(Tunnel.Act.MINE, n.act(), n.why());
        assertEquals(Tunnel.Act.DIG, Tunnel.next(null, null, null, false, false, h, new int[]{40, 16, 8}, FEET).act());
    }

    @Test
    void aCaveCellEndsTheTunnelWithTheFact() {
        int[] start = {8, 16, 8};
        Set<Long> known = Set.of(Tunnel.key(8, 16, 8), Tunnel.key(8, 17, 8), Tunnel.key(9, 16, 8));
        int[] cave = Tunnel.caveAir(List.of(new int[]{8, 16, 8}, new int[]{9, 16, 8}, new int[]{11, 18, 8}), known);
        assertArrayEquals(new int[]{11, 18, 8}, cave);
        var n = Tunnel.next(null, null, cave, false, false, Tunnel.heading("east"), new int[]{40, 16, 8}, start);
        assertEquals(Tunnel.Act.END_FACT, n.act());
        assertEquals("cave air at 11 18 8: the cave step takes over", n.why());
        assertNull(Tunnel.caveAir(List.of(new int[]{8, 16, 8}), known));
    }

    @Test
    void waterAheadEndsAndHoldsTheHeading() {
        var n = Tunnel.next(null, "water ahead at 10 16 8", null, false, false, Tunnel.heading("east"), new int[]{40, 16, 8}, FEET);
        assertEquals(Tunnel.Act.END_FACT, n.act());
        assertTrue(n.why().contains("the heading east is held"), n.why());
    }

    @Test
    void theLengthIsTheBandPricesFaces() {
        // 3 ore at 0.004 a face: 750 faces; 2 blocks a step x 3 faces a break = 6 a step: 125 steps, capped at 64
        assertEquals(64, Tunnel.length(3, 0.004, 3, 64));
        assertEquals(42, Tunnel.length(1, 0.004, 3, 64));
        assertTrue(Tunnel.endLine("at the tunnel's end", 84, 200, 2, 1, "iron").contains("84 block(s) broken, 200 face(s) exposed, 2 iron ore seen, 1 mined"));
    }
}
