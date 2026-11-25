package io.github.mojolowjo.entropybot.baritone;

import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import io.github.mojolowjo.entropybot.guard.PolicyStats;
import io.github.mojolowjo.entropybot.guard.StripSearch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review sent back (5): a place refused because its cell's block may not be broken is one ask and one deny at the place
 * site (none at the break site), the judge is not asked a second time, and the strip probe says "place", not "break".
 * The counts replay what placeCost and breakMultiplier do with the site, through {@link BreakSite}.
 */
class BreakSiteTest {
    @Test
    void aRefusedPlaceIsOneAskAndOneDenyAtThePlaceSite() {
        PolicyStats s = new PolicyStats();
        s.ask(PolicyStats.SITE_ASTAR_PLACE);                                   // placeCost's one ask
        BreakSite.PLACE.earlyDeny(s, "walking only");                         // breakMultiplier asked for the place
        BreakSite.PLACE.mineAsk(s);                                           // no second ask under MINE
        assertEquals(1, s.asks());
        assertEquals(1, s.denies(PolicyStats.SITE_ASTAR_PLACE));
        assertEquals(0, s.denies(PolicyStats.SITE_ASTAR_COST), "nothing is broken: no break-site deny");
        assertFalse(BreakSite.PLACE.judgeAsks(), "the judge counts the deny only, no second ask");
    }

    @Test
    void theBreakSiteKeepsItsOwnCounts() {
        PolicyStats s = new PolicyStats();
        BreakSite.COST.earlyDeny(s, "walking only");                          // the route map's context: not counted, as before
        BreakSite.COST.mineAsk(s);
        assertEquals(1, s.asks());
        assertEquals(0, s.denies(PolicyStats.SITE_ASTAR_COST));
        assertTrue(BreakSite.COST.judgeAsks());
        assertEquals("break", BreakSite.COST.verb());
    }

    @Test
    void theProbeLineSaysPlace() {
        StripSearch ss = new StripSearch();
        ss.begin(new StripSearch.Store() {
            Intent i = Intent.MINE; String by = StripSearch.BY;
            @Override public Intent intent() { return i; }
            @Override public String by() { return by; }
            @Override public void set(Intent n, String b) { i = n; by = b; }
        }, true);
        ss.focus(new int[]{5, -20, 5});
        String line = ss.refuse(ss.forSearch(), 5, -20, 5, "minecraft:stone", Intent.MINE, BreakSite.PLACE.verb(), "keep-out", "in neighbour (keep-out)", 1e6);
        assertNotNull(line);
        assertTrue(line.contains(BreakSite.PLACE_NEEDS_BREAK), line);
        assertFalse(line.contains(" break keep-out"), line);
    }
}
