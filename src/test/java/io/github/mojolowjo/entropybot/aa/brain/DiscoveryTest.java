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

    static io.github.mojolowjo.entropybot.aa.WayUp.Heading h(String dir, double mult, boolean water, int[] outside) {
        List<io.github.mojolowjo.entropybot.aa.Replan.Cell> cells = new ArrayList<>();
        for (int i = 0; i < 6; i++) cells.add(new io.github.mojolowjo.entropybot.aa.Replan.Cell(false, false, mult, false, water && i == 0, false));
        return new io.github.mojolowjo.entropybot.aa.WayUp.Heading(dir, 54, outside == null ? cells : List.of(), outside);
    }

    static Discovery.Facts withDowns(List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downs) {
        return new Discovery.Facts(-1, 1, false, true, null, 100, 70, 200, 16, 0.002, 0.4, false, downs);
    }

    @Test
    void theStaircaseTakesTheCheapestHeadingInMyAreas() {
        List<String> dd = new ArrayList<>();
        var r = Discovery.rank(withDowns(List.of(h("north", 1, false, new int[]{100, 40, 170}), h("south", 1, true, null),
                h("east", 1, false, null), h("west", Double.POSITIVE_INFINITY, false, null))), "iron", 5, dd);
        var s = r.get(0);
        assertEquals(Discovery.Kind.STRIP, s.kind());
        assertTrue(s.command().startsWith("dig 101 69 200 101 71 200 ores via engine then goto 101 69 200 then "), s.command());
        assertTrue(s.command().endsWith("goto 154 16 200 then place mine east then mine strip iron 5"), s.command());
        assertTrue(s.why().contains("stairs east ground"), s.why());
        assertTrue(dd.contains("strip at Y: stairs north leaves my areas at 100 40 170"), dd.toString());
        assertTrue(dd.contains("strip at Y: stairs west a cell may not be broken"), dd.toString());
        // the ground is in the price: a wet heading alone costs more than a dry one
        double dry = Discovery.rank(withDowns(List.of(h("east", 1, false, null))), "iron", 5, new ArrayList<>()).get(0).cost();
        double wet = Discovery.rank(withDowns(List.of(h("south", 1, true, null))), "iron", 5, new ArrayList<>()).get(0).cost();
        assertTrue(wet > dry, wet + " vs " + dry);
    }

    @Test
    void noHeadingInMyAreasNoStrip() {
        List<String> dd = new ArrayList<>();
        var r = Discovery.rank(withDowns(List.of(h("north", 1, false, new int[]{1, 2, 3}), h("south", 1, false, new int[]{4, 5, 6}))), "iron", 5, dd);
        assertTrue(r.isEmpty(), r.toString());
        assertTrue(dd.contains("strip at Y: no staircase heading down stays in my areas and may be dug"), dd.toString());
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
