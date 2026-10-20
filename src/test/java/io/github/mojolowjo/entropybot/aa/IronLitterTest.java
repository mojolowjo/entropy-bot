package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.guard.Litter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** aa-litter: the pillar option carries min(litter, cleanup) and ends with the matching take-down / keep step. */
class IronLitterTest {
    /** An ore 6 up, closed (a pillar is the only way that is near). */
    static IronOptions.Ore ore(boolean sky, boolean open) {
        return new IronOptions.Ore(10, 70, 0, 12, true, open, sky, 3, false, null, false);
    }

    static IronOptions.Facts facts(IronOptions.Ore o, Litter.Site site) {
        return new IronOptions.Facts(List.of(o), 64, false, 20, true, -1, false, "no", -1, false, false, 0, null, null, site);
    }

    static IronOptions.Scored pillar(IronOptions.Choice c) {
        for (var s : c.ranked()) if (s.way() == IronOptions.Way.PILLAR_KNOWN) return s;
        return null;
    }

    /** The choice as if the pillar had won (it need not be the cheapest here). */
    static IronOptions.Choice asChoice(IronOptions.Choice c, IronOptions.Scored p) {
        return new IronOptions.Choice(p.way(), p.target(), c.smelt(), c.ranked(), c.dropped(), null, null, p.after());
    }

    @Test void noSiteFactsNoLitterTerm() {
        var c = IronOptions.choose(facts(ore(false, false), null), new IronOptions.Memory(), 0);
        var p = pillar(c);
        assertNotNull(p);
        assertNull(p.after());
        assertEquals(12 + 6 * IronOptions.PLACE_BLOCK + IronOptions.CLEAR, p.cost(), 1e-9);
        assertFalse(p.why().contains("litter"));
        assertTrue(asChoice(c, p).command(3).endsWith("dig"), asChoice(c, p).command(3));
    }

    @Test void surfacePillarIsTakenDownAndPriced() {
        // under the sky, near a build: 6 blocks x 30 s vs cleanup 6 x 1.1 + 3 s: take down, price the cleanup
        var site = new Litter.Site(5, 2, 3, 1, 0, 0, 0, false);
        var c = IronOptions.choose(facts(ore(true, true), site), new IronOptions.Memory(), 0);
        var p = pillar(c);
        assertNotNull(p);
        double cleanup = 6 * 1.1 + 3;
        assertEquals(12 + 6 * IronOptions.PLACE_BLOCK + IronOptions.CLEAR + cleanup * io.github.mojolowjo.entropybot.aa.WaterWays.WALK_BPS, p.cost(), 1e-6);
        assertTrue(p.why().contains("litter") && p.why().contains("take down"), p.why());
        assertEquals("restore placed " + io.github.mojolowjo.entropybot.restore.PlacedPlan.DEFAULT_RADIUS, p.after());
        assertTrue(asChoice(c, p).command(3).endsWith(" then restore placed 24"), asChoice(c, p).command(3));
    }

    @Test void sealedPillarWithTheReturnLegIsKept() {
        // sealed, 40 from any build: 6 x 2 s = 12 s of litter, the cleanup is 9.6 s and the return leg's reuse (60) is capped at it
        var site = new Litter.Site(40, 2, 3, 1, 0, 0, 0, false);
        var c = IronOptions.choose(facts(ore(false, false), site), new IronOptions.Memory(), 0);
        var p = pillar(c);
        assertNotNull(p);
        assertTrue(p.why().contains("keep"), p.why());
        assertEquals("restore keep pillar", p.after());
        assertEquals(12 + 6 * IronOptions.PLACE_BLOCK + IronOptions.CLEAR + 2.4 * io.github.mojolowjo.entropybot.aa.WaterWays.WALK_BPS, p.cost(), 1e-6);
        assertTrue(asChoice(c, p).command(3).endsWith(" then restore keep pillar"), asChoice(c, p).command(3));
    }

    @Test void ownBoxScaffoldIsFree() {
        var site = new Litter.Site(0, 2, 3, 1, 0, 0, 0, true);
        var c = IronOptions.choose(facts(ore(true, true), site), new IronOptions.Memory(), 0);
        var p = pillar(c);
        assertNotNull(p);
        assertNull(p.after());
        assertEquals(12 + 6 * IronOptions.PLACE_BLOCK + IronOptions.CLEAR, p.cost(), 1e-9);
    }
}
