package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND3 3: the discovery options (explore cave, strip at Y, clear chunk) and their prices. */
class DiscoveryTest {
    static Discovery.Facts f(int caveDist, double rate, boolean marked, boolean areas, int bestY, double density, boolean owner) {
        return new Discovery.Facts(caveDist, rate, marked, areas, areas ? null : "outside my areas", 100, 70, 200, bestY, density, 0.4, owner);
    }

    @Test
    void aNearRichCaveBeatsTheStrip() {
        List<String> dd = new ArrayList<>();
        var r = Discovery.rank(f(20, 3, false, true, 16, 0.0005, false), "iron", 5, dd);
        assertEquals(Discovery.Kind.CAVE, r.get(0).kind());
        assertEquals("mine cave iron 5", r.get(0).command());
        assertTrue(dd.contains("clear chunk: the owner's goals only"), dd.toString());
    }

    @Test
    void noCaveStripsAtTheBestBand() {
        List<String> dd = new ArrayList<>();
        var r = Discovery.rank(f(-1, 1, false, true, 16, 0.002, false), "iron", 5, dd);
        assertEquals(Discovery.Kind.STRIP, r.get(0).kind());
        assertTrue(r.get(0).command().startsWith("dig 100 69 199 100 71 199 ores via engine then goto 100 69 199 then "), r.get(0).command());
        assertTrue(r.get(0).command().endsWith("goto 100 16 146 then place mine north then mine strip iron 5"), r.get(0).command());
        assertFalse(r.get(0).command().contains("torch"));
        assertTrue(r.get(0).why().contains("54 down"), r.get(0).why());
        // a marked mine: its own place
        assertEquals("mine strip iron 5", Discovery.rank(f(-1, 1, true, true, 16, 0.002, false), "iron", 5, dd).get(0).command());
    }

    @Test
    void outsideMyAreasOnlyTheCave() {
        List<String> dd = new ArrayList<>();
        var r = Discovery.rank(f(-1, 1, false, false, 16, 0.002, true), "iron", 5, dd);
        assertTrue(r.isEmpty());
        assertTrue(dd.stream().anyMatch(d -> d.startsWith("strip at Y: outside")), dd.toString());
        assertTrue(dd.contains("clear chunk: outside my areas"), dd.toString());
    }

    @Test
    void clearChunkForTheOwnerIsPricedPerOre() {
        List<String> dd = new ArrayList<>();
        var r = Discovery.rank(f(-1, 1, false, true, 16, 0.002, true), "iron", 5, dd);
        var c = r.stream().filter(o -> o.kind() == Discovery.Kind.CLEAR_CHUNK).findFirst().orElseThrow();
        assertEquals("dig 96 16 192 111 19 207 ores", c.command());
        assertTrue(c.cost() > 0);
    }

    @Test
    void theBandNeverAboveMeAndDensityUnknown() {
        assertEquals(70, Discovery.stripY(f(-1, 1, false, true, 120, -1, false)));
        assertEquals(70, Discovery.stripY(f(-1, 1, false, true, Integer.MIN_VALUE, -1, false)));
        assertEquals(Discovery.DEFAULT_TUNNEL_PER_ORE, Discovery.tunnelPerOre(-1));
        assertTrue(Discovery.tunnelPerOre(0.01) < Discovery.tunnelPerOre(0.001));
        // a richer band is cheaper
        List<String> dd = new ArrayList<>();
        double rich = Discovery.rank(f(-1, 1, false, true, 16, 0.01, false), "iron", 1, dd).get(0).cost();
        double poor = Discovery.rank(f(-1, 1, false, true, 16, 0.0005, false), "iron", 1, dd).get(0).cost();
        assertTrue(rich < poor);
    }
}
